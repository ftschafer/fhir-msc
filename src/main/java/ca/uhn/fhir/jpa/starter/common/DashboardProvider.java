package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Date;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Provides a custom dashboard API endpoint that aggregates clinical data
 * Accessible at: GET [base]/Patient/$dashboard-stats?block=Block-North-A
 */
@Component
public class DashboardProvider implements IResourceProvider {

    private static final String NEWS2_IDENTIFIER_SYSTEM = "urn:aggregate:news2";
    private static final String BLOCK_AVERAGE_SUFFIX = "|block-average";

    private final DaoRegistry daoRegistry;
    
    // Cache results for 30 seconds
    private DashboardStats cachedStats;
    private long cacheTimestamp = 0;
    @Value("${dashboard.cache-ms:5000}")
    private long cacheDurationMs;

    public DashboardProvider(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Patient.class; // Required but we're using custom operation
    }

    @Operation(name = "$dashboard-stats", idempotent = true, manualResponse = true)
    public void getDashboardStats(
            @OperationParam(name = "block") StringType blockParam,
            HttpServletRequest request, 
            HttpServletResponse response) {
        
        try {
            String filterBlock = blockParam != null ? blockParam.getValue() : null;
            String requestTs = request.getParameter("_ts");
            boolean bypassCache = requestTs != null && !requestTs.isBlank();
            
            // Check cache (only if no filter or matches current block)
            long now = System.currentTimeMillis();
            if (!bypassCache && cachedStats != null && (now - cacheTimestamp) < cacheDurationMs && filterBlock == null) {
                writeJsonResponse(response, cachedStats);
                return;
            }
            
            // Calculate fresh stats
            DashboardStats stats = calculateStats(filterBlock);
            
            // Update cache (only if no filter)
            if (filterBlock == null) {
                cachedStats = stats;
                cacheTimestamp = now;
            }
            
            writeJsonResponse(response, stats);
            
        } catch (Exception e) {
            try {
                response.setStatus(500);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {}
        }
    }

    private DashboardStats calculateStats(String filterBlock) {
        DashboardStats stats = new DashboardStats();

        // Build per-block stats from current patient state
        Map<String, BlockStats> blockStatsMap = new HashMap<>();
        Map<String, NeighborhoodStats> neighborhoodStatsMap = new HashMap<>();
        Map<String, Map<String, VitalSignAccumulator>> neighborhoodVitalMap = new HashMap<>();
        int totalPatients = 0;

        // Get all patients to map to blocks
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        IBundleProvider patientResults = patientDao.search(patientSearch);
        List<IBaseResource> patients = getAllResources(patientResults);

        Map<String, String> patientBlockMap = new HashMap<>();
        Map<String, String> blockNeighborhoodMap = new HashMap<>();
        for (IBaseResource res : patients) {
            Patient patient = (Patient) res;
            String patientId = patient.getIdElement().getIdPart();
            String block = extractBlock(patient);
            if (block == null || block.isBlank()) {
                continue;
            }
            if (filterBlock == null || filterBlock.isEmpty() || block.equals(filterBlock)) {
                patientBlockMap.put(patientId, block);
                String neighborhood = extractNeighborhood(patient);
                if (neighborhood != null && !neighborhood.isBlank()) {
                    blockNeighborhoodMap.putIfAbsent(block, neighborhood);
                }
            }
        }

        // Current patient count and total NEWS2 from Patient resources (source of truth)
        for (IBaseResource res : patients) {
            Patient patient = (Patient) res;
            String blockName = extractBlock(patient);
            if (blockName == null || blockName.isBlank()) {
                continue;
            }
            if (filterBlock != null && !filterBlock.isEmpty() && !filterBlock.equals(blockName)) {
                continue;
            }

            double score = extractNews2Score(patient);

            BlockStats bs = blockStatsMap.computeIfAbsent(blockName, k -> new BlockStats(k));
            bs.patientCount += 1;
            bs.totalScore += score;

            String neighborhood = blockNeighborhoodMap.get(blockName);
            bs.neighborhood = neighborhood != null ? neighborhood : "";

            totalPatients += 1;

            String neighborhoodKey = bs.neighborhood == null ? "" : bs.neighborhood;
            NeighborhoodStats ns = neighborhoodStatsMap.computeIfAbsent(neighborhoodKey, k -> new NeighborhoodStats(k));
            ns.totalScore += score;
            ns.patientCount += 1;
        }
        
        // Get conditions grouped by block (active only, evaluated in-code for robustness)
        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
        SearchParameterMap conditionSearch = new SearchParameterMap();
        conditionSearch.setLoadSynchronous(true);
        IBundleProvider conditionResults = conditionDao.search(conditionSearch);
        List<IBaseResource> conditions = getAllResources(conditionResults);

        int totalConditionsForBlocks = 0;
        for (IBaseResource res : conditions) {
            Condition c = (Condition) res;
            if (!isActiveCondition(c)) {
                continue;
            }

            String block = extractBlock(c);
            if (block == null || block.isBlank()) {
                String patientId = c.getSubject() != null
                    ? c.getSubject().getReferenceElement().getIdPart()
                    : null;
                if (patientId != null && !patientId.isBlank()) {
                    block = patientBlockMap.get(patientId);
                }
            }

            if (block != null && !block.isBlank()) {
                if (filterBlock != null && !filterBlock.isEmpty() && !filterBlock.equals(block)) {
                    continue;
                }
                BlockStats bs = blockStatsMap.get(block);
                if (bs != null) {
                    bs.conditionCount++;
                    totalConditionsForBlocks++;
                }
            }
        }
        stats.totalConditions = totalConditionsForBlocks;
        
        // Get vital sign averages grouped by block
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap avgSearch = new SearchParameterMap();
        avgSearch.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs-average"));
        avgSearch.setLoadSynchronous(true);
        IBundleProvider avgResults = observationDao.search(avgSearch);
        List<IBaseResource> averages = getAllResources(avgResults);
        
        for (IBaseResource res : averages) {
            Observation obs = (Observation) res;
            String block = extractBlockFromObservation(obs);
            if (block != null && (filterBlock == null || filterBlock.isEmpty() || block.equals(filterBlock))) {
                BlockStats bs = blockStatsMap.get(block);
                if (bs != null) {
                    VitalSignAverage vsa = new VitalSignAverage();
                    vsa.vitalSign = obs.getCode() != null && obs.getCode().hasCoding()
                        ? obs.getCode().getCodingFirstRep().getDisplay()
                        : "";
                    vsa.averageValue = obs.getValueQuantity() != null
                        ? obs.getValueQuantity().getValue().doubleValue()
                        : 0;
                    vsa.unit = obs.getValueQuantity() != null
                        ? obs.getValueQuantity().getUnit()
                        : "";
                    Extension sampleExt = obs.getExtensionByUrl("http://observation-sample-count");
                    if (sampleExt != null && sampleExt.getValue() instanceof IntegerType) {
                        Integer sampleCount = ((IntegerType) sampleExt.getValue()).getValue();
                        vsa.sampleCount = sampleCount != null ? sampleCount : 0;
                    } else {
                        vsa.sampleCount = 0;
                    }
                    bs.vitalSignAverages.add(vsa);

                    String neighborhoodKey = bs.neighborhood == null ? "" : bs.neighborhood;
                    Map<String, VitalSignAccumulator> byVital = neighborhoodVitalMap
                        .computeIfAbsent(neighborhoodKey, k -> new HashMap<>());
                    String vitalKey = vsa.vitalSign == null ? "" : vsa.vitalSign;
                    VitalSignAccumulator acc = byVital.computeIfAbsent(vitalKey, k -> new VitalSignAccumulator());
                    acc.add(vsa.averageValue, vsa.sampleCount, vsa.unit);
                }
            }
        }

        // Build neighborhood vital sign averages from aggregated block averages
        for (Map.Entry<String, NeighborhoodStats> entry : neighborhoodStatsMap.entrySet()) {
            String neighborhood = entry.getKey();
            NeighborhoodStats ns = entry.getValue();
            Map<String, VitalSignAccumulator> byVital = neighborhoodVitalMap.get(neighborhood);
            if (byVital == null || byVital.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, VitalSignAccumulator> vitalEntry : byVital.entrySet()) {
                VitalSignAverage avg = vitalEntry.getValue().toAverage(vitalEntry.getKey());
                if (avg != null) {
                    ns.vitalSignAverages.add(avg);
                }
            }
        }

        for (BlockStats blockStats : blockStatsMap.values()) {
            AggregateObservation aggregate = loadAggregateByIdentifier(observationDao, blockStats.block + BLOCK_AVERAGE_SUFFIX);
            if (aggregate != null) {
                blockStats.aggregateNews2 = aggregate.value;
                blockStats.aggregateSampleCount = aggregate.sampleCount;
            }
        }

        for (NeighborhoodStats neighborhoodStats : neighborhoodStatsMap.values()) {
            // Derive neighborhood average from block aggregate observations (previous-layer values only)
            double weightedSum = 0d;
            int totalWeight = 0;
            for (BlockStats blockStats : blockStatsMap.values()) {
                if (blockStats.aggregateNews2 == null) {
                    continue;
                }
                if (!java.util.Objects.equals(blockStats.neighborhood, neighborhoodStats.neighborhood)) {
                    continue;
                }
                int weight = blockStats.aggregateSampleCount != null && blockStats.aggregateSampleCount > 0
                    ? blockStats.aggregateSampleCount
                    : 1;
                weightedSum += blockStats.aggregateNews2 * weight;
                totalWeight += weight;
            }
            if (totalWeight > 0) {
                neighborhoodStats.aggregateNews2 = weightedSum / totalWeight;
            }
        }
        
        // Convert to lists
        stats.blockStats = new ArrayList<>(blockStatsMap.values());
        stats.neighborhoodStats = new ArrayList<>(neighborhoodStatsMap.values());
        stats.totalPatients = totalPatients;

        // ── Statistical Summarization: reuse the same blockStats the dashboard displays ─
        String neighborhoodName = stats.neighborhoodStats.isEmpty() ? ""
            : stats.neighborhoodStats.get(0).neighborhood;
        stats.blockStatistics = computeStatsSummary(stats.blockStats, neighborhoodName);

        return stats;
    }

    // ── Statistical computation ─────────────────────────────────────────────

    private static final double Z_THRESHOLD = 1.96;
    private static final int BOOTSTRAP_N = 1000;

    /**
     * Compute mean, median, variance, stddev, incidence, prevalence, z-scores
     * and 95 % bootstrap CI using the same aggregateNews2 values the cards show.
     */
    private StatsSummary computeStatsSummary(List<BlockStats> blocks, String neighborhoodLabel) {
        // Filter to blocks that have an aggregate value (the same ones shown in the dashboard)
        List<BlockStats> withAgg = blocks.stream()
            .filter(b -> b.aggregateNews2 != null)
            .collect(java.util.stream.Collectors.toList());

        StatsSummary s = new StatsSummary();
        s.neighborhood = neighborhoodLabel;
        s.blockCount = withAgg.size();
        if (withAgg.isEmpty()) return s;

        // avg NEWS2 per block — from aggregateNews2, the same value the block cards display
        double[] avgs = withAgg.stream()
            .mapToDouble(b -> b.aggregateNews2)
            .toArray();

        // Mean
        double sum = 0;
        for (double v : avgs) sum += v;
        s.mean = sum / avgs.length;

        // Median
        double[] sorted = avgs.clone();
        java.util.Arrays.sort(sorted);
        if (sorted.length % 2 == 0) {
            s.median = (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
        } else {
            s.median = sorted[sorted.length / 2];
        }

        // Variance (sample)
        if (avgs.length > 1) {
            double ss = 0;
            for (double v : avgs) ss += (v - s.mean) * (v - s.mean);
            s.variance = ss / (avgs.length - 1);
        }
        s.stdDev = Math.sqrt(s.variance);

        // Incidence rate (fraction of blocks with score > 0)
        long withScore = withAgg.stream().filter(b -> b.totalScore > 0).count();
        s.incidenceRate = (double) withScore / withAgg.size();

        // Prevalence rate (total score / total patients)
        int totalPat = withAgg.stream().mapToInt(b -> b.patientCount).sum();
        double totalScr = withAgg.stream().mapToDouble(b -> b.totalScore).sum();
        s.prevalenceRate = totalPat == 0 ? 0 : totalScr / totalPat;

        // Z-scores per block
        for (int i = 0; i < withAgg.size(); i++) {
            BlockStats bs = withAgg.get(i);
            double avg = avgs[i];
            double z = s.stdDev == 0 ? 0 : (avg - s.mean) / s.stdDev;
            boolean abnormal = Math.abs(z) >= Z_THRESHOLD;
            s.zScores.add(new BlockZScore(
                bs.block,
                neighborhoodLabel != null ? neighborhoodLabel : (bs.neighborhood != null ? bs.neighborhood : ""),
                avg, bs.patientCount, z, abnormal));
        }

        // Bootstrap 95 % CI for the mean
        if (avgs.length > 0) {
            java.util.Random rng = new java.util.Random(42);
            double[] means = new double[BOOTSTRAP_N];
            for (int i = 0; i < BOOTSTRAP_N; i++) {
                double bSum = 0;
                for (int j = 0; j < avgs.length; j++) {
                    bSum += avgs[rng.nextInt(avgs.length)];
                }
                means[i] = bSum / avgs.length;
            }
            java.util.Arrays.sort(means);
            int lo = (int) Math.floor(0.025 * means.length);
            int hi = Math.min((int) Math.floor(0.975 * means.length), means.length - 1);
            s.ciLower = means[Math.max(0, lo)];
            s.ciUpper = means[hi];
        }

        return s;
    }
    
    private String extractBlock(Patient patient) {
        Extension locExt = patient.getExtensionByUrl("http://patient-location");
        if (locExt != null) {
            Extension blockExt = locExt.getExtension().stream()
                .filter(e -> "block".equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() != null) {
                return normalize(blockExt.getValue().primitiveValue());
            }
        }

        // Fallback: infer block from scoped identifier "<neighborhood>-<block>-<resourceId>"
        // system: urn:patient:neigh-block-scope
        String fromIdentifier = extractBlockFromScopedIdentifier(
            patient.getIdentifier(),
            "urn:patient:neigh-block-scope"
        );
        if (fromIdentifier != null) {
            return fromIdentifier;
        }

        // Final fallback: infer block from patient id prefix like "b72-p5" -> "B72"
        String fromPatientId = extractBlockFromResourceId(patient.getIdElement().getIdPart());
        if (fromPatientId != null) {
            return fromPatientId;
        }

        return null;
    }

    private String extractNeighborhood(Patient patient) {
        Extension locExt = patient.getExtensionByUrl("http://patient-location");
        if (locExt != null) {
            for (Extension nested : locExt.getExtension()) {
                if ("neighborhood".equals(nested.getUrl()) && nested.getValue() instanceof StringType) {
                    String v = ((StringType) nested.getValue()).getValue();
                    if (v != null && !v.isBlank()) {
                        return normalize(v);
                    }
                }
            }
        }
        return null;
    }
    
    private String extractBlockFromObservation(Observation obs) {
        Extension locExt = obs.getExtensionByUrl("http://patient-location");
        if (locExt != null) {
            Extension blockExt = locExt.getExtension().stream()
                .filter(e -> "block".equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() != null) {
                return normalize(blockExt.getValue().primitiveValue());
            }
        }

        // Fallback: infer block from scoped identifier "<neighborhood>-<block>-<resourceId>"
        // system: urn:observation:neigh-block-scope
        String fromIdentifier = extractBlockFromScopedIdentifier(
            obs.getIdentifier(),
            "urn:observation:neigh-block-scope"
        );
        if (fromIdentifier != null) {
            return fromIdentifier;
        }

        return null;
    }

    private String extractBlockFromScopedIdentifier(List<Identifier> identifiers, String system) {
        if (identifiers == null || identifiers.isEmpty()) {
            return null;
        }
        for (Identifier id : identifiers) {
            if (id == null || id.getValue() == null) {
                continue;
            }
            if (system.equals(id.getSystem())) {
                String value = id.getValue();
                // expected: <neighborhood>-<block>-<resourceId>
                int first = value.indexOf('-');
                int second = value.indexOf('-', first + 1);
                if (first > 0 && second > first + 1) {
                    return normalize(value.substring(first + 1, second));
                }
            }
        }
        return null;
    }

    private String normalize(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String extractBlockFromResourceId(String resourceId) {
        String id = normalize(resourceId);
        if (id == null) {
            return null;
        }

        int dash = id.indexOf('-');
        if (dash <= 1) {
            return null;
        }

        String prefix = id.substring(0, dash);
        if (prefix.length() < 2) {
            return null;
        }

        char first = prefix.charAt(0);
        if (first != 'b' && first != 'B') {
            return null;
        }

        String number = prefix.substring(1);
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) {
                return null;
            }
        }

        return "B" + number;
    }

    private List<IBaseResource> getAllResources(IBundleProvider provider) {
        if (provider == null) {
            return List.of();
        }

        Integer size = provider.size();
        if (size == null || size < 0) {
            return provider.getAllResources();
        }

        List<IBaseResource> all = new ArrayList<>(size);
        final int pageSize = 500;
        for (int from = 0; from < size; from += pageSize) {
            int to = Math.min(from + pageSize, size);
            List<IBaseResource> page = provider.getResources(from, to);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
        }
        return all;
    }

    private double extractNews2Score(Patient patient) {
        Extension ext = patient.getExtensionByUrl("http://news2-score");
        if (ext != null && ext.getValue() instanceof IntegerType) {
            Integer v = ((IntegerType) ext.getValue()).getValue();
            return v != null ? v.doubleValue() : 0d;
        }
        if (ext != null && ext.getValue() instanceof DecimalType) {
            java.math.BigDecimal v = ((DecimalType) ext.getValue()).getValue();
            return v != null ? v.doubleValue() : 0d;
        }
        return 0d;
    }

    private boolean isActiveCondition(Condition condition) {
        if (condition == null || !condition.hasClinicalStatus()) {
            return false;
        }
        return condition.getClinicalStatus().getCoding().stream()
            .anyMatch(c -> c != null && c.getCode() != null && "active".equalsIgnoreCase(c.getCode()));
    }

    private Double extractObservationNumericValue(Observation observation) {
        if (observation == null) {
            return null;
        }
        if (observation.hasValueQuantity() && observation.getValueQuantity().getValue() != null) {
            return observation.getValueQuantity().getValue().doubleValue();
        }
        if (observation.getValue() instanceof IntegerType integerType && integerType.getValue() != null) {
            return integerType.getValue().doubleValue();
        }
        if (observation.getValue() instanceof DecimalType decimalType && decimalType.getValue() != null) {
            return decimalType.getValue().doubleValue();
        }
        Extension ext = observation.getExtensionByUrl("http://news2-score");
        if (ext != null && ext.getValue() instanceof IntegerType integerType && integerType.getValue() != null) {
            return integerType.getValue().doubleValue();
        }
        if (ext != null && ext.getValue() instanceof DecimalType decimalType && decimalType.getValue() != null) {
            return decimalType.getValue().doubleValue();
        }
        return null;
    }

    private AggregateObservation loadAggregateByIdentifier(IFhirResourceDao<Observation> observationDao, String identifierValue) {
        String normalizedIdentifier = normalize(identifierValue);
        if (normalizedIdentifier == null) {
            return null;
        }

        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);
        search.add("identifier", new TokenParam(NEWS2_IDENTIFIER_SYSTEM, normalizedIdentifier));

        IBundleProvider provider = observationDao.search(search);
        List<IBaseResource> resources = getAllResources(provider);
        if (resources.isEmpty()) {
            return null;
        }

        Observation latest = resources.stream()
            .filter(Observation.class::isInstance)
            .map(Observation.class::cast)
            .max(Comparator.comparing(this::extractLastUpdatedForSort))
            .orElse(null);

        if (latest == null) {
            return null;
        }
        Double value = extractObservationNumericValue(latest);
        if (value == null) {
            return null;
        }
        return new AggregateObservation(value, extractSampleCount(latest));
    }

