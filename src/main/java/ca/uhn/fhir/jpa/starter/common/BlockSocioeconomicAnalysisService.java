package ca.uhn.fhir.jpa.starter.common;

import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

@Service
public class BlockSocioeconomicAnalysisService {

    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";

    @Value("${hapi.fhir.analysis.socioeconomic.use-dummy-income:true}")
    private boolean useDummyIncome;

    @Value("${hapi.fhir.analysis.socioeconomic.income-extension-url:http://average-income}")
    private String incomeExtensionUrl;

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

    public AnalysisOutput analyze(List<Patient> patients, String filterBlock, String defaultBlock) {
        if (patients == null || patients.isEmpty()) {
            return AnalysisOutput.empty();
        }

        List<FeatureRow> rows = new ArrayList<>();
        LocalDate now = LocalDate.now();

        for (Patient patient : patients) {
            String patientId = patient.getIdElement().getIdPart();
            if (patientId == null || patientId.isBlank()) {
                continue;
            }

            String block = extractBlock(patient);
            if (block == null || block.isBlank()) {
                block = defaultBlock;
            }
            if (block == null || block.isBlank()) {
                block = "Unknown";
            }

            if (filterBlock != null && !filterBlock.isBlank() && !block.equalsIgnoreCase(filterBlock)) {
                continue;
            }

            int news2 = extractNews2(patient);
            int age = extractAge(patient, now, patientId);
            double income = resolveIncome(patient, patientId, block);
            double seasonality = estimateSeasonality(now);

            rows.add(new FeatureRow(patientId, block, news2, age, income, seasonality));
        }

        if (rows.isEmpty()) {
            return AnalysisOutput.empty();
        }

        List<double[]> normalized = normalize(rows);
        KMeansSelectionResult selected = selectBestModel(normalized);
        List<ClusterProfile> profiles = toProfiles(rows, selected.assignment, selected.k);

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

        return new AnalysisOutput(rows.size(), selected.k, profiles, quality);
    }

    private KMeansSelectionResult selectBestModel(List<double[]> normalized) {
        int n = normalized.size();
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
                KMeansResult result = runKMeans(normalized, k, 12345L + (31L * k) + run);
                double silhouette = silhouette(result.assignment, normalized, k);
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

    private List<double[]> normalize(List<FeatureRow> rows) {
        double minNews2 = rows.stream().mapToDouble(FeatureRow::news2).min().orElse(0);
        double maxNews2 = rows.stream().mapToDouble(FeatureRow::news2).max().orElse(1);
        double minAge = rows.stream().mapToDouble(FeatureRow::age).min().orElse(0);
        double maxAge = rows.stream().mapToDouble(FeatureRow::age).max().orElse(1);
        double minIncome = rows.stream().mapToDouble(FeatureRow::income).min().orElse(0);
        double maxIncome = rows.stream().mapToDouble(FeatureRow::income).max().orElse(1);

        List<double[]> out = new ArrayList<>();
        for (FeatureRow row : rows) {
            out.add(new double[]{
                    scale(row.news2, minNews2, maxNews2),
                    scale(row.age, minAge, maxAge),
                    scale(row.income, minIncome, maxIncome),
                    row.seasonality
            });
        }
        return out;
    }

    private double scale(double value, double min, double max) {
        if (max <= min) return 0;
        return (value - min) / (max - min);
    }

    private int extractNews2(Patient patient) {
        Extension ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        if (ext != null && ext.getValue() instanceof IntegerType it && it.getValue() != null) {
            return it.getValue();
        }
        return 0;
    }

    private String extractBlock(Patient patient) {
        Extension locationExtension = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locationExtension == null) {
            return null;
        }
        for (Extension nested : locationExtension.getExtension()) {
            if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() instanceof StringType st) {
                return st.getValue();
            }
        }
        return null;
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

    private double estimateIncome(String patientId, String block) {
        int hash = Math.abs(Objects.hash(patientId, block));
        return 1200 + (hash % 6801);
    }

