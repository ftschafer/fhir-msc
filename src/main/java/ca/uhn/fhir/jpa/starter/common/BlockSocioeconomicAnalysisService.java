package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Enumerations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

@Service
public class BlockSocioeconomicAnalysisService {

    private static final String HEART_RATE_CODE = "8867-4";
    private static final String SYSTOLIC_BP_CODE = "8480-6";
    private final DaoRegistry daoRegistry;

    @Value("${hapi.fhir.analysis.socioeconomic.kmeans.max-k:6}")
    private int configuredMaxK;

    @Value("${hapi.fhir.analysis.socioeconomic.kmeans.runs:8}")
    private int configuredRuns;

    @Value("${hapi.fhir.analysis.socioeconomic.kmeans.min-silhouette:0.2}")
    private double minSilhouette;

    @Value("${hapi.fhir.analysis.socioeconomic.kmeans.min-cluster-size:2}")
    private int minClusterSize;

    @Value("${hapi.fhir.analysis.socioeconomic.kmeans.max-singleton-ratio:0.20}")
    private double maxSingletonRatio;

    public BlockSocioeconomicAnalysisService(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    public AnalysisOutput analyze(List<Patient> patients, String filterBlock, String defaultBlock) {
        if (patients == null || patients.isEmpty()) {
            return AnalysisOutput.empty();
        }

        String configuredBlock = (defaultBlock != null && !defaultBlock.isBlank()) ? defaultBlock : "Unknown";
        List<FeatureRow> rows = new ArrayList<>();
        LocalDate now = LocalDate.now();
        Map<String, Integer> activeConditionsByPatient = loadActiveConditionCountsByPatientId();
        Map<String, VitalSigns> vitalSignsByPatient = loadLatestVitalSignsByPatientId();

        for (Patient patient : patients) {
            String patientId = patient.getIdElement().getIdPart();
            if (patientId == null || patientId.isBlank()) {
                continue;
            }

            String block = configuredBlock;

            VitalSigns vitalSigns = vitalSignsByPatient.get(patientId);
            if (vitalSigns == null) {
                continue;
            }

            int heartRate = vitalSigns.heartRate();
            int systolicPressure = vitalSigns.systolicPressure();
            int age = extractAge(patient, now, patientId);
            int activeConditions = activeConditionsByPatient.getOrDefault(patientId, 0);
            double sexEncoded = extractSexEncoded(patient);
            int maleFlag = extractMaleFlag(patient);
            int femaleFlag = extractFemaleFlag(patient);

            rows.add(new FeatureRow(patientId, block, heartRate, systolicPressure, age, sexEncoded, activeConditions, maleFlag, femaleFlag));
        }

        if (rows.isEmpty()) {
            return AnalysisOutput.empty();
        }

        List<double[]> standardized = standardizeFeatures(rows);
        KMeansSelectionResult selected = selectBestModel(standardized);
        List<double[]> pcaProjection = projectPca2D(standardized);
        List<ClusterProfile> profiles = toProfiles(rows, selected.assignment, selected.k, pcaProjection);
        List<ClusterPoint> points = toClusterPoints(rows, selected.assignment, selected.k, pcaProjection);

        AnalysisQuality quality = new AnalysisQuality(
                selected.k,
                round(selected.silhouette),
                selected.runs,
                round(selected.wcss),
                round(minSilhouette),
                selected.silhouette < minSilhouette || selected.constraintViolated,
                selected.minClusterObserved,
                selected.singletonClusters,
                round(selected.adjustedSilhouette)
        );

        return new AnalysisOutput(rows.size(), selected.k, profiles, points, quality);
    }

    private KMeansSelectionResult selectBestModel(List<double[]> standardizedFeatures) {
        int n = standardizedFeatures.size();
        if (n < 2) {
            return new KMeansSelectionResult(1, new int[]{0}, 0.0, 0.0, 0.0, 1, 1, 0, false);
        }

        int maxK = Math.max(2, Math.min(configuredMaxK, n - 1));
        int runs = Math.max(1, configuredRuns);
        int effectiveMinClusterSize = Math.max(1, minClusterSize);

        KMeansSelectionResult best = null;
        KMeansSelectionResult fallbackBest = null;

        for (int k = 2; k <= maxK; k++) {
            for (int run = 0; run < runs; run++) {
                KMeansResult result = runKMeans(standardizedFeatures, k, 12345L + (31L * k) + run);
                double silhouette = silhouette(result.assignment, standardizedFeatures, k);
                ClusterShape shape = evaluateClusterShape(result.assignment, k);

                boolean singletonHeavy = ((double) shape.singletonClusters / (double) k) > maxSingletonRatio;
                boolean hasTinyCluster = shape.minClusterObserved < effectiveMinClusterSize;
                boolean violatesConstraint = singletonHeavy || hasTinyCluster;
                double adjustedSilhouette = silhouette - (violatesConstraint ? 0.35 : 0.0);

                KMeansSelectionResult candidate = new KMeansSelectionResult(
                        k,
                        result.assignment,
                        silhouette,
                        adjustedSilhouette,
                        result.wcss,
                        runs,
                        shape.minClusterObserved,
                        shape.singletonClusters,
                        violatesConstraint
                );

                if (fallbackBest == null
                        || candidate.adjustedSilhouette > fallbackBest.adjustedSilhouette
                        || (candidate.adjustedSilhouette == fallbackBest.adjustedSilhouette && candidate.wcss < fallbackBest.wcss)) {
                    fallbackBest = candidate;
                }

                if (!candidate.constraintViolated && (best == null
                        || candidate.adjustedSilhouette > best.adjustedSilhouette
                        || (candidate.adjustedSilhouette == best.adjustedSilhouette && candidate.wcss < best.wcss))) {
                    best = candidate;
                }
            }
        }

        if (best != null) {
            return best;
        }
        if (fallbackBest != null) {
            return fallbackBest;
        }
        return new KMeansSelectionResult(1, new int[n], 0.0, 0.0, 0.0, 1, n, 0, false);
    }

    private ClusterShape evaluateClusterShape(int[] assignment, int k) {
        int[] counts = new int[k];
        for (int cluster : assignment) {
            if (cluster >= 0 && cluster < k) {
                counts[cluster]++;
            }
        }

        int minObserved = Integer.MAX_VALUE;
        int singletonClusters = 0;
        for (int count : counts) {
            minObserved = Math.min(minObserved, count);
            if (count == 1) {
                singletonClusters++;
            }
        }

        if (minObserved == Integer.MAX_VALUE) {
            minObserved = 0;
        }

        return new ClusterShape(minObserved, singletonClusters);
    }

    private KMeansResult runKMeans(List<double[]> points, int k, long seed) {
        Random random = new Random(seed);
        List<double[]> centroids = initializeCentroids(points, k, random);

        int[] assignment = new int[points.size()];
        for (int i = 0; i < assignment.length; i++) {
            assignment[i] = -1;
        }

        for (int iter = 0; iter < 40; iter++) {
            boolean changed = false;

            for (int i = 0; i < points.size(); i++) {
                int closest = closestCentroid(points.get(i), centroids);
                if (assignment[i] != closest) {
                    assignment[i] = closest;
                    changed = true;
                }
            }

            recenter(assignment, points, centroids, random);
            if (!changed) {
                break;
            }
        }

        double wcss = 0.0;
        for (int i = 0; i < points.size(); i++) {
            int cluster = Math.max(0, assignment[i]);
            wcss += dist(points.get(i), centroids.get(cluster));
        }

        return new KMeansResult(assignment, wcss);
    }

    private List<double[]> initializeCentroids(List<double[]> points, int k, Random random) {
        List<double[]> centroids = new ArrayList<>();
        boolean[] used = new boolean[points.size()];

        while (centroids.size() < k) {
            int pick = random.nextInt(points.size());
            if (used[pick]) {
                continue;
            }
            used[pick] = true;
            centroids.add(points.get(pick).clone());
        }

        return centroids;
    }

    private int closestCentroid(double[] point, List<double[]> centroids) {
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < centroids.size(); i++) {
            double d = dist(point, centroids.get(i));
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private void recenter(int[] assignment, List<double[]> points, List<double[]> centroids, Random random) {
        int dim = points.get(0).length;
        double[][] sums = new double[centroids.size()][dim];
        int[] counts = new int[centroids.size()];

        for (int i = 0; i < points.size(); i++) {
            int c = assignment[i];
            if (c < 0 || c >= centroids.size()) continue;
            counts[c]++;
            for (int d = 0; d < dim; d++) {
                sums[c][d] += points.get(i)[d];
            }
        }

        for (int c = 0; c < centroids.size(); c++) {
            if (counts[c] == 0) {
                centroids.set(c, points.get(random.nextInt(points.size())).clone());
                continue;
            }
            for (int d = 0; d < dim; d++) {
                centroids.get(c)[d] = sums[c][d] / counts[c];
            }
        }
    }

    private double silhouette(int[] assignment, List<double[]> points, int k) {
        if (points.size() < 3) {
            return 0.0;
        }

        List<List<Integer>> members = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            members.add(new ArrayList<>());
        }
        for (int i = 0; i < assignment.length; i++) {
            int c = assignment[i];
            if (c >= 0 && c < k) {
                members.get(c).add(i);
            }
        }

        double total = 0.0;
        int counted = 0;

        for (int i = 0; i < points.size(); i++) {
            int ci = assignment[i];
            if (ci < 0 || ci >= k) {
                continue;
            }

            double a = meanDistance(i, members.get(ci), points, true);
            double b = Double.MAX_VALUE;

            for (int other = 0; other < k; other++) {
                if (other == ci || members.get(other).isEmpty()) {
                    continue;
                }
                double dist = meanDistance(i, members.get(other), points, false);
                if (dist < b) {
                    b = dist;
                }
            }

            double s;
            if (b == Double.MAX_VALUE) {
                s = 0.0;
            } else if (a == 0.0 && b == 0.0) {
                s = 0.0;
            } else {
                s = (b - a) / Math.max(a, b);
            }

            total += s;
            counted++;
        }

        return counted == 0 ? 0.0 : total / counted;
    }

    private double meanDistance(int pointIndex, List<Integer> group, List<double[]> points, boolean skipSelf) {
        if (group.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        int count = 0;
        for (Integer idx : group) {
            if (skipSelf && idx == pointIndex) {
                continue;
            }
            sum += Math.sqrt(dist(points.get(pointIndex), points.get(idx)));
            count++;
        }

        return count == 0 ? 0.0 : sum / count;
    }

    private double dist(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }

    private List<double[]> standardizeFeatures(List<FeatureRow> rows) {
        int featureCount = 5;
        double[] means = new double[featureCount];
        double[] stdDevs = new double[featureCount];

        List<double[]> matrix = new ArrayList<>(rows.size());
        for (FeatureRow row : rows) {
            double[] vector = new double[]{
                    row.heartRate,
                    row.systolicPressure,
                    row.age,
                    row.sexEncoded,
                    row.activeConditions
            };
            matrix.add(vector);
            for (int i = 0; i < featureCount; i++) {
                means[i] += vector[i];
            }
        }

        for (int i = 0; i < featureCount; i++) {
            means[i] /= rows.size();
        }

        for (double[] vector : matrix) {
            for (int i = 0; i < featureCount; i++) {
                double diff = vector[i] - means[i];
                stdDevs[i] += diff * diff;
            }
        }

        for (int i = 0; i < featureCount; i++) {
            stdDevs[i] = Math.sqrt(stdDevs[i] / Math.max(1, rows.size() - 1));
        }

        List<double[]> standardized = new ArrayList<>(rows.size());
        for (double[] vector : matrix) {
            double[] z = new double[featureCount];
            for (int i = 0; i < featureCount; i++) {
                z[i] = stdDevs[i] > 0.0 ? (vector[i] - means[i]) / stdDevs[i] : 0.0;
            }
            standardized.add(z);
        }
        return standardized;
    }

    private List<double[]> projectPca2D(List<double[]> standardizedFeatures) {
        if (standardizedFeatures.isEmpty()) {
            return List.of();
        }

        int dimension = standardizedFeatures.get(0).length;
        double[][] covariance = covarianceMatrix(standardizedFeatures, dimension);

        double[] pc1Vector = dominantEigenvector(covariance, 120);
        double eigenvalue1 = eigenvalue(covariance, pc1Vector);

        double[][] deflated = deflate(covariance, pc1Vector, eigenvalue1);
        double[] pc2Vector = dominantEigenvector(deflated, 120);

        List<double[]> projection = new ArrayList<>(standardizedFeatures.size());
        for (double[] row : standardizedFeatures) {
            projection.add(new double[]{dot(row, pc1Vector), dot(row, pc2Vector)});
        }
        return projection;
    }

    private double[][] covarianceMatrix(List<double[]> points, int dimension) {
        double[][] cov = new double[dimension][dimension];
        if (points.size() < 2) {
            return cov;
        }

        for (double[] row : points) {
            for (int i = 0; i < dimension; i++) {
                for (int j = 0; j < dimension; j++) {
                    cov[i][j] += row[i] * row[j];
                }
            }
        }

        double denom = points.size() - 1.0;
        for (int i = 0; i < dimension; i++) {
            for (int j = 0; j < dimension; j++) {
                cov[i][j] /= denom;
            }
        }
        return cov;
    }

    private double[] dominantEigenvector(double[][] matrix, int iterations) {
        int n = matrix.length;
        double[] vector = new double[n];
        Arrays.fill(vector, 1.0 / Math.sqrt(Math.max(1, n)));

        for (int iter = 0; iter < iterations; iter++) {
            double[] next = multiply(matrix, vector);
            double norm = norm(next);
            if (norm == 0.0) {
                return vector;
            }
            for (int i = 0; i < n; i++) {
                next[i] /= norm;
            }
            vector = next;
        }
        return vector;
    }

    private double eigenvalue(double[][] matrix, double[] vector) {
        double[] mv = multiply(matrix, vector);
        return dot(vector, mv);
    }

    private double[][] deflate(double[][] matrix, double[] eigenvector, double eigenvalue) {
        int n = matrix.length;
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                out[i][j] = matrix[i][j] - eigenvalue * eigenvector[i] * eigenvector[j];
            }
        }
        return out;
    }