    private Date extractLastUpdatedForSort(Observation observation) {
        if (observation == null || observation.getMeta() == null || observation.getMeta().getLastUpdated() == null) {
            return new Date(0L);
        }
        return observation.getMeta().getLastUpdated();
    }

    private Integer extractSampleCount(Observation observation) {
        if (observation == null || !observation.hasExtension()) {
            return null;
        }
        Extension sampleExt = observation.getExtensionByUrl("http://observation-sample-count");
        if (sampleExt != null && sampleExt.getValue() instanceof IntegerType integerType) {
            Integer value = integerType.getValue();
            return value != null && value > 0 ? value : null;
        }
        return null;
    }

    private String extractBlock(Condition condition) {
        Extension locExt = condition.getExtensionByUrl("http://patient-location");
        if (locExt != null) {
            Extension blockExt = locExt.getExtension().stream()
                .filter(e -> "block".equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() != null) {
                return normalize(blockExt.getValue().primitiveValue());
            }
        }
        return null;
    }

    private void writeJsonResponse(HttpServletResponse response, DashboardStats stats) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        // Manual JSON serialization
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"totalPatients\":").append(stats.totalPatients).append(",");
        json.append("\"totalConditions\":").append(stats.totalConditions).append(",");
        
        // Neighborhood stats
        json.append("\"neighborhoodStats\":[");
        for (int i = 0; i < stats.neighborhoodStats.size(); i++) {
            NeighborhoodStats ns = stats.neighborhoodStats.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"neighborhood\":\"").append(escapeJson(ns.neighborhood)).append("\",");
            double neighborhoodAvgNews2 = ns.aggregateNews2 != null ? ns.aggregateNews2 : 0d;
            json.append("\"avgNews2\":").append(String.format(Locale.US, "%.1f", neighborhoodAvgNews2)).append(",");
            json.append("\"patientCount\":").append(ns.patientCount).append(",");

            json.append("\"vitalSignAverages\":[");
            for (int j = 0; j < ns.vitalSignAverages.size(); j++) {
                VitalSignAverage vsa = ns.vitalSignAverages.get(j);
                if (j > 0) json.append(",");
                json.append("{");
                json.append("\"vitalSign\":\"").append(escapeJson(vsa.vitalSign)).append("\",");
                json.append("\"averageValue\":").append(String.format(Locale.US, "%.2f", vsa.averageValue)).append(",");
                json.append("\"unit\":\"").append(escapeJson(vsa.unit)).append("\",");
                json.append("\"sampleCount\":").append(vsa.sampleCount);
                json.append("}");
            }
            json.append("]");
            json.append("}");
        }
        json.append("],");
        
        // Block stats (per neighborhood)
        json.append("\"blockStats\":[");
        for (int i = 0; i < stats.blockStats.size(); i++) {
            BlockStats bs = stats.blockStats.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"block\":\"").append(escapeJson(bs.block)).append("\",");
            json.append("\"neighborhood\":\"").append(escapeJson(bs.neighborhood)).append("\",");
            json.append("\"patientCount\":").append(bs.patientCount).append(",");
            json.append("\"conditionCount\":").append(bs.conditionCount).append(",");
            double blockAvgNews2 = bs.aggregateNews2 != null ? bs.aggregateNews2 : 0d;
            json.append("\"avgNews2\":").append(String.format(Locale.US, "%.1f", blockAvgNews2)).append(",");
            
            // Vital sign averages
            json.append("\"vitalSignAverages\":[");
            for (int j = 0; j < bs.vitalSignAverages.size(); j++) {
                VitalSignAverage vsa = bs.vitalSignAverages.get(j);
                if (j > 0) json.append(",");
                json.append("{");
                json.append("\"vitalSign\":\"").append(escapeJson(vsa.vitalSign)).append("\",");
                json.append("\"averageValue\":").append(String.format(Locale.US, "%.2f", vsa.averageValue)).append(",");
                json.append("\"unit\":\"").append(escapeJson(vsa.unit)).append("\",");
                json.append("\"sampleCount\":").append(vsa.sampleCount);
                json.append("}");
            }
            json.append("]");
            
            json.append("}");
        }
        json.append("],");

        // ── Statistical Summarization ───────────────────────────────────────
        json.append("\"blockStatistics\":");
        appendStatsSummaryJson(json, stats.blockStatistics);
        
        json.append("}");
        
        response.getWriter().write(json.toString());
    }

    /** Serializes a StatsSummary (with z-scores) to JSON */
    private void appendStatsSummaryJson(StringBuilder json, StatsSummary s) {
        if (s == null) { json.append("null"); return; }
        json.append("{");
        json.append("\"neighborhood\":\"").append(escapeJson(s.neighborhood)).append("\",");
        json.append("\"blockCount\":").append(s.blockCount).append(",");
        json.append("\"mean\":").append(String.format(Locale.US, "%.4f", s.mean)).append(",");
        json.append("\"median\":").append(String.format(Locale.US, "%.4f", s.median)).append(",");
        json.append("\"variance\":").append(String.format(Locale.US, "%.4f", s.variance)).append(",");
        json.append("\"stdDev\":").append(String.format(Locale.US, "%.4f", s.stdDev)).append(",");
        json.append("\"incidenceRate\":").append(String.format(Locale.US, "%.4f", s.incidenceRate)).append(",");
        json.append("\"prevalenceRate\":").append(String.format(Locale.US, "%.4f", s.prevalenceRate)).append(",");
        json.append("\"ciLower\":").append(String.format(Locale.US, "%.4f", s.ciLower)).append(",");
        json.append("\"ciUpper\":").append(String.format(Locale.US, "%.4f", s.ciUpper)).append(",");
        json.append("\"zScores\":[");
        for (int i = 0; i < s.zScores.size(); i++) {
            BlockZScore z = s.zScores.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"block\":\"").append(escapeJson(z.block)).append("\",");
            json.append("\"neighborhood\":\"").append(escapeJson(z.neighborhood)).append("\",");
            json.append("\"average\":").append(String.format(Locale.US, "%.4f", z.average)).append(",");
            json.append("\"patients\":").append(z.patients).append(",");
            json.append("\"zScore\":").append(String.format(Locale.US, "%.4f", z.zScore)).append(",");
            json.append("\"abnormal\":").append(z.abnormal);
            json.append("}");
        }
        json.append("]");
        json.append("}");
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }

    // Data classes
    static class BlockStats {
        String block;
        String neighborhood;
        int patientCount;
        double totalScore;
        Double aggregateNews2;
        Integer aggregateSampleCount;
        int conditionCount;
        List<VitalSignAverage> vitalSignAverages = new ArrayList<>();
        
        BlockStats(String block) {
            this.block = block;
        }
    }

    static class NeighborhoodStats {
        String neighborhood;
        double totalScore;
        int patientCount;
        Double aggregateNews2;
        List<VitalSignAverage> vitalSignAverages = new ArrayList<>();
        
        NeighborhoodStats(String neighborhood) {
            this.neighborhood = neighborhood;
        }
    }

    static class DashboardStats {
        int totalPatients;
        int totalConditions;
        List<BlockStats> blockStats = new ArrayList<>();
        List<NeighborhoodStats> neighborhoodStats = new ArrayList<>();
        StatsSummary blockStatistics;
    }

    /** Per-block z-score row */
    static class BlockZScore {
        String block;
        String neighborhood;
        double average;
        int patients;
        double zScore;
        boolean abnormal;

        BlockZScore(String block, String neighborhood, double average, int patients, double zScore, boolean abnormal) {
            this.block = block;
            this.neighborhood = neighborhood;
            this.average = average;
            this.patients = patients;
            this.zScore = zScore;
            this.abnormal = abnormal;
        }
    }

    /** Aggregated statistical summary for a set of blocks */
    static class StatsSummary {
        String neighborhood;
        int blockCount;
        double mean;
        double median;
        double variance;
        double stdDev;
        double incidenceRate;
        double prevalenceRate;
        double ciLower;
        double ciUpper;
        List<BlockZScore> zScores = new ArrayList<>();
    }


    
    static class VitalSignAverage {
        String vitalSign;
        double averageValue;
        String unit;
        int sampleCount;
    }

    static class VitalSignAccumulator {
        double weightedValueSum;
        int totalSamples;
        String unit;

        void add(double averageValue, int sampleCount, String unitValue) {
            int weight = sampleCount > 0 ? sampleCount : 1;
            weightedValueSum += averageValue * weight;
            totalSamples += weight;
            if ((unit == null || unit.isBlank()) && unitValue != null && !unitValue.isBlank()) {
                unit = unitValue;
            }
        }

        VitalSignAverage toAverage(String vitalSignName) {
            if (totalSamples <= 0) {
                return null;
            }
            VitalSignAverage out = new VitalSignAverage();
            out.vitalSign = vitalSignName;
            out.averageValue = weightedValueSum / totalSamples;
            out.unit = unit == null ? "" : unit;
            out.sampleCount = totalSamples;
            return out;
        }
    }

    static class AggregateObservation {
        final Double value;
        final Integer sampleCount;

        AggregateObservation(Double value, Integer sampleCount) {
            this.value = value;
            this.sampleCount = sampleCount;
        }
    }

}
