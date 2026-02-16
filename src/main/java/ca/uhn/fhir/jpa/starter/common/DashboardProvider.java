package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
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

    private final DaoRegistry daoRegistry;
    
    // Cache results for 30 seconds
    private DashboardStats cachedStats;
    private long cacheTimestamp = 0;
    private static final long CACHE_DURATION_MS = 30000;

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
            
            // Check cache (only if no filter or matches current block)
            long now = System.currentTimeMillis();
            if (cachedStats != null && (now - cacheTimestamp) < CACHE_DURATION_MS && filterBlock == null) {
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

            int score = extractNews2Score(patient);

            BlockStats bs = blockStatsMap.computeIfAbsent(blockName, k -> new BlockStats(k));
            bs.patientCount += 1;
            bs.totalScore += score;

            String neighborhood = blockNeighborhoodMap.get(blockName);
            bs.city = neighborhood != null ? neighborhood : "";
            bs.region = "";

            totalPatients += 1;

            String neighborhoodKey = bs.city == null ? "" : bs.city;
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
                }
            }
        }
        
        // Convert to lists
        stats.blockStats = new ArrayList<>(blockStatsMap.values());
        stats.neighborhoodStats = new ArrayList<>(neighborhoodStatsMap.values());
        stats.totalPatients = totalPatients;
        
        return stats;
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

    private int extractNews2Score(Patient patient) {
        Extension ext = patient.getExtensionByUrl("http://news2-score");
        if (ext != null && ext.getValue() instanceof IntegerType) {
            Integer v = ((IntegerType) ext.getValue()).getValue();
            return v != null ? v : 0;
        }
        return 0;
    }

    private boolean isActiveCondition(Condition condition) {
        if (condition == null || !condition.hasClinicalStatus()) {
            return false;
        }
        return condition.getClinicalStatus().getCoding().stream()
            .anyMatch(c -> c != null && c.getCode() != null && "active".equalsIgnoreCase(c.getCode()));
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
            json.append("\"avgNews2\":").append(String.format(Locale.US, "%.1f", ns.patientCount > 0 ? (double) ns.totalScore / ns.patientCount : 0)).append(",");
            json.append("\"patientCount\":").append(ns.patientCount);
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
            json.append("\"region\":\"").append(escapeJson(bs.region)).append("\",");
            json.append("\"city\":\"").append(escapeJson(bs.city)).append("\",");
            json.append("\"patientCount\":").append(bs.patientCount).append(",");
            json.append("\"conditionCount\":").append(bs.conditionCount).append(",");
            json.append("\"avgNews2\":").append(String.format(Locale.US, "%.1f", bs.patientCount > 0 ? (double) bs.totalScore / bs.patientCount : 0)).append(",");
            
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
        json.append("]");
        
        json.append("}");
        
        response.getWriter().write(json.toString());
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
        String region;
        String city;
        int patientCount;
        int totalScore;
        int conditionCount;
        List<VitalSignAverage> vitalSignAverages = new ArrayList<>();
        
        BlockStats(String block) {
            this.block = block;
        }
    }

    static class NeighborhoodStats {
        String neighborhood;
        int totalScore;
        int patientCount;
        
        NeighborhoodStats(String neighborhood) {
            this.neighborhood = neighborhood;
        }
    }

    static class DashboardStats {
        int totalPatients;
        int totalConditions;
        List<BlockStats> blockStats = new ArrayList<>();
        List<NeighborhoodStats> neighborhoodStats = new ArrayList<>();
    }
    
    static class VitalSignAverage {
        String vitalSign;
        double averageValue;
        String unit;
        int sampleCount;
    }

}
