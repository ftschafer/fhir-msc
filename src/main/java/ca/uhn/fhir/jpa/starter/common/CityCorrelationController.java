package ca.uhn.fhir.jpa.starter.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RestController;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import io.micrometer.core.instrument.Timer;
import jakarta.transaction.Transactional;

/**
 * City-level correlation and spatial-autocorrelation analytics.
 *
 * Hierarchy: city → neighbourhood → block.
 * This controller groups block-level FHIR data by neighbourhood and computes
 * Pearson / Spearman / Kendall correlations + Moran's I across the
 * neighbourhoods that belong to a given city.
 *
 * Endpoints:
 *   GET /analytics/neigh-correlations
 *   GET /analytics/neigh-spatial-autocorrelation
 *
 * City scope is fixed by application property {@code location.city}.
 */
@RestController
@RequestMapping("/analytics")
public class CityCorrelationController {

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL      = "block";
    private static final String NEIGHBORHOOD_URL = "neighborhood";
    private static final String CITY_URL       = "city";

    private static final String BLOCK_MEASURE               = "Measure/block-health-aggregation";
    private static final String BLOCK_IDENTIFIER_SYSTEM      = "urn:block:health-aggregation";

    private static final String NEIGHBORHOOD_MEASURE          = "Measure/neighborhood-health-aggregation";
    private static final String NEIGHBORHOOD_IDENTIFIER_SYSTEM = "urn:neighborhood:health-aggregation";

    private static final String NEWS2_IDENTIFIER_SYSTEM = "urn:aggregate:news2";
    private static final String BLOCK_AVERAGE_SUFFIX    = "|block-average";

    private static final String STRAT_AVERAGE_INCOME = "Average Income";
    private static final String STRAT_CARE_UNITS     = "Care Units";
    private static final String STRAT_MEAN_AGE       = "Mean Age";

    private static final int  PERMUTATION_COUNT  = 1000;
    private static final long PERMUTATION_SEED   = 42L;
    private static final int  MORAN_PERMUTATIONS = 999;

    private final DaoRegistry daoRegistry;
    private final PerfMetricsService perfMetrics;

    @org.springframework.beans.factory.annotation.Value("${location.city}")
    private String configuredCity;

    public CityCorrelationController(DaoRegistry daoRegistry, PerfMetricsService perfMetrics) {
        this.daoRegistry = daoRegistry;
        this.perfMetrics = perfMetrics;
    }

    // ── City-level correlations (derived from neighbourhood samples) ───────

