package ca.uhn.fhir.jpa.starter.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import jakarta.transaction.Transactional;

@RestController
@RequestMapping("/analytics")
public class BlockCorrelationController {

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";
    private static final String NEIGHBORHOOD_URL = "neighborhood";

    private static final String BLOCK_MEASURE = "Measure/block-health-aggregation";
    private static final String BLOCK_IDENTIFIER_SYSTEM = "urn:block:health-aggregation";

    private static final String NEWS2_IDENTIFIER_SYSTEM = "urn:aggregate:news2";
    private static final String BLOCK_AVERAGE_SUFFIX = "|block-average";

    private static final String STRAT_AVERAGE_INCOME = "Average Income";
    private static final String STRAT_CARE_UNITS = "Care Units";
    private static final String STRAT_MEAN_AGE = "Mean Age";

    private final DaoRegistry daoRegistry;

    @org.springframework.beans.factory.annotation.Value("${location.neighborhood:center}")
    private String configuredNeighborhood;

    public BlockCorrelationController(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @GetMapping("/block-correlations")
    @Transactional
    public Map<String, Object> getBlockCorrelations(
        @RequestParam(value = "neighborhood", required = false) String neighborhoodParam
    ) {
        String neighborhoodFilter = normalize(neighborhoodParam);
        if (neighborhoodFilter == null) {
            neighborhoodFilter = normalize(configuredNeighborhood);
        }

        Map<String, BlockRow> byBlock = loadBlockRows(neighborhoodFilter);
        if (byBlock.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("neighborhood", neighborhoodFilter);
            out.put("blockCount", 0);
            out.put("message", "No block-level data available for correlation");
            out.put("correlations", List.of());
            return out;
        }

        applyConditionCounts(byBlock);
        applyBlockNews2(byBlock);
        applyVitalSignAverages(byBlock);

        List<Map<String, Object>> correlations = new ArrayList<>();
        correlations.add(correlationResult("careUnits_vs_news2", byBlock, b -> b.careUnits, b -> b.avgNews2));
        correlations.add(correlationResult("averageIncome_vs_conditions", byBlock, b -> b.averageIncome, b -> asDouble(b.conditionCount)));
        correlations.add(correlationResult("meanAge_vs_heartRate", byBlock, b -> b.meanAge, b -> b.heartRate));
        correlations.add(correlationResult("meanAge_vs_systolicBP", byBlock, b -> b.meanAge, b -> b.systolicBp));
        correlations.add(correlationResult("meanAge_vs_diastolicBP", byBlock, b -> b.meanAge, b -> b.diastolicBp));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("neighborhood", neighborhoodFilter);
        out.put("blockCount", byBlock.size());
        out.put("minimumSampleForCorrelation", 3);
        out.put("correlations", correlations);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (BlockRow row : byBlock.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("block", row.block);
            entry.put("averageIncome", row.averageIncome);
            entry.put("careUnits", row.careUnits);
            entry.put("meanAge", row.meanAge);
            entry.put("avgNews2", row.avgNews2);
            entry.put("conditionCount", row.conditionCount);
            entry.put("heartRate", row.heartRate);
            entry.put("systolicBP", row.systolicBp);
            entry.put("diastolicBP", row.diastolicBp);
            rows.add(entry);
        }
        out.put("blockRows", rows);
        return out;
    }

    private Map<String, BlockRow> loadBlockRows(String neighborhoodFilter) {
        IFhirResourceDao<MeasureReport> dao = daoRegistry.getResourceDao(MeasureReport.class);
        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);
        IBundleProvider provider = dao.search(search);

        Map<String, BlockRow> out = new LinkedHashMap<>();
        for (IBaseResource resource : getAllResources(provider)) {
            if (!(resource instanceof MeasureReport report)) {
                continue;
            }
            if (!isBlockLayerMeasure(report)) {
                continue;
            }

            String neighborhood = extractNeighborhood(report);
            if (neighborhoodFilter != null && !neighborhoodFilter.equalsIgnoreCase(normalize(neighborhood))) {
                continue;
            }

            String block = extractBlock(report);
            if (block == null) {
                continue;
            }

            BlockRow row = out.computeIfAbsent(block, BlockRow::new);
            row.averageIncome = pickFirstNonNull(row.averageIncome, extractStratifierValue(report, STRAT_AVERAGE_INCOME));
            row.careUnits = pickFirstNonNull(row.careUnits, extractStratifierValue(report, STRAT_CARE_UNITS));
            row.meanAge = pickFirstNonNull(row.meanAge, extractStratifierValue(report, STRAT_MEAN_AGE));
        }

        return out;
    }