    private double[] multiply(double[][] matrix, double[] vector) {
        int n = matrix.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int j = 0; j < n; j++) {
                sum += matrix[i][j] * vector[j];
            }
            out[i] = sum;
        }
        return out;
    }

    private double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private double norm(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private int extractAge(Patient patient, LocalDate now, String patientId) {
        DateType birthDateElement = patient.getBirthDateElement();
        if (birthDateElement != null && birthDateElement.getValue() != null) {
            LocalDate birthDate = birthDateElement.getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            int age = now.getYear() - birthDate.getYear();
            if (birthDate.plusYears(age).isAfter(now)) {
                age--;
            }
            if (age >= 0 && age <= 110) {
                return age;
            }
        }

        int hash = Math.abs(Objects.hash(patientId));
        return 18 + (hash % 73);
    }

    private Map<String, Integer> loadActiveConditionCountsByPatientId() {
        Map<String, Integer> counts = new HashMap<>();
        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);

        SearchParameterMap search = new SearchParameterMap();
        search.add("clinical-status", new TokenParam("http://terminology.hl7.org/CodeSystem/condition-clinical", "active"));
        search.setLoadSynchronous(true);

        IBundleProvider results = conditionDao.search(search);
        for (IBaseResource resource : results.getAllResources()) {
            if (!(resource instanceof Condition condition) || condition.getSubject() == null) {
                continue;
            }

            String patientId = extractPatientIdFromReference(condition.getSubject().getReference());
            if (patientId == null || patientId.isBlank()) {
                continue;
            }

            counts.merge(patientId, 1, Integer::sum);
        }

        return counts;
    }

    private String extractPatientIdFromReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }

        String trimmed = reference.trim();
        if (trimmed.startsWith("Patient/")) {
            return trimmed.substring("Patient/".length());
        }

        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            return trimmed.substring(slash + 1);
        }

        return trimmed;
    }

    private int extractMaleFlag(Patient patient) {
        return patient != null && patient.getGender() == Enumerations.AdministrativeGender.MALE ? 1 : 0;
    }

    private int extractFemaleFlag(Patient patient) {
        return patient != null && patient.getGender() == Enumerations.AdministrativeGender.FEMALE ? 1 : 0;
    }

    private double extractSexEncoded(Patient patient) {
        if (patient == null || patient.getGender() == null) {
            return 0.5;
        }
        if (patient.getGender() == Enumerations.AdministrativeGender.MALE) {
            return 1.0;
        }
        if (patient.getGender() == Enumerations.AdministrativeGender.FEMALE) {
            return 0.0;
        }
        return 0.5;
    }

    private Map<String, VitalSigns> loadLatestVitalSignsByPatientId() {
        Map<String, Observation> latestHeartRateByPatient = new HashMap<>();
        Map<String, Observation> latestSystolicByPatient = new HashMap<>();

        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap search = new SearchParameterMap();
        search.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs"));
        search.setLoadSynchronous(true);

        IBundleProvider results = observationDao.search(search);
        for (IBaseResource resource : results.getAllResources()) {
            if (!(resource instanceof Observation observation) || observation.getSubject() == null || observation.getValueQuantity() == null) {
                continue;
            }

            String patientId = extractPatientIdFromReference(observation.getSubject().getReference());
            if (patientId == null || patientId.isBlank()) {
                continue;
            }

            String code = observation.getCode() != null && observation.getCode().hasCoding()
                    ? observation.getCode().getCodingFirstRep().getCode()
                    : null;
            if (code == null) {
                continue;
            }

            if (HEART_RATE_CODE.equals(code)) {
                Observation current = latestHeartRateByPatient.get(patientId);
                if (current == null || isObservationNewer(observation, current)) {
                    latestHeartRateByPatient.put(patientId, observation);
                }
            } else if (SYSTOLIC_BP_CODE.equals(code)) {
                Observation current = latestSystolicByPatient.get(patientId);
                if (current == null || isObservationNewer(observation, current)) {
                    latestSystolicByPatient.put(patientId, observation);
                }
            }
        }

        Map<String, VitalSigns> out = new HashMap<>();
        for (String patientId : latestHeartRateByPatient.keySet()) {
            Observation hrObs = latestHeartRateByPatient.get(patientId);
            Observation sbpObs = latestSystolicByPatient.get(patientId);
            if (hrObs == null || sbpObs == null) {
                continue;
            }

            int heartRate = hrObs.getValueQuantity().getValue() != null ? hrObs.getValueQuantity().getValue().intValue() : 0;
            int systolicPressure = sbpObs.getValueQuantity().getValue() != null ? sbpObs.getValueQuantity().getValue().intValue() : 0;
            if (heartRate <= 0 || systolicPressure <= 0) {
                continue;
            }

            out.put(patientId, new VitalSigns(heartRate, systolicPressure));
        }

        return out;
    }

    private boolean isObservationNewer(Observation candidate, Observation current) {
        java.util.Date candidateUpdated = candidate.getMeta() != null ? candidate.getMeta().getLastUpdated() : null;
        java.util.Date currentUpdated = current.getMeta() != null ? current.getMeta().getLastUpdated() : null;

        if (candidateUpdated != null && currentUpdated != null) {
            return candidateUpdated.after(currentUpdated);
        }
        if (candidateUpdated != null) {
            return true;
        }
        if (currentUpdated != null) {
            return false;
        }

        java.util.Date candidateEffective = candidate.getEffectiveDateTimeType() != null ? candidate.getEffectiveDateTimeType().getValue() : null;
        java.util.Date currentEffective = current.getEffectiveDateTimeType() != null ? current.getEffectiveDateTimeType().getValue() : null;
        if (candidateEffective != null && currentEffective != null) {
            return candidateEffective.after(currentEffective);
        }
        return candidateEffective != null;
    }

    private record FeatureRow(
            String patientId,
            String block,
            int heartRate,
            int systolicPressure,
            int age,
            double sexEncoded,
            int activeConditions,
            int maleFlag,
            int femaleFlag
    ) {
    }

    private List<ClusterProfile> toProfiles(List<FeatureRow> rows, int[] assignment, int k, List<double[]> pcaProjection) {
        List<ClusterAccumulator> acc = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            acc.add(new ClusterAccumulator(i));
        }

        for (int i = 0; i < rows.size(); i++) {
            int cluster = assignment[i];
            if (cluster < 0 || cluster >= k) {
                continue;
            }
            double[] pc = i < pcaProjection.size() ? pcaProjection.get(i) : new double[]{0.0, 0.0};
            acc.get(cluster).accept(rows.get(i), pc[0], pc[1]);
        }

        List<ClusterProfile> profiles = new ArrayList<>();
        for (ClusterAccumulator c : acc) {
            if (c.size == 0) continue;
            profiles.add(c.toProfile());
        }

        profiles.sort((a, b) -> Double.compare(b.avgHeartRate, a.avgHeartRate));
        return profiles;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

        public record AnalysisOutput(
            int sampleSize,
            int clusterCount,
            List<ClusterProfile> clusterProfiles,
            List<ClusterPoint> clusterPoints,
            AnalysisQuality quality
        ) {
        public static AnalysisOutput empty() {
            return new AnalysisOutput(0, 0, List.of(), List.of(), new AnalysisQuality(0, 0.0, 0, 0.0, 0.2, true, 0, 0, 0.0));
        }
    }

    public record AnalysisQuality(
            int selectedK,
            double silhouetteScore,
            int runCount,
            double withinClusterSse,
            double minSilhouette,
            boolean lowConfidence,
            int minClusterSizeObserved,
            int singletonClusterCount,
            double adjustedSilhouetteScore
    ) {
    }

    public record ClusterProfile(
            int clusterId,
            int patientCount,
            double avgHeartRate,
            double avgSystolicPressure,
            double avgAge,
            double avgConditions,
            double pctMale,
            double pctFemale,
            double pc1,
            double pc2,
            String profileLabel
    ) {
    }

    public record ClusterPoint(
            String patientId,
            int clusterId,
            int heartRate,
            int systolicPressure,
            int age,
            int activeConditions,
            int maleFlag,
                int femaleFlag,
                double pc1,
                double pc2
    ) {
    }

            private List<ClusterPoint> toClusterPoints(List<FeatureRow> rows, int[] assignment, int k, List<double[]> pcaProjection) {
        List<ClusterPoint> points = new ArrayList<>();
        int size = Math.min(rows.size(), assignment.length);
        for (int i = 0; i < size; i++) {
            int clusterId = assignment[i];
            if (clusterId < 0 || clusterId >= k) {
                continue;
            }
            FeatureRow row = rows.get(i);
                double[] pc = i < pcaProjection.size() ? pcaProjection.get(i) : new double[]{0.0, 0.0};
            points.add(new ClusterPoint(
                    row.patientId,
                    clusterId,
                    row.heartRate,
                    row.systolicPressure,
                    row.age,
                    row.activeConditions,
                    row.maleFlag,
                    row.femaleFlag,
                    round(pc[0]),
                    round(pc[1])
            ));
        }
        return points;
    }

    private static class ClusterAccumulator {
        private final int clusterId;
        private int size;
        private double heartRateSum;
        private double systolicPressureSum;
        private double ageSum;
        private double activeConditionsSum;
        private int maleCount;
        private int femaleCount;
        private double pc1Sum;
        private double pc2Sum;

        private ClusterAccumulator(int clusterId) {
            this.clusterId = clusterId;
        }

        private void accept(FeatureRow row, double pc1, double pc2) {
            size++;
            heartRateSum += row.heartRate;
            systolicPressureSum += row.systolicPressure;
            ageSum += row.age;
            activeConditionsSum += row.activeConditions;
            maleCount += row.maleFlag;
            femaleCount += row.femaleFlag;
            pc1Sum += pc1;
            pc2Sum += pc2;
        }

        private ClusterProfile toProfile() {
            double avgHeartRate = heartRateSum / size;
            double avgSystolicPressure = systolicPressureSum / size;
            double avgAge = ageSum / size;
            double avgConditions = activeConditionsSum / size;
            double pctMale = (maleCount * 100.0) / size;
            double pctFemale = (femaleCount * 100.0) / size;

            String label;
            if (avgHeartRate >= 100.0 || avgSystolicPressure >= 140.0) {
                label = "Hemodynamic high-strain cluster";
            } else if (avgHeartRate >= 85.0 || avgSystolicPressure >= 125.0) {
                label = avgAge >= 65.0
                        ? "Older-population moderate-vitals cluster"
                        : "Moderate-vitals cluster";
            } else if (avgAge >= 65.0) {
                label = "Older-population lower-vitals-strain cluster";
            } else {
                label = "Lower-vitals-strain cluster";
            }

            if (pctMale >= 65.0) {
                label += " (male-majority)";
            } else if (pctFemale >= 65.0) {
                label += " (female-majority)";
            }

            return new ClusterProfile(
                    clusterId,
                    size,
                    round(avgHeartRate),
                    round(avgSystolicPressure),
                    round(avgAge),
                    round(avgConditions),
                    round(pctMale),
                    round(pctFemale),
                        round(pc1Sum / size),
                        round(pc2Sum / size),
                    label
            );
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    private record KMeansResult(int[] assignment, double wcss) {
    }

    private record KMeansSelectionResult(
            int k,
            int[] assignment,
            double silhouette,
            double adjustedSilhouette,
            double wcss,
            int runs,
            int minClusterObserved,
            int singletonClusters,
            boolean constraintViolated
    ) {
    }

    private record ClusterShape(int minClusterObserved, int singletonClusters) {
    }

    private record VitalSigns(int heartRate, int systolicPressure) {
    }
}