    @GetMapping("/neigh-correlations")
    @Transactional
    public Map<String, Object> getNeighCorrelations() {
        perfMetrics.recordNeighCorrelationRequest();
        Timer.Sample sample = Timer.start();
        try {
            String cityFilter = resolveConfiguredCity();

            Map<String, NeighRow> byNeigh = loadNeighRows(cityFilter);
            if (byNeigh.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("city", cityFilter);
                out.put("neighCount", 0);
                out.put("message", "No neighbourhood source data available to compute city-level correlations");
                out.put("correlations", List.of());
                return out;
            }

            applyConditionCounts(byNeigh, cityFilter);
            applyNeighNews2(byNeigh);
            applyVitalSignAverages(byNeigh, cityFilter);

            List<Map<String, Object>> correlations = new ArrayList<>();
            correlations.add(correlationResult("careUnits_vs_news2",           byNeigh, r -> r.avgCare,   r -> r.avgNews2));
            correlations.add(correlationResult("averageIncome_vs_conditions",  byNeigh, r -> r.avgIncome, r -> asDouble(r.conditionCount)));
            correlations.add(correlationResult("meanAge_vs_heartRate",         byNeigh, r -> r.avgAge,    r -> r.heartRate));
            correlations.add(correlationResult("meanAge_vs_systolicBP",        byNeigh, r -> r.avgAge,    r -> r.systolicBp));
            correlations.add(correlationResult("meanAge_vs_diastolicBP",       byNeigh, r -> r.avgAge,    r -> r.diastolicBp));
            perfMetrics.recordNeighCorrelationComputations(correlations.size());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (NeighRow row : byNeigh.values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("neighbourhood", row.neigh);
                entry.put("averageIncome", row.avgIncome);
                entry.put("careUnits",     row.avgCare);
                entry.put("meanAge",       row.avgAge);
                entry.put("avgNews2",      row.avgNews2);
                entry.put("conditionCount", row.conditionCount);
                entry.put("heartRate",     row.heartRate);
                entry.put("systolicBP",    row.systolicBp);
                entry.put("diastolicBP",   row.diastolicBp);
                rows.add(entry);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("city",                       cityFilter);
            out.put("neighCount",                 byNeigh.size());
            out.put("minimumSampleForCorrelation", 3);
            out.put("correlations",               correlations);
            out.put("neighRows",                  rows);
            return out;
        } finally {
            sample.stop(perfMetrics.neighCorrelationTimer);
        }
    }

    // ── City-level spatial autocorrelation (using neighbourhood adjacency) ──

    @GetMapping("/neigh-spatial-autocorrelation")
    @Transactional
    public Map<String, Object> getNeighSpatialAutocorrelation() {
        perfMetrics.recordNeighSpatialAutocorrelationRequest();
        Timer.Sample sample = Timer.start();
        try {
            String cityFilter = resolveConfiguredCity();

            Map<String, NeighRow> byNeigh = loadNeighRows(cityFilter);
            if (byNeigh.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("city",      cityFilter);
                out.put("neighCount", 0);
                out.put("message",   "No neighbourhood source data available to compute city-level spatial analysis");
                out.put("variables", List.of());
                return out;
            }

            applyConditionCounts(byNeigh, cityFilter);
            applyNeighNews2(byNeigh);
            applyVitalSignAverages(byNeigh, cityFilter);

            List<String> neighOrder = new ArrayList<>(byNeigh.keySet());
            double[][] weights = buildSpatialWeights(neighOrder);

            List<Map<String, Object>> variables = new ArrayList<>();
            variables.add(moranResult("avgNews2",      neighOrder, byNeigh, r -> r.avgNews2,              weights));
            variables.add(moranResult("conditionCount", neighOrder, byNeigh, r -> asDouble(r.conditionCount), weights));
            variables.add(moranResult("heartRate",     neighOrder, byNeigh, r -> r.heartRate,             weights));
            variables.add(moranResult("systolicBP",    neighOrder, byNeigh, r -> r.systolicBp,            weights));
            variables.add(moranResult("diastolicBP",   neighOrder, byNeigh, r -> r.diastolicBp,           weights));
            variables.add(moranResult("averageIncome", neighOrder, byNeigh, r -> r.avgIncome,             weights));
            variables.add(moranResult("careUnits",     neighOrder, byNeigh, r -> r.avgCare,               weights));
            perfMetrics.recordNeighMoranVariableComputations(variables.size());

            boolean anyClustered = variables.stream()
                .anyMatch(v -> "clustered".equals(v.get("pattern")));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("city",       cityFilter);
            out.put("neighCount", byNeigh.size());
            out.put("weightMatrix",  "row-standardized contiguity (numeric neighbourhood adjacency, fallback: full connectivity)");
            out.put("permutations",  MORAN_PERMUTATIONS);
            out.put("blockLevelInterventionJustified", anyClustered);
            out.put("variables",  variables);
            return out;
        } finally {
            sample.stop(perfMetrics.neighSpatialAutocorrelationTimer);
        }
    }

    // ── Data loading: groups block-level FHIR data by neighbourhood ──────────

    /**
     * Reads all block-level MeasureReports, filters by city extension, and
     * returns one {@link NeighRow} per neighbourhood (averaging each metric
     * across the blocks that belong to it).
     */
    private Map<String, NeighRow> loadNeighRows(String cityFilter) {
        IFhirResourceDao<MeasureReport> dao = daoRegistry.getResourceDao(MeasureReport.class);
        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);
        IBundleProvider provider = dao.search(search);

        // Accumulators: neigh → [sum, count]
        Map<String, double[]> incomeAcc = new LinkedHashMap<>();
        Map<String, double[]> careAcc   = new LinkedHashMap<>();
        Map<String, double[]> ageAcc    = new LinkedHashMap<>();
        Set<String> allNeighs = new LinkedHashSet<>();

        for (IBaseResource resource : getAllResources(provider)) {
            if (!(resource instanceof MeasureReport report)) {
                continue;
            }
            if (!isBlockLayerMeasure(report) && !isNeighborhoodLayerMeasure(report)) {
                continue;
            }

            String city = extractCity(report);
            if (cityFilter != null && !cityFilter.equalsIgnoreCase(normalize(city))) {
                continue;
            }

            String neigh = extractNeighborhood(report);
            if (neigh == null) {
                continue;
            }
            allNeighs.add(neigh);

            BigDecimal income = extractStratifierValue(report, STRAT_AVERAGE_INCOME);
            BigDecimal care   = extractStratifierValue(report, STRAT_CARE_UNITS);
            BigDecimal age    = extractStratifierValue(report, STRAT_MEAN_AGE);

            if (income != null) {
                double[] acc = incomeAcc.computeIfAbsent(neigh, k -> new double[2]);
                acc[0] += income.doubleValue(); acc[1]++;
            }
            if (care != null) {
                double[] acc = careAcc.computeIfAbsent(neigh, k -> new double[2]);
                acc[0] += care.doubleValue(); acc[1]++;
            }
            if (age != null) {
                double[] acc = ageAcc.computeIfAbsent(neigh, k -> new double[2]);
                acc[0] += age.doubleValue(); acc[1]++;
            }
        }

        Map<String, NeighRow> out = new LinkedHashMap<>();
        for (String neigh : allNeighs) {
            NeighRow row = new NeighRow(neigh);
            double[] ia = incomeAcc.get(neigh);
            double[] ca = careAcc.get(neigh);
            double[] aa = ageAcc.get(neigh);
            row.avgIncome = (ia != null && ia[1] > 0) ? ia[0] / ia[1] : null;
            row.avgCare   = (ca != null && ca[1] > 0) ? ca[0] / ca[1] : null;
            row.avgAge    = (aa != null && aa[1] > 0) ? aa[0] / aa[1] : null;
            out.put(neigh, row);
        }
        return out;
    }