    private void applyConditionCounts(Map<String, BlockRow> byBlock) {
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        Map<String, String> patientBlockMap = new HashMap<>();
        for (IBaseResource resource : getAllResources(patientDao.search(patientSearch))) {
            if (!(resource instanceof Patient patient)) {
                continue;
            }
            String block = extractBlock(patient);
            if (block == null) {
                continue;
            }
            patientBlockMap.put(patient.getIdElement().getIdPart(), block);
        }

        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
        SearchParameterMap conditionSearch = new SearchParameterMap();
        conditionSearch.setLoadSynchronous(true);
        for (IBaseResource resource : getAllResources(conditionDao.search(conditionSearch))) {
            if (!(resource instanceof Condition condition)) {
                continue;
            }
            if (!isActiveCondition(condition)) {
                continue;
            }

            String block = extractBlock(condition);
            if (block == null && condition.hasSubject() && condition.getSubject().hasReferenceElement()) {
                String patientId = condition.getSubject().getReferenceElement().getIdPart();
                block = patientBlockMap.get(patientId);
            }
            if (block == null) {
                continue;
            }
            BlockRow row = byBlock.get(block);
            if (row != null) {
                row.conditionCount++;
            }
        }
    }

    private void applyBlockNews2(Map<String, BlockRow> byBlock) {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        for (BlockRow row : byBlock.values()) {
            Observation latest = loadLatestByIdentifier(observationDao, row.block + BLOCK_AVERAGE_SUFFIX);
            if (latest == null) {
                continue;
            }
            row.avgNews2 = extractObservationNumericValue(latest);
        }
    }

    private void applyVitalSignAverages(Map<String, BlockRow> byBlock) {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap search = new SearchParameterMap();
        search.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs-average"));
        search.setLoadSynchronous(true);

        Map<String, WeightedAccumulator> heartRate = new HashMap<>();
        Map<String, WeightedAccumulator> systolic = new HashMap<>();
        Map<String, WeightedAccumulator> diastolic = new HashMap<>();

        for (IBaseResource resource : getAllResources(observationDao.search(search))) {
            if (!(resource instanceof Observation observation)) {
                continue;
            }

            String block = extractBlock(observation);
            if (block == null || !byBlock.containsKey(block)) {
                continue;
            }

            Double value = extractObservationNumericValue(observation);
            if (value == null) {
                continue;
            }
            int weight = extractSampleCount(observation);

            String codeText = observation.hasCode() && observation.getCode().hasCoding()
                ? normalize(observation.getCode().getCodingFirstRep().getDisplay())
                : normalize(observation.getCode().getText());
            if (codeText == null) {
                continue;
            }

            String lower = codeText.toLowerCase(Locale.ROOT);
            if (lower.contains("heart") && lower.contains("rate")) {
                heartRate.computeIfAbsent(block, k -> new WeightedAccumulator()).add(value, weight);
            }
            if (lower.contains("systolic")) {
                systolic.computeIfAbsent(block, k -> new WeightedAccumulator()).add(value, weight);
            }
            if (lower.contains("diastolic")) {
                diastolic.computeIfAbsent(block, k -> new WeightedAccumulator()).add(value, weight);
            }
        }

        for (BlockRow row : byBlock.values()) {
            WeightedAccumulator hr = heartRate.get(row.block);
            WeightedAccumulator sbp = systolic.get(row.block);
            WeightedAccumulator dbp = diastolic.get(row.block);
            row.heartRate = hr == null ? null : hr.average();
            row.systolicBp = sbp == null ? null : sbp.average();
            row.diastolicBp = dbp == null ? null : dbp.average();
        }
    }

    private Map<String, Object> correlationResult(
        String label,
        Map<String, BlockRow> byBlock,
        ValueExtractor xExtractor,
        ValueExtractor yExtractor
    ) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<String> blocks = new ArrayList<>();

