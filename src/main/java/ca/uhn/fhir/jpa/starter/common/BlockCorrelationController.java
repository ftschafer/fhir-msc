package ca.uhn.fhir.jpa.starter.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final int PERMUTATION_COUNT = 1000;
    private static final long PERMUTATION_SEED = 42L;
    private static final int MORAN_PERMUTATIONS = 999;

    private final DaoRegistry daoRegistry;

    @org.springframework.beans.factory.annotation.Value("${location.neighborhood}")
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

    // ── Moran's I — Spatial Autocorrelation ──────────────────────────────

    @GetMapping("/spatial-autocorrelation")
    @Transactional
    public Map<String, Object> getSpatialAutocorrelation(
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
            out.put("message", "No block-level data available for spatial analysis");
            out.put("variables", List.of());
            return out;
        }

        applyConditionCounts(byBlock);
        applyBlockNews2(byBlock);
        applyVitalSignAverages(byBlock);

        List<String> blockOrder = new ArrayList<>(byBlock.keySet());
        double[][] weights = buildSpatialWeights(blockOrder);

        List<Map<String, Object>> variables = new ArrayList<>();
        variables.add(moranResult("avgNews2", blockOrder, byBlock, b -> b.avgNews2, weights));
        variables.add(moranResult("conditionCount", blockOrder, byBlock, b -> asDouble(b.conditionCount), weights));
        variables.add(moranResult("heartRate", blockOrder, byBlock, b -> b.heartRate, weights));
        variables.add(moranResult("systolicBP", blockOrder, byBlock, b -> b.systolicBp, weights));
        variables.add(moranResult("diastolicBP", blockOrder, byBlock, b -> b.diastolicBp, weights));
        variables.add(moranResult("averageIncome", blockOrder, byBlock, b -> b.averageIncome, weights));
        variables.add(moranResult("careUnits", blockOrder, byBlock, b -> b.careUnits, weights));

        boolean anyClustered = variables.stream()
            .anyMatch(v -> "clustered".equals(v.get("pattern")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("neighborhood", neighborhoodFilter);
        out.put("blockCount", byBlock.size());
        out.put("weightMatrix", "row-standardized contiguity (numeric block adjacency, fallback: full connectivity)");
        out.put("permutations", MORAN_PERMUTATIONS);
        out.put("blockLevelInterventionJustified", anyClustered);
        out.put("variables", variables);
        return out;
    }

    /**
     * Build a row-standardized spatial weight matrix.  Tries to parse a
     * numeric suffix from each block identifier so that blocks whose numbers
     * differ by 1 are considered contiguous neighbours.  When numeric parsing
     * fails for any block, falls back to equal-weight (all blocks connected
     * to all others).
     */
    private double[][] buildSpatialWeights(List<String> blockOrder) {
        int n = blockOrder.size();
        double[][] w = new double[n][n];

        // Attempt to extract a numeric id from each block name
        Pattern numPattern = Pattern.compile("(\\d+)");
        int[] numericId = new int[n];
        boolean numericOk = true;
        for (int i = 0; i < n; i++) {
            Matcher m = numPattern.matcher(blockOrder.get(i));
            if (m.find()) {
                numericId[i] = Integer.parseInt(m.group(1));
            } else {
                numericOk = false;
                break;
            }
        }

        if (numericOk) {
            // Contiguity: neighbours if numeric ids differ by exactly 1
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j && Math.abs(numericId[i] - numericId[j]) == 1) {
                        w[i][j] = 1.0;
                    }
                }
            }
            // Check if any block ended up isolated (no numeric neighbour)
            boolean anyIsolated = false;
            for (int i = 0; i < n; i++) {
                double rowSum = 0;
                for (int j = 0; j < n; j++) rowSum += w[i][j];
                if (rowSum == 0) { anyIsolated = true; break; }
            }
            if (anyIsolated) {
                // Fall back to full connectivity
                for (int i = 0; i < n; i++)
                    for (int j = 0; j < n; j++)
                        w[i][j] = (i != j) ? 1.0 : 0.0;
            }
        } else {
            // Full connectivity fallback
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    w[i][j] = (i != j) ? 1.0 : 0.0;
        }

        // Row-standardize
        for (int i = 0; i < n; i++) {
            double rowSum = 0;
            for (int j = 0; j < n; j++) rowSum += w[i][j];
            if (rowSum > 0) {
                for (int j = 0; j < n; j++) w[i][j] /= rowSum;
            }
        }
        return w;
    }

    /**
     * Compute Moran's I for a single variable across blocks, with a
     * permutation-based pseudo p-value.
     */
    private Map<String, Object> moranResult(
        String variableName,
        List<String> blockOrder,
        Map<String, BlockRow> byBlock,
        ValueExtractor extractor,
        double[][] weights
    ) {
        // Collect values aligned with blockOrder, skipping nulls
        List<Double> values = new ArrayList<>();
        List<String> usedBlocks = new ArrayList<>();
        List<Integer> usedIndices = new ArrayList<>();
        for (int i = 0; i < blockOrder.size(); i++) {
            BlockRow row = byBlock.get(blockOrder.get(i));
            Double v = extractor.get(row);
            if (v != null && !v.isNaN()) {
                values.add(v);
                usedBlocks.add(blockOrder.get(i));
                usedIndices.add(i);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variable", variableName);
        out.put("blocksUsed", usedBlocks);
        out.put("n", values.size());

        if (values.size() < 3) {
            out.put("status", "insufficient_data");
            out.put("moranI", null);
            out.put("expectedI", null);
            out.put("pValue", null);
            out.put("zScore", null);
            out.put("pattern", "undetermined");
            out.put("interpretation", "Need at least 3 blocks with data");
            return out;
        }

        // Build sub-weight-matrix for the used indices
        int n = values.size();
        double[][] subW = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                subW[i][j] = weights[usedIndices.get(i)][usedIndices.get(j)];
        // Re-row-standardize after subsetting
        for (int i = 0; i < n; i++) {
            double rowSum = 0;
            for (int j = 0; j < n; j++) rowSum += subW[i][j];
            if (rowSum > 0) {
                for (int j = 0; j < n; j++) subW[i][j] /= rowSum;
            }
        }

        double observedI = computeMoranI(values, subW);
        double expectedI = -1.0 / (n - 1);

        // Variance check
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        if (ss == 0.0) {
            out.put("status", "zero_variance");
            out.put("moranI", null);
            out.put("expectedI", expectedI);
            out.put("pValue", null);
            out.put("zScore", null);
            out.put("pattern", "constant");
            out.put("interpretation", "All blocks have the same value — no spatial pattern to detect");
            return out;
        }

        // Permutation test
        Random rng = new Random(PERMUTATION_SEED);
        List<Double> shuffled = new ArrayList<>(values);
        int exceedCount = 0;
        double sumPermI = 0;
        double sumPermI2 = 0;

        for (int p = 0; p < MORAN_PERMUTATIONS; p++) {
            Collections.shuffle(shuffled, rng);
            double permI = computeMoranI(shuffled, subW);
            sumPermI += permI;
            sumPermI2 += permI * permI;
            if (Math.abs(permI) >= Math.abs(observedI)) {
                exceedCount++;
            }
        }

        double pValue = (exceedCount + 1.0) / (MORAN_PERMUTATIONS + 1.0);
        double permMean = sumPermI / MORAN_PERMUTATIONS;
        double permVar = (sumPermI2 / MORAN_PERMUTATIONS) - (permMean * permMean);
        Double zScore = permVar > 0 ? (observedI - permMean) / Math.sqrt(permVar) : null;

        String pattern;
        String interpretation;
        if (pValue < 0.05 && observedI > expectedI) {
            pattern = "clustered";
            interpretation = "Significant positive spatial autocorrelation — similar values cluster together. Block-level intervention is justified.";
        } else if (pValue < 0.05 && observedI < expectedI) {
            pattern = "dispersed";
            interpretation = "Significant negative spatial autocorrelation — neighbouring blocks tend to have dissimilar values.";
        } else {
            pattern = "random";
            interpretation = "No significant spatial pattern detected — values appear spatially random across blocks.";
        }

        out.put("status", "ok");
        out.put("moranI", Math.round(observedI * 10000.0) / 10000.0);
        out.put("expectedI", Math.round(expectedI * 10000.0) / 10000.0);
        out.put("pValue", Math.round(pValue * 10000.0) / 10000.0);
        out.put("zScore", zScore != null ? Math.round(zScore * 1000.0) / 1000.0 : null);
        out.put("significant", pValue < 0.05);
        out.put("pattern", pattern);
        out.put("interpretation", interpretation);
        return out;
    }

    /**
     * Global Moran's I statistic.
     * <pre>
     *   I = (n / W) * Σᵢ Σⱼ wᵢⱼ (xᵢ - x̄)(xⱼ - x̄)  /  Σᵢ (xᵢ - x̄)²
     * </pre>
     * where W = Σᵢ Σⱼ wᵢⱼ.
     */
    private double computeMoranI(List<Double> values, double[][] w) {
        int n = values.size();
        double mean = 0;
        for (double v : values) mean += v;
        mean /= n;

        double numerator = 0;
        double denominator = 0;
        double totalW = 0;

        for (int i = 0; i < n; i++) {
            double di = values.get(i) - mean;
            denominator += di * di;
            for (int j = 0; j < n; j++) {
                totalW += w[i][j];
                numerator += w[i][j] * di * (values.get(j) - mean);
            }
        }

        if (denominator == 0 || totalW == 0) {
            return 0;
        }
        return (n / totalW) * (numerator / denominator);
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
            out.put("pearsonPValue", null);
            out.put("spearmanPValue", null);
            out.put("kendallTauPValue", null);
            out.put("permutationTest", null);
            out.put("confidence", "low");
            out.put("note", "Need at least 3 blocks with both variables populated");
            return out;
        }

        Double pearson = pearson(xs, ys);
        Double spearman = spearman(xs, ys);
        Double kendallTau = kendallTauB(xs, ys);

        Double pearsonP = correlationPValue(pearson, xs.size());
        Double spearmanP = correlationPValue(spearman, xs.size());
        Double kendallP = kendallTauPValue(kendallTau, xs.size());
        Map<String, Object> permutation = permutationTest(xs, ys);

        out.put("status", pearson == null ? "undefined" : "ok");
        out.put("pearson", pearson);
        out.put("spearman", spearman);
        out.put("kendallTau", kendallTau);
        out.put("pearsonPValue", pearsonP);
        out.put("spearmanPValue", spearmanP);
        out.put("kendallTauPValue", kendallP);
        out.put("permutationTest", permutation);

        Double bestPValue = smallestNonNull(pearsonP, spearmanP, kendallP);
        out.put("confidence", confidenceLabel(xs.size(), spearman, kendallTau, bestPValue));
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

    private String confidenceLabel(int samples, Double spearman, Double kendallTau, Double pValue) {
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
        boolean significant = pValue != null && pValue < 0.05;

        if (samples >= 8 && avgSignal >= 0.35d && significant) {
            return "high";
        }
        if (samples >= 8 && avgSignal >= 0.35d) {
            return "medium";
        }
        if (samples >= 5 && avgSignal >= 0.2d && significant) {
            return "medium";
        }
        if (samples >= 5 && avgSignal >= 0.2d) {
            return "low-medium";
        }
        return "low";
    }

    // ── Analytical p-values ──────────────────────────────────────────────

    /**
     * Two-tailed p-value for Pearson or Spearman r using the t-distribution.
     * t = r * sqrt((n-2) / (1 - r²)), df = n - 2.
     */
    private Double correlationPValue(Double r, int n) {
        if (r == null || n < 3) {
            return null;
        }
        if (Math.abs(r) >= 1.0) {
            return 0.0;
        }
        double t = r * Math.sqrt((n - 2.0) / (1.0 - r * r));
        return tDistTwoTailP(t, n - 2);
    }

    /**
     * Two-tailed p-value for Kendall's tau using a normal approximation.
     * Variance = 2(2n+5) / (9n(n-1)), z = tau / sqrt(variance).
     */
    private Double kendallTauPValue(Double tau, int n) {
        if (tau == null || n < 3) {
            return null;
        }
        double variance = (2.0 * (2.0 * n + 5.0)) / (9.0 * n * (n - 1.0));
        double z = tau / Math.sqrt(variance);
        return 2.0 * normalCdfUpperTail(Math.abs(z));
    }

    /**
     * Two-tailed p-value from Student's t distribution using the
     * regularized incomplete beta function:
     * p = I_{df/(df+t²)}(df/2, 1/2)
     */
    private Double tDistTwoTailP(double t, int df) {
        if (df < 1) {
            return null;
        }
        double x = df / (df + t * t);
        double p = regularizedIncompleteBeta(df / 2.0, 0.5, x);
        return Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * Upper tail probability of the standard normal distribution.
     * Uses the Abramowitz & Stegun rational approximation (formula 26.2.17).
     */
    private double normalCdfUpperTail(double z) {
        if (z < 0) {
            return 1.0 - normalCdfUpperTail(-z);
        }
        double t = 1.0 / (1.0 + 0.2316419 * z);
        double pdf = Math.exp(-z * z / 2.0) / Math.sqrt(2.0 * Math.PI);
        double poly = t * (0.319381530
            + t * (-0.356563782
            + t * (1.781477937
            + t * (-1.821255978
            + t * 1.330274429))));
        return Math.max(0.0, Math.min(1.0, pdf * poly));
    }

    // ── Regularized incomplete beta function (for t-distribution CDF) ───

    private double regularizedIncompleteBeta(double a, double b, double x) {
        if (x < 0.0 || x > 1.0) {
            return Double.NaN;
        }
        if (x == 0.0) {
            return 0.0;
        }
        if (x == 1.0) {
            return 1.0;
        }
        double lnBeta = lnGamma(a) + lnGamma(b) - lnGamma(a + b);
        double front = Math.exp(Math.log(x) * a + Math.log(1.0 - x) * b - lnBeta);
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * betaContinuedFraction(a, b, x) / a;
        } else {
            return 1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b;
        }
    }

    /** Lentz continued-fraction evaluation for the incomplete beta function. */
    private double betaContinuedFraction(double a, double b, double x) {
        int maxIter = 200;
        double eps = 1e-14;
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;

        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < 1e-30) {
            d = 1e-30;
        }
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= maxIter; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            d = 1.0 / d;
            h *= d * c;

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            d = 1.0 / d;
            double del = d * c;
            h *= del;

            if (Math.abs(del - 1.0) < eps) {
                break;
            }
        }
        return h;
    }

    /** Lanczos approximation for ln(Gamma(x)). */
    private double lnGamma(double x) {
        double[] coef = {
            76.18009172947146, -86.50532032941677,
            24.01409824083091, -1.231739572450155,
            0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x - 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : coef) {
            y += 1.0;
            ser += c / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    // ── Permutation test ────────────────────────────────────────────────

    /**
     * Permutation test for Pearson r.  Shuffles the y-values
     * {@link #PERMUTATION_COUNT} times (deterministic seed) and reports
     * how often |r_perm| >= |r_observed|.
     */
    private Map<String, Object> permutationTest(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("permutations", PERMUTATION_COUNT);

        if (n < 3) {
            result.put("status", "insufficient_samples");
            result.put("observedPearson", null);
            result.put("pValue", null);
            result.put("significant_005", false);
            result.put("significant_001", false);
            return result;
        }

        Double observedR = pearson(xs, ys);
        if (observedR == null) {
            result.put("status", "undefined_zero_variance");
            result.put("observedPearson", null);
            result.put("pValue", null);
            result.put("significant_005", false);
            result.put("significant_001", false);
            return result;
        }

        double absObserved = Math.abs(observedR);
        int exceedCount = 0;
        Random rng = new Random(PERMUTATION_SEED);
        List<Double> shuffled = new ArrayList<>(ys);

        for (int p = 0; p < PERMUTATION_COUNT; p++) {
            Collections.shuffle(shuffled, rng);
            Double permR = pearson(xs, shuffled);
            if (permR != null && Math.abs(permR) >= absObserved) {
                exceedCount++;
            }
        }

        double pValue = (exceedCount + 1.0) / (PERMUTATION_COUNT + 1.0);
        result.put("status", "ok");
        result.put("observedPearson", observedR);
        result.put("pValue", Math.round(pValue * 10000.0) / 10000.0);
        result.put("significant_005", pValue < 0.05);
        result.put("significant_001", pValue < 0.01);
        return result;
    }

    private Double smallestNonNull(Double... values) {
        Double min = null;
        for (Double v : values) {
            if (v != null && (min == null || v < min)) {
                min = v;
            }
        }
        return min;
    }

    // ── Core correlation coefficients ───────────────────────────────────

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