    /**
     * Counts active Conditions per neighbourhood (using patient → neigh mapping
     * as a fallback when the condition itself has no location extension).
     */
    private void applyConditionCounts(Map<String, NeighRow> byNeigh, String cityFilter) {
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap ps = new SearchParameterMap();
        ps.setLoadSynchronous(true);

        Map<String, String> patientNeighMap = new HashMap<>();
        for (IBaseResource resource : getAllResources(patientDao.search(ps))) {
            if (!(resource instanceof Patient patient)) {
                continue;
            }
            String city = extractCity(patient);
            if (cityFilter != null && !cityFilter.equalsIgnoreCase(normalize(city))) {
                continue;
            }
            String neigh = extractNeighborhood(patient);
            if (neigh == null || !byNeigh.containsKey(neigh)) {
                continue;
            }
            patientNeighMap.put(patient.getIdElement().getIdPart(), neigh);
        }

        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
        SearchParameterMap cs = new SearchParameterMap();
        cs.setLoadSynchronous(true);
        for (IBaseResource resource : getAllResources(conditionDao.search(cs))) {
            if (!(resource instanceof Condition condition)) {
                continue;
            }
            if (!isActiveCondition(condition)) {
                continue;
            }
            String neigh = extractNeighborhood(condition);
            if (neigh == null && condition.hasSubject() && condition.getSubject().hasReferenceElement()) {
                String patientId = condition.getSubject().getReferenceElement().getIdPart();
                neigh = patientNeighMap.get(patientId);
            }
            if (neigh == null) {
                continue;
            }
            NeighRow row = byNeigh.get(neigh);
            if (row != null) {
                row.conditionCount++;
            }
        }
    }

    /**
     * Aggregates block-level NEWS2 averages by neighbourhood.
     * Reads all Observations with identifier system {@code urn:aggregate:news2}
     * and groups them by the neighbourhood from their location extension.
     */
    private void applyNeighNews2(Map<String, NeighRow> byNeigh) {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);

        Map<String, WeightedAccumulator> news2Acc = new HashMap<>();
        for (IBaseResource resource : getAllResources(observationDao.search(search))) {
            if (!(resource instanceof Observation obs)) {
                continue;
            }
            if (!isBlockNews2Average(obs)) {
                continue;
            }
            String neigh = extractNeighborhood(obs);
            if (neigh == null || !byNeigh.containsKey(neigh)) {
                continue;
            }
            Double value = extractObservationNumericValue(obs);
            if (value == null) {
                continue;
            }
            int weight = extractSampleCount(obs);
            news2Acc.computeIfAbsent(neigh, k -> new WeightedAccumulator()).add(value, weight);
        }