        for (BlockRow row : byBlock.values()) {
            Double x = xExtractor.get(row);
            Double y = yExtractor.get(row);
            if (x == null || y == null || x.isNaN() || y.isNaN()) {
                continue;
            }
            xs.add(x);
            ys.add(y);
            blocks.add(row.block);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("samples", xs.size());
        out.put("blocksUsed", blocks);

        if (xs.size() < 3) {
            out.put("status", "insufficient_samples");
            out.put("pearson", null);
            out.put("spearman", null);
            out.put("kendallTau", null);
            out.put("confidence", "low");
            out.put("note", "Need at least 3 blocks with both variables populated");
            return out;
        }

        Double pearson = pearson(xs, ys);
        Double spearman = spearman(xs, ys);
        Double kendallTau = kendallTauB(xs, ys);
        out.put("status", pearson == null ? "undefined" : "ok");
        out.put("pearson", pearson);
        out.put("spearman", spearman);
        out.put("kendallTau", kendallTau);
        out.put("confidence", confidenceLabel(xs.size(), spearman, kendallTau));
        if (pearson == null) {
            out.put("note", "One variable has zero variance across sampled blocks");
        }
        return out;
    }

    private Double spearman(List<Double> xs, List<Double> ys) {
        if (xs == null || ys == null || xs.size() != ys.size() || xs.size() < 2) {
            return null;
        }
        List<Double> rankX = ranks(xs);
        List<Double> rankY = ranks(ys);
        return pearson(rankX, rankY);
    }

    private List<Double> ranks(List<Double> values) {
        int n = values.size();
        List<Double> rank = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rank.add(0d);
        }