    private double resolveIncome(Patient patient, String patientId, String block) {
        if (!useDummyIncome) {
            Double fromExtension = extractIncomeFromExtension(patient);
            if (fromExtension != null) {
                return fromExtension;
            }
        }
        return estimateIncome(patientId, block);
    }

    private Double extractIncomeFromExtension(Patient patient) {
        if (incomeExtensionUrl == null || incomeExtensionUrl.isBlank()) {
            return null;
        }

        Extension incomeExt = patient.getExtensionByUrl(incomeExtensionUrl);
        if (incomeExt == null || incomeExt.getValue() == null) {
            return null;
        }

        if (incomeExt.getValue() instanceof DecimalType decimalType && decimalType.getValue() != null) {
            return decimalType.getValue().doubleValue();
        }

        if (incomeExt.getValue() instanceof IntegerType integerType && integerType.getValue() != null) {
            return integerType.getValue().doubleValue();
        }

        if (incomeExt.getValue() instanceof StringType stringType && stringType.getValue() != null) {
            try {
                return Double.parseDouble(stringType.getValue());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private double estimateSeasonality(LocalDate now) {
        int month = now.getMonthValue();
        return Math.sin((2.0 * Math.PI * month) / 12.0);
    }

    private record FeatureRow(String patientId, String block, int news2, int age, double income, double seasonality) {
    }

    private List<ClusterProfile> toProfiles(List<FeatureRow> rows, int[] assignment, int k) {
        List<ClusterAccumulator> acc = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            acc.add(new ClusterAccumulator(i));
        }

        for (int i = 0; i < rows.size(); i++) {
            int cluster = assignment[i];
            if (cluster < 0 || cluster >= k) {
                continue;
            }
            acc.get(cluster).accept(rows.get(i));
        }

        List<ClusterProfile> profiles = new ArrayList<>();
        for (ClusterAccumulator c : acc) {
            if (c.size == 0) continue;
            profiles.add(c.toProfile());
        }

        profiles.sort((a, b) -> Double.compare(b.avgNews2, a.avgNews2));
        return profiles;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record AnalysisOutput(int sampleSize, int clusterCount, List<ClusterProfile> clusterProfiles, AnalysisQuality quality) {
        public static AnalysisOutput empty() {
            return new AnalysisOutput(0, 0, List.of(), new AnalysisQuality(0, 0.0, 0, 0.0, 0.2, true, 0, 0, 0.0));
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
            double avgNews2,
            double avgAge,
            double avgIncome,
            double pctLowRisk,
            double pctMediumRisk,
            double pctHighRisk,
            String profileLabel
    ) {
    }

    private static class ClusterAccumulator {
        private final int clusterId;
        private int size;
        private double news2Sum;
        private double ageSum;
        private double incomeSum;
        private int lowRisk;
        private int mediumRisk;
        private int highRisk;

        private ClusterAccumulator(int clusterId) {
            this.clusterId = clusterId;
        }

        private void accept(FeatureRow row) {
            size++;
            news2Sum += row.news2;
            ageSum += row.age;
            incomeSum += row.income;

            if (row.news2 >= 7) {
                highRisk++;
            } else if (row.news2 >= 5) {
                mediumRisk++;
            } else {
                lowRisk++;
            }
        }

        private ClusterProfile toProfile() {
            double avgNews2 = news2Sum / size;
            double avgAge = ageSum / size;
            double avgIncome = incomeSum / size;

            String label;
            if (avgNews2 >= 7) {
                label = "High-risk clinical cluster";
            } else if (avgAge >= 60 && avgIncome < 3200) {
                label = "Older lower-income vulnerability cluster";
            } else if (avgIncome >= 5000 && avgNews2 < 4) {
                label = "Lower-risk higher-income cluster";
            } else {
                label = "Mixed-risk general population cluster";
            }

            return new ClusterProfile(
                    clusterId,
                    size,
                    round(avgNews2),
                    round(avgAge),
                    round(avgIncome),
                    round((lowRisk * 100.0) / size),
                    round((mediumRisk * 100.0) / size),
                    round((highRisk * 100.0) / size),
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
}