        for (NeighRow row : byNeigh.values()) {
            WeightedAccumulator acc = news2Acc.get(row.neigh);
            row.avgNews2 = acc == null ? null : acc.average();
        }
    }

    /**
     * Aggregates block-level vital sign averages (HR, SBP, DBP) by neighbourhood.
     * Uses the {@code vital-signs-average} category and location extension.
     */
    private void applyVitalSignAverages(Map<String, NeighRow> byNeigh, String cityFilter) {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap search = new SearchParameterMap();
        search.add("category", new TokenParam(
            "http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs-average"));
        search.setLoadSynchronous(true);

        Map<String, WeightedAccumulator> heartRate = new HashMap<>();
        Map<String, WeightedAccumulator> systolic  = new HashMap<>();
        Map<String, WeightedAccumulator> diastolic = new HashMap<>();

        for (IBaseResource resource : getAllResources(observationDao.search(search))) {
            if (!(resource instanceof Observation observation)) {
                continue;
            }

            String city = extractCity(observation);
            if (cityFilter != null && city != null && !cityFilter.equalsIgnoreCase(normalize(city))) {
                continue;
            }

            String neigh = extractNeighborhood(observation);
            if (neigh == null || !byNeigh.containsKey(neigh)) {
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
                heartRate.computeIfAbsent(neigh, k -> new WeightedAccumulator()).add(value, weight);
            }
            if (lower.contains("systolic")) {
                systolic.computeIfAbsent(neigh, k -> new WeightedAccumulator()).add(value, weight);
            }
            if (lower.contains("diastolic")) {
                diastolic.computeIfAbsent(neigh, k -> new WeightedAccumulator()).add(value, weight);
            }
        }

        for (NeighRow row : byNeigh.values()) {
            WeightedAccumulator hr  = heartRate.get(row.neigh);
            WeightedAccumulator sbp = systolic.get(row.neigh);
            WeightedAccumulator dbp = diastolic.get(row.neigh);
            row.heartRate  = hr  == null ? null : hr.average();
            row.systolicBp = sbp == null ? null : sbp.average();
            row.diastolicBp = dbp == null ? null : dbp.average();
        }
    }

    // ── Moran's I ───────────────────────────────────────────────────────────

    /**
     * Row-standardized spatial weight matrix.
     * Neighbourhoods whose numeric suffix differs by 1 are contiguous
     * neighbours.  Falls back to full connectivity when parsing fails.
     */
    private double[][] buildSpatialWeights(List<String> neighOrder) {
        int n = neighOrder.size();
        double[][] w = new double[n][n];

        Pattern numPattern = Pattern.compile("(\\d+)");
        int[] numericId = new int[n];
        boolean numericOk = true;
        for (int i = 0; i < n; i++) {
            Matcher m = numPattern.matcher(neighOrder.get(i));
            if (m.find()) {
                numericId[i] = Integer.parseInt(m.group(1));
            } else {
                numericOk = false;
                break;
            }
        }

        if (numericOk) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j && Math.abs(numericId[i] - numericId[j]) == 1) {
                        w[i][j] = 1.0;
                    }
                }
            }
            // Fall back to full connectivity if any node is isolated
            boolean anyIsolated = false;
            for (int i = 0; i < n; i++) {
                double rowSum = 0;
                for (int j = 0; j < n; j++) rowSum += w[i][j];
                if (rowSum == 0) { anyIsolated = true; break; }
            }
            if (anyIsolated) {
                for (int i = 0; i < n; i++)
                    for (int j = 0; j < n; j++)
                        w[i][j] = (i != j) ? 1.0 : 0.0;
            }
        } else {
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

    private Map<String, Object> moranResult(
        String variableName,
        List<String> neighOrder,
        Map<String, NeighRow> byNeigh,
        ValueExtractor extractor,
        double[][] weights
    ) {
        List<Double>  values      = new ArrayList<>();
        List<String>  usedNeighs  = new ArrayList<>();
        List<Integer> usedIndices = new ArrayList<>();

        for (int i = 0; i < neighOrder.size(); i++) {
            NeighRow row = byNeigh.get(neighOrder.get(i));
            Double v = extractor.get(row);
            if (v != null && !v.isNaN()) {
                values.add(v);
                usedNeighs.add(neighOrder.get(i));
                usedIndices.add(i);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variable",    variableName);
        out.put("neighsUsed",  usedNeighs);
        out.put("n",           values.size());

        if (values.size() < 3) {
            out.put("status",          "insufficient_data");
            out.put("moranI",          null);
            out.put("expectedI",       null);
            out.put("pValue",          null);
            out.put("zScore",          null);
            out.put("pattern",         "undetermined");
            out.put("interpretation",  "Need at least 3 neighbourhoods with data");
            return out;
        }

        int n = values.size();
        double[][] subW = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                subW[i][j] = weights[usedIndices.get(i)][usedIndices.get(j)];
        for (int i = 0; i < n; i++) {
            double rowSum = 0;
            for (int j = 0; j < n; j++) rowSum += subW[i][j];
            if (rowSum > 0) {
                for (int j = 0; j < n; j++) subW[i][j] /= rowSum;
            }
        }

        double observedI = computeMoranI(values, subW);
        double expectedI = -1.0 / (n - 1);

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss   = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        if (ss == 0.0) {
            out.put("status",          "zero_variance");
            out.put("moranI",          null);
            out.put("expectedI",       Math.round(expectedI * 10000.0) / 10000.0);
            out.put("pValue",          null);
            out.put("zScore",          null);
            out.put("pattern",         "constant");
            out.put("interpretation",  "All neighbourhoods have the same value — no spatial pattern to detect");
            return out;
        }

        Random rng = new Random(PERMUTATION_SEED);
        List<Double> shuffled = new ArrayList<>(values);
        int exceedCount = 0;
        double sumPermI = 0;
        double sumPermI2 = 0;

        for (int p = 0; p < MORAN_PERMUTATIONS; p++) {
            Collections.shuffle(shuffled, rng);
            double permI = computeMoranI(shuffled, subW);
            sumPermI   += permI;
            sumPermI2  += permI * permI;
            if (Math.abs(permI) >= Math.abs(observedI)) {
                exceedCount++;
            }
        }

        double pValue   = (exceedCount + 1.0) / (MORAN_PERMUTATIONS + 1.0);
        double permMean = sumPermI / MORAN_PERMUTATIONS;
        double permVar  = (sumPermI2 / MORAN_PERMUTATIONS) - (permMean * permMean);
        Double zScore   = permVar > 0 ? (observedI - permMean) / Math.sqrt(permVar) : null;

        String pattern;
        String interpretation;
        if (pValue < 0.05 && observedI > expectedI) {
            pattern = "clustered";
            interpretation = "Significant positive spatial autocorrelation — similar values cluster together. Neighbourhood-level intervention is justified.";
        } else if (pValue < 0.05 && observedI < expectedI) {
            pattern = "dispersed";
            interpretation = "Significant negative spatial autocorrelation — neighbouring areas tend to have dissimilar values.";
        } else {
            pattern = "random";
            interpretation = "No significant spatial pattern detected — values appear spatially random across neighbourhoods.";
        }

        out.put("status",               "ok");
        out.put("moranI",               Math.round(observedI * 10000.0) / 10000.0);
        out.put("expectedI",            Math.round(expectedI * 10000.0) / 10000.0);
        out.put("pValue",               Math.round(pValue    * 10000.0) / 10000.0);
        out.put("zScore",               zScore != null ? Math.round(zScore * 1000.0) / 1000.0 : null);
        out.put("significant",          pValue < 0.05);
        out.put("pattern",              pattern);
        out.put("interpretation",       interpretation);
        return out;
    }

    private double computeMoranI(List<Double> values, double[][] w) {
        int n = values.size();
        double mean = 0;
        for (double v : values) mean += v;
        mean /= n;

        double numerator = 0, denominator = 0, totalW = 0;
        for (int i = 0; i < n; i++) {
            double di = values.get(i) - mean;
            denominator += di * di;
            for (int j = 0; j < n; j++) {
                totalW    += w[i][j];
                numerator += w[i][j] * di * (values.get(j) - mean);
            }
        }
        if (denominator == 0 || totalW == 0) return 0;
        return (n / totalW) * (numerator / denominator);
    }

    // ── Correlation coefficients + p-values ─────────────────────────────────

    private Map<String, Object> correlationResult(
        String label,
        Map<String, NeighRow> byNeigh,
        ValueExtractor xExtractor,
        ValueExtractor yExtractor
    ) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<String> neighs = new ArrayList<>();

        for (NeighRow row : byNeigh.values()) {
            Double x = xExtractor.get(row);
            Double y = yExtractor.get(row);
            if (x == null || y == null || x.isNaN() || y.isNaN()) {
                continue;
            }
            xs.add(x);
            ys.add(y);
            neighs.add(row.neigh);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label",      label);
        out.put("samples",    xs.size());
        out.put("neighsUsed", neighs);

        if (xs.size() < 3) {
            out.put("status",          "insufficient_samples");
            out.put("pearson",         null);
            out.put("spearman",        null);
            out.put("kendallTau",      null);
            out.put("pearsonPValue",   null);
            out.put("spearmanPValue",  null);
            out.put("kendallTauPValue", null);
            out.put("permutationTest", null);
            out.put("confidence",      "low");
            out.put("note", "Need at least 3 neighbourhoods with both variables populated");
            return out;
        }

        Double pearson    = pearson(xs, ys);
        Double spearman   = spearman(xs, ys);
        Double kendallTau = kendallTauB(xs, ys);

        Double pearsonP  = correlationPValue(pearson,    xs.size());
        Double spearmanP = correlationPValue(spearman,   xs.size());
        Double kendallP  = kendallTauPValue(kendallTau,  xs.size());
        Map<String, Object> permutation = permutationTest(xs, ys);

        out.put("status",           pearson == null ? "undefined" : "ok");
        out.put("pearson",          pearson);
        out.put("spearman",         spearman);
        out.put("kendallTau",       kendallTau);
        out.put("pearsonPValue",    pearsonP);
        out.put("spearmanPValue",   spearmanP);
        out.put("kendallTauPValue", kendallP);
        out.put("permutationTest",  permutation);

        Double bestPValue = smallestNonNull(pearsonP, spearmanP, kendallP);
        out.put("confidence", confidenceLabel(xs.size(), spearman, kendallTau, bestPValue));
        if (pearson == null) {
            out.put("note", "One variable has zero variance across sampled neighbourhoods");
        }
        return out;
    }

    private Double spearman(List<Double> xs, List<Double> ys) {
        if (xs == null || ys == null || xs.size() != ys.size() || xs.size() < 2) return null;
        return pearson(ranks(xs), ranks(ys));
    }

    private List<Double> ranks(List<Double> values) {
        int n = values.size();
        List<Double> rank = new ArrayList<>(Collections.nCopies(n, 0.0));
        List<Integer> indices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) indices.add(i);
        indices.sort((a, b) -> Double.compare(values.get(a), values.get(b)));

        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(values.get(indices.get(j)), values.get(indices.get(j + 1))) == 0) {
                j++;
            }
            double avgRank = ((i + 1) + (j + 1)) / 2.0;
            for (int k = i; k <= j; k++) rank.set(indices.get(k), avgRank);
            i = j + 1;
        }
        return rank;
    }

    private Double kendallTauB(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n != ys.size() || n < 2) return null;

        long concordant = 0, discordant = 0, tieX = 0, tieY = 0;
        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                int sx = Double.compare(xs.get(i) - xs.get(j), 0d);
                int sy = Double.compare(ys.get(i) - ys.get(j), 0d);
                if (sx == 0 && sy == 0) continue;
                if (sx == 0) { tieX++; continue; }
                if (sy == 0) { tieY++; continue; }
                if (sx == sy) concordant++; else discordant++;
            }
        }
        double denominator = Math.sqrt(
            (concordant + discordant + tieX) * (double) (concordant + discordant + tieY));
        return denominator == 0d ? null : (concordant - discordant) / denominator;
    }

    private Double pearson(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n != ys.size() || n < 2) return null;
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += xs.get(i); sumY += ys.get(i); }
        double meanX = sumX / n, meanY = sumY / n;
        double num = 0, denomX = 0, denomY = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX, dy = ys.get(i) - meanY;
            num    += dx * dy;
            denomX += dx * dx;
            denomY += dy * dy;
        }
        if (denomX == 0d || denomY == 0d) return null;
        return num / Math.sqrt(denomX * denomY);
    }

    private String confidenceLabel(int samples, Double spearman, Double kendallTau, Double pValue) {
        if (samples < 4) return "low";
        double signal = 0; int count = 0;
        if (spearman   != null) { signal += Math.abs(spearman);   count++; }
        if (kendallTau != null) { signal += Math.abs(kendallTau); count++; }
        double avg = count == 0 ? 0 : signal / count;
        boolean sig = pValue != null && pValue < 0.05;
        if (samples >= 8 && avg >= 0.35 && sig)  return "high";
        if (samples >= 8 && avg >= 0.35)          return "medium";
        if (samples >= 5 && avg >= 0.2  && sig)  return "medium";
        if (samples >= 5 && avg >= 0.2)           return "low-medium";
        return "low";
    }

    // ── Analytical p-values ──────────────────────────────────────────────────

    private Double correlationPValue(Double r, int n) {
        if (r == null || n < 3) return null;
        if (Math.abs(r) >= 1.0) return 0.0;
        double t = r * Math.sqrt((n - 2.0) / (1.0 - r * r));
        return tDistTwoTailP(t, n - 2);
    }

    private Double kendallTauPValue(Double tau, int n) {
        if (tau == null || n < 3) return null;
        double variance = (2.0 * (2.0 * n + 5.0)) / (9.0 * n * (n - 1.0));
        double z = tau / Math.sqrt(variance);
        return 2.0 * normalCdfUpperTail(Math.abs(z));
    }

    private Double tDistTwoTailP(double t, int df) {
        if (df < 1) return null;
        double x = df / (df + t * t);
        double p = regularizedIncompleteBeta(df / 2.0, 0.5, x);
        return Math.max(0.0, Math.min(1.0, p));
    }

    private double normalCdfUpperTail(double z) {
        if (z < 0) return 1.0 - normalCdfUpperTail(-z);
        double t   = 1.0 / (1.0 + 0.2316419 * z);
        double pdf = Math.exp(-z * z / 2.0) / Math.sqrt(2.0 * Math.PI);
        double poly = t * (0.319381530
            + t * (-0.356563782
            + t * (1.781477937
            + t * (-1.821255978
            + t * 1.330274429))));
        return Math.max(0.0, Math.min(1.0, pdf * poly));
    }

    private double regularizedIncompleteBeta(double a, double b, double x) {
        if (x < 0.0 || x > 1.0) return Double.NaN;
        if (x == 0.0) return 0.0;
        if (x == 1.0) return 1.0;
        double lnBeta = lnGamma(a) + lnGamma(b) - lnGamma(a + b);
        double front  = Math.exp(Math.log(x) * a + Math.log(1.0 - x) * b - lnBeta);
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * betaContinuedFraction(a, b, x) / a;
        } else {
            return 1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b;
        }
    }

    private double betaContinuedFraction(double a, double b, double x) {
        int maxIter = 200; double eps = 1e-14;
        double qab = a + b, qap = a + 1.0, qam = a - 1.0;
        double c = 1.0, d = 1.0 - qab * x / qap;
        if (Math.abs(d) < 1e-30) d = 1e-30;
        d = 1.0 / d; double h = d;
        for (int m = 1; m <= maxIter; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d; if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1.0 + aa / c; if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d; h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d; if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1.0 + aa / c; if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d; double del = d * c; h *= del;
            if (Math.abs(del - 1.0) < eps) break;
        }
        return h;
    }

    private double lnGamma(double x) {
        double[] coef = {
            76.18009172947146, -86.50532032941677,
            24.01409824083091, -1.231739572450155,
            0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x, tmp = x + 5.5;
        tmp -= (x - 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : coef) { y += 1.0; ser += c / y; }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    // ── Permutation test ─────────────────────────────────────────────────────

    private Map<String, Object> permutationTest(List<Double> xs, List<Double> ys) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("permutations", PERMUTATION_COUNT);

        if (xs.size() < 3) {
            result.put("status",           "insufficient_samples");
            result.put("observedPearson",  null);
            result.put("pValue",           null);
            result.put("significant_005",  false);
            result.put("significant_001",  false);
            return result;
        }

        Double observedR = pearson(xs, ys);
        if (observedR == null) {
            result.put("status",           "undefined_zero_variance");
            result.put("observedPearson",  null);
            result.put("pValue",           null);
            result.put("significant_005",  false);
            result.put("significant_001",  false);
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
        result.put("status",          "ok");
        result.put("observedPearson", observedR);
        result.put("pValue",          Math.round(pValue * 10000.0) / 10000.0);
        result.put("significant_005", pValue < 0.05);
        result.put("significant_001", pValue < 0.01);
        return result;
    }

    private Double smallestNonNull(Double... values) {
        Double min = null;
        for (Double v : values) {
            if (v != null && (min == null || v < min)) min = v;
        }
        return min;
    }

    // ── FHIR resource helpers ────────────────────────────────────────────────

    private boolean isBlockLayerMeasure(MeasureReport report) {
        if (report == null) return false;
        if (BLOCK_MEASURE.equals(report.getMeasure())) return true;
        for (Identifier identifier : report.getIdentifier()) {
            if (identifier != null && BLOCK_IDENTIFIER_SYSTEM.equals(identifier.getSystem())) return true;
        }
        return false;
    }

    private boolean isNeighborhoodLayerMeasure(MeasureReport report) {
        if (report == null) return false;
        if (NEIGHBORHOOD_MEASURE.equals(report.getMeasure())) return true;
        for (Identifier identifier : report.getIdentifier()) {
            if (identifier != null && NEIGHBORHOOD_IDENTIFIER_SYSTEM.equals(identifier.getSystem())) return true;
        }
        return false;
    }

    private boolean isBlockNews2Average(Observation obs) {
        if (obs == null || !obs.hasIdentifier()) return false;
        for (Identifier id : obs.getIdentifier()) {
            if (NEWS2_IDENTIFIER_SYSTEM.equals(id.getSystem())
                && id.getValue() != null
                && id.getValue().endsWith(BLOCK_AVERAGE_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveCondition(Condition condition) {
        if (condition == null || !condition.hasClinicalStatus()) return false;
        return condition.getClinicalStatus().getCoding().stream()
            .anyMatch(c -> c != null && c.getCode() != null && "active".equalsIgnoreCase(c.getCode()));
    }

    private BigDecimal extractStratifierValue(MeasureReport report, String stratifierText) {
        if (report == null || !report.hasGroup()) return null;
        for (MeasureReport.MeasureReportGroupComponent group : report.getGroup()) {
            for (MeasureReport.MeasureReportGroupStratifierComponent stratifier : group.getStratifier()) {
                String codeText = null;
                if (!stratifier.getCode().isEmpty() && stratifier.getCodeFirstRep().hasText()) {
                    codeText = stratifier.getCodeFirstRep().getText();
                }
                if (codeText == null || !codeText.equalsIgnoreCase(stratifierText)) continue;
                if (stratifier.hasStratum()
                    && stratifier.getStratumFirstRep().hasMeasureScore()
                    && stratifier.getStratumFirstRep().getMeasureScore().getValue() != null) {
                    return stratifier.getStratumFirstRep().getMeasureScore().getValue();
                }
            }
        }
        return null;
    }

    private Double extractObservationNumericValue(Observation observation) {
        if (observation == null) return null;
        if (observation.hasValueQuantity() && observation.getValueQuantity().getValue() != null) {
            return observation.getValueQuantity().getValue().doubleValue();
        }
        if (observation.getValue() instanceof IntegerType it && it.getValue() != null) {
            return it.getValue().doubleValue();
        }
        if (observation.getValue() instanceof DecimalType dt && dt.getValue() != null) {
            return dt.getValue().doubleValue();
        }
        return null;
    }

    private int extractSampleCount(Observation observation) {
        if (observation == null) return 1;
        Extension sampleExt = observation.getExtensionByUrl("http://observation-sample-count");
        if (sampleExt != null && sampleExt.getValue() instanceof IntegerType it && it.getValue() != null) {
            int value = it.getValue();
            return value > 0 ? value : 1;
        }
        return 1;
    }

    // ── Extension extraction ─────────────────────────────────────────────────

    private String extractCity(MeasureReport report) {
        return extractFromLocation(report.getExtensionByUrl(LOCATION_EXTENSION_URL), CITY_URL);
    }

    private String extractCity(Observation observation) {
        return extractFromLocation(observation.getExtensionByUrl(LOCATION_EXTENSION_URL), CITY_URL);
    }

    private String extractCity(Patient patient) {
        return extractFromLocation(patient.getExtensionByUrl(LOCATION_EXTENSION_URL), CITY_URL);
    }

    private String extractNeighborhood(MeasureReport report) {
        return extractFromLocation(report.getExtensionByUrl(LOCATION_EXTENSION_URL), NEIGHBORHOOD_URL);
    }

    private String extractNeighborhood(Observation observation) {
        return extractFromLocation(observation.getExtensionByUrl(LOCATION_EXTENSION_URL), NEIGHBORHOOD_URL);
    }

    private String extractNeighborhood(Patient patient) {
        return extractFromLocation(patient.getExtensionByUrl(LOCATION_EXTENSION_URL), NEIGHBORHOOD_URL);
    }

    private String extractNeighborhood(Condition condition) {
        return extractFromLocation(condition.getExtensionByUrl(LOCATION_EXTENSION_URL), NEIGHBORHOOD_URL);
    }

    private String extractFromLocation(Extension location, String fieldUrl) {
        if (location == null) return null;
        for (Extension nested : location.getExtension()) {
            if (fieldUrl.equals(nested.getUrl()) && nested.getValue() != null) {
                return normalize(nested.getValue().primitiveValue());
            }
        }
        return null;
    }

    private List<IBaseResource> getAllResources(IBundleProvider provider) {
        if (provider == null) return List.of();
        Integer size = provider.size();
        if (size == null || size < 0) return provider.getAllResources();
        List<IBaseResource> all = new ArrayList<>(size);
        int pageSize = 500;
        for (int from = 0; from < size; from += pageSize) {
            int to = Math.min(from + pageSize, size);
            List<IBaseResource> page = provider.getResources(from, to);
            if (page.isEmpty()) break;
            all.addAll(page);
        }
        return all;
    }

    private String normalize(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveConfiguredCity() {
        String city = normalize(configuredCity);
        if (city == null) {
            throw new IllegalStateException("Required property 'location.city' is missing or blank");
        }
        return city;
    }

    private Double asDouble(int value) {
        return (double) value;
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    private interface ValueExtractor {
        Double get(NeighRow row);
    }

    private static class WeightedAccumulator {
        double weightedSum;
        int totalWeight;

        void add(double value, int weight) {
            int w = weight > 0 ? weight : 1;
            weightedSum  += value * w;
            totalWeight  += w;
        }

        Double average() {
            return totalWeight <= 0 ? null : weightedSum / totalWeight;
        }
    }

    private static class NeighRow {
        final String neigh;
        Double avgIncome;
        Double avgCare;
        Double avgAge;
        Double avgNews2;
        int    conditionCount;
        Double heartRate;
        Double systolicBp;
        Double diastolicBp;

        NeighRow(String neigh) {
            this.neigh = neigh;
        }
    }
}