        List<Integer> indices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            indices.add(i);
        }
        indices.sort((a, b) -> Double.compare(values.get(a), values.get(b)));

        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(values.get(indices.get(j)), values.get(indices.get(j + 1))) == 0) {
                j++;
            }
            double avgRank = ((i + 1) + (j + 1)) / 2.0;
            for (int k = i; k <= j; k++) {
                rank.set(indices.get(k), avgRank);
            }
            i = j + 1;
        }

        return rank;
    }

    private Double kendallTauB(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n != ys.size() || n < 2) {
            return null;
        }

        long concordant = 0;
        long discordant = 0;
        long tieX = 0;
        long tieY = 0;

        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = xs.get(i) - xs.get(j);
                double dy = ys.get(i) - ys.get(j);
                int sx = Double.compare(dx, 0d);
                int sy = Double.compare(dy, 0d);

                if (sx == 0 && sy == 0) {
                    continue;
                }
                if (sx == 0) {
                    tieX++;
                    continue;
                }
                if (sy == 0) {
                    tieY++;
                    continue;
                }
                if (sx == sy) {
                    concordant++;
                } else {
                    discordant++;
                }
            }
        }

        double denominator = Math.sqrt((concordant + discordant + tieX) * (double) (concordant + discordant + tieY));
        if (denominator == 0d) {
            return null;
        }
        return (concordant - discordant) / denominator;
    }

    private String confidenceLabel(int samples, Double spearman, Double kendallTau) {
        if (samples < 4) {
            return "low";
        }

        double signal = 0d;
        int count = 0;
        if (spearman != null) {
            signal += Math.abs(spearman);
            count++;
        }
        if (kendallTau != null) {
            signal += Math.abs(kendallTau);
            count++;
        }
        double avgSignal = count == 0 ? 0d : signal / count;

        if (samples >= 8 && avgSignal >= 0.35d) {
            return "high";
        }
        if (samples >= 5 && avgSignal >= 0.2d) {
            return "medium";
        }
        return "low";
    }

    private Double pearson(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n != ys.size() || n < 2) {
            return null;
        }

        double sumX = 0d;
        double sumY = 0d;
        for (int i = 0; i < n; i++) {
            sumX += xs.get(i);
            sumY += ys.get(i);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double numerator = 0d;
        double denomX = 0d;
        double denomY = 0d;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            numerator += dx * dy;
            denomX += dx * dx;
            denomY += dy * dy;
        }

        if (denomX == 0d || denomY == 0d) {
            return null;
        }

        return numerator / Math.sqrt(denomX * denomY);
    }

    private Observation loadLatestByIdentifier(IFhirResourceDao<Observation> observationDao, String identifierValue) {
        String idValue = normalize(identifierValue);
        if (idValue == null) {
            return null;
        }
        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);
        search.add("identifier", new TokenParam(NEWS2_IDENTIFIER_SYSTEM, idValue));
        List<IBaseResource> resources = getAllResources(observationDao.search(search));
        Observation latest = null;
        for (IBaseResource resource : resources) {
            if (!(resource instanceof Observation current)) {
                continue;
            }
            if (latest == null) {
                latest = current;
                continue;
            }
            if (current.getMeta() != null && current.getMeta().getLastUpdated() != null
                && (latest.getMeta() == null || latest.getMeta().getLastUpdated() == null
                || current.getMeta().getLastUpdated().after(latest.getMeta().getLastUpdated()))) {
                latest = current;
            }
        }
        return latest;
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
        return null;
    }

    private int extractSampleCount(Observation observation) {
        if (observation == null) {
            return 1;
        }
        Extension sampleExt = observation.getExtensionByUrl("http://observation-sample-count");
        if (sampleExt != null && sampleExt.getValue() instanceof IntegerType integerType && integerType.getValue() != null) {
            int value = integerType.getValue();
            return value > 0 ? value : 1;
        }
        return 1;
    }

    private boolean isBlockLayerMeasure(MeasureReport report) {
        if (report == null) {
            return false;
        }
        if (BLOCK_MEASURE.equals(report.getMeasure())) {
            return true;
        }
        for (Identifier identifier : report.getIdentifier()) {
            if (identifier != null && BLOCK_IDENTIFIER_SYSTEM.equals(identifier.getSystem())) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal extractStratifierValue(MeasureReport report, String stratifierText) {
        if (report == null || !report.hasGroup()) {
            return null;
        }
        for (MeasureReport.MeasureReportGroupComponent group : report.getGroup()) {
            for (MeasureReport.MeasureReportGroupStratifierComponent stratifier : group.getStratifier()) {
                String codeText = null;
                if (!stratifier.getCode().isEmpty() && stratifier.getCodeFirstRep().hasText()) {
                    codeText = stratifier.getCodeFirstRep().getText();
                }
                if (codeText == null || !codeText.equalsIgnoreCase(stratifierText)) {
                    continue;
                }
                if (stratifier.hasStratum() && stratifier.getStratumFirstRep().hasMeasureScore()
                    && stratifier.getStratumFirstRep().getMeasureScore().getValue() != null) {
                    return stratifier.getStratumFirstRep().getMeasureScore().getValue();
                }
            }
        }
        return null;
    }

    private boolean isActiveCondition(Condition condition) {
        if (condition == null || !condition.hasClinicalStatus()) {
            return false;
        }
        return condition.getClinicalStatus().getCoding().stream()
            .anyMatch(c -> c != null && c.getCode() != null && "active".equalsIgnoreCase(c.getCode()));
    }

    private String extractBlock(Patient patient) {
        Extension location = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        return extractBlock(location);
    }

    private String extractBlock(Condition condition) {
        Extension location = condition.getExtensionByUrl(LOCATION_EXTENSION_URL);
        return extractBlock(location);
    }

    private String extractBlock(Observation observation) {
        Extension location = observation.getExtensionByUrl(LOCATION_EXTENSION_URL);
        return extractBlock(location);
    }

    private String extractBlock(MeasureReport report) {
        Extension location = report.getExtensionByUrl(LOCATION_EXTENSION_URL);
        return extractBlock(location);
    }

    private String extractNeighborhood(MeasureReport report) {
        Extension location = report.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (location == null) {
            return null;
        }
        for (Extension nested : location.getExtension()) {
            if (NEIGHBORHOOD_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                return normalize(nested.getValue().primitiveValue());
            }
        }
        return null;
    }

    private String extractBlock(Extension location) {
        if (location == null) {
            return null;
        }
        for (Extension nested : location.getExtension()) {
            if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                return normalize(nested.getValue().primitiveValue());
            }
        }
        return null;
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
        int pageSize = 500;
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

    private String normalize(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Double pickFirstNonNull(Double current, BigDecimal incoming) {
        if (current != null) {
            return current;
        }
        return incoming == null ? null : incoming.doubleValue();
    }

    private Double asDouble(int value) {
        return (double) value;
    }

    private interface ValueExtractor {
        Double get(BlockRow row);
    }

    private static class WeightedAccumulator {
        double weightedSum;
        int totalWeight;

        void add(double value, int weight) {
            int safeWeight = weight > 0 ? weight : 1;
            weightedSum += value * safeWeight;
            totalWeight += safeWeight;
        }

        Double average() {
            if (totalWeight <= 0) {
                return null;
            }
            return weightedSum / totalWeight;
        }
    }

    private static class BlockRow {
        final String block;
        Double averageIncome;
        Double careUnits;
        Double meanAge;
        Double avgNews2;
        int conditionCount;
        Double heartRate;
        Double systolicBp;
        Double diastolicBp;

        BlockRow(String block) {
            this.block = block;
        }
    }
}
