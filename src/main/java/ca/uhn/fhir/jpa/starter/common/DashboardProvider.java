package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Provides a custom dashboard API endpoint that aggregates clinical data
 * Accessible at: GET [base]/Patient/$dashboard-stats
 */
@Component
public class DashboardProvider implements IResourceProvider {

    private final DaoRegistry daoRegistry;
    private final BlockSocioeconomicAnalysisService socioeconomicAnalysisService;

    @PersistenceContext
    private EntityManager em;
    
    @Value("${hapi.fhir.location.block:}")
    private String currentBlock;
    
    // Cache results for 30 seconds
    private DashboardStats cachedStats;
    private long cacheTimestamp = 0;
    private static final long CACHE_DURATION_MS = 3000;
    private static final int BOOTSTRAP_SAMPLES = 300;

    public DashboardProvider(DaoRegistry daoRegistry, BlockSocioeconomicAnalysisService socioeconomicAnalysisService) {
        this.daoRegistry = daoRegistry;
        this.socioeconomicAnalysisService = socioeconomicAnalysisService;
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
            // Block parameter is intentionally ignored: dashboard always aggregates all
            // patients using the configured block from application.yaml.

            // Check cache
            long now = System.currentTimeMillis();
            if (cachedStats != null && (now - cacheTimestamp) < CACHE_DURATION_MS) {
                writeJsonResponse(response, cachedStats);
                return;
            }
            
            // Calculate fresh stats
            DashboardStats stats = calculateStats();
            
            // Update cache
            cachedStats = stats;
            cacheTimestamp = now;
            
            writeJsonResponse(response, stats);
            
        } catch (Exception e) {
            try {
                response.setStatus(500);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {}
        }
    }

    private DashboardStats calculateStats() {
        DashboardStats stats = new DashboardStats();
        
        // Get all patients
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        IBundleProvider patientResults = patientDao.search(patientSearch);
        List<Patient> patients = patientResults.getAllResources().stream()
            .filter(Patient.class::isInstance)
            .map(Patient.class::cast)
            .collect(Collectors.toList());
        
        // Process patients by location
        Map<String, LocationStats> locationMap = new HashMap<>();
        List<PatientSummary> patientList = new ArrayList<>();
        String configuredBlock = (currentBlock != null && !currentBlock.isBlank()) ? currentBlock : "Unknown";
        
        for (Patient patient : patients) {
            String patientId = patient.getIdElement().getIdPart();
            
            // Get NEWS2 score
            Integer news2Score = null;
            Extension news2Ext = patient.getExtensionByUrl("http://news2-score");
            if (news2Ext != null && news2Ext.getValue() instanceof IntegerType) {
                news2Score = ((IntegerType) news2Ext.getValue()).getValue();
            }
            
            // Always use configured block; patient-level block values are ignored.
            String location = configuredBlock;
            
            // Add to location stats
            LocationStats locStats = locationMap.computeIfAbsent(location, k -> new LocationStats(k));
            locStats.patientCount++;
            if (news2Score != null) {
                locStats.totalNews2 += news2Score;
                locStats.news2Count++;
                locStats.news2Values.add(news2Score);
                if (news2Score >= 7) {
                    locStats.incidentCaseCount++;
                }
                if (news2Score >= 5) {
                    locStats.prevalentCaseCount++;
                }
            }
            
            // Add patient summary
            PatientSummary ps = new PatientSummary();
            ps.patientId = patientId;
            ps.news2Score = news2Score != null ? news2Score : 0;
            ps.location = location;
            ps.riskLevel = getRiskLevel(ps.news2Score);
            patientList.add(ps);
        }
        
        stats.totalPatients = patients.size();
        stats.avgNews2Score = queryProjectedAvgNews2();
        stats.locationStats = new ArrayList<>(locationMap.values());
        finalizeLocationStats(stats.locationStats);
        stats.patients = patientList;
        stats.socioeconomicAnalysis = socioeconomicAnalysisService.analyze(patients, null, configuredBlock);
        
        // Get active conditions
        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
        SearchParameterMap conditionSearch = new SearchParameterMap();
        conditionSearch.add("clinical-status", new TokenParam("http://terminology.hl7.org/CodeSystem/condition-clinical", "active"));
        conditionSearch.setLoadSynchronous(true);
        IBundleProvider conditionResults = conditionDao.search(conditionSearch);
        List<IBaseResource> conditions = conditionResults.getAllResources();
        
        stats.totalConditions = conditions.size();
        stats.conditions = conditions.stream()
            .limit(50) // Limit to recent 50
            .map(res -> {
                Condition c = (Condition) res;
                ConditionSummary cs = new ConditionSummary();
                cs.patientId = c.getSubject() != null ? c.getSubject().getReference() : "Unknown";
                cs.conditionName = c.getCode() != null && c.getCode().hasCoding() 
                    ? c.getCode().getCodingFirstRep().getDisplay() 
                    : c.getCode() != null ? c.getCode().getText() : "Unknown";
                cs.onsetDate = c.getOnsetDateTimeType() != null 
                    ? c.getOnsetDateTimeType().getValueAsString() 
                    : null;
                return cs;
            })
            .collect(Collectors.toList());
        
        // Get vital sign averages
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap avgSearch = new SearchParameterMap();
        avgSearch.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs-average"));
        avgSearch.setLoadSynchronous(true);
        IBundleProvider avgResults = observationDao.search(avgSearch);
        List<IBaseResource> averages = avgResults.getAllResources();
        
        // Deduplicate by code + location, keeping the most recently updated aggregate
        Map<String, Observation> latestByCodeAndLocation = new HashMap<>();
        for (IBaseResource res : averages) {
            Observation obs = (Observation) res;
            String code = obs.getCode() != null && obs.getCode().hasCoding()
                ? obs.getCode().getCodingFirstRep().getCode()
                : "Unknown";
            String location = extractLocation(obs);
            String key = code + "|" + location;

            Observation current = latestByCodeAndLocation.get(key);
            if (current == null || isNewer(obs, current)) {
                latestByCodeAndLocation.put(key, obs);
            }
        }

        stats.totalAverages = latestByCodeAndLocation.size();
        stats.vitalSignAverages = latestByCodeAndLocation.values().stream()
            .map(this::toVitalSignAverage)
            .collect(Collectors.toList());
        
        return stats;
    }

    private double queryProjectedAvgNews2() {
        Object result = em.createNativeQuery("SELECT COALESCE(AVG(news2), 0) FROM patient_news2_current")
                .getSingleResult();

        Number avg = (Number) result;
        return avg == null ? 0.0 : avg.doubleValue();
    }

    private void finalizeLocationStats(List<LocationStats> locationStats) {
        if (locationStats == null || locationStats.isEmpty()) {
            return;
        }

        for (LocationStats stat : locationStats) {
            stat.computeDistributionMetrics();
            stat.computeRates();
            double[] ci = bootstrapMeanConfidenceInterval(stat.news2Values, 0.95, BOOTSTRAP_SAMPLES);
            stat.bootstrapMeanCiLow = ci[0];
            stat.bootstrapMeanCiHigh = ci[1];
        }
    }

    private double[] bootstrapMeanConfidenceInterval(List<Integer> values, double confidenceLevel, int samples) {
        if (values == null || values.isEmpty()) {
            return new double[]{0.0, 0.0};
        }

        int draws = Math.max(50, samples);
        int n = values.size();
        List<Double> resampledMeans = new ArrayList<>(draws);

        for (int sample = 0; sample < draws; sample++) {
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                int pick = ThreadLocalRandom.current().nextInt(n);
                sum += values.get(pick);
            }
            resampledMeans.add(sum / n);
        }

        Collections.sort(resampledMeans);
        double alpha = Math.max(0.0, Math.min(1.0, 1.0 - confidenceLevel));
        int lowIndex = (int) Math.floor((alpha / 2.0) * (draws - 1));
        int highIndex = (int) Math.ceil((1.0 - alpha / 2.0) * (draws - 1));

        return new double[]{resampledMeans.get(lowIndex), resampledMeans.get(highIndex)};
    }

    private String getRiskLevel(int news2Score) {
        if (news2Score >= 7) return "HIGH";
        if (news2Score >= 5) return "MEDIUM";
        return "LOW";
    }

    private VitalSignAverage toVitalSignAverage(Observation obs) {
        VitalSignAverage vsa = new VitalSignAverage();
        vsa.vitalSign = obs.getCode() != null && obs.getCode().hasCoding()
            ? obs.getCode().getCodingFirstRep().getDisplay()
            : "Unknown";
        vsa.averageValue = obs.getValueQuantity() != null
            ? obs.getValueQuantity().getValue().doubleValue()
            : 0;
        vsa.unit = obs.getValueQuantity() != null
            ? obs.getValueQuantity().getUnit()
            : "";

        Extension sampleExt = obs.getExtensionByUrl("http://observation-sample-count");
        vsa.sampleCount = sampleExt != null && sampleExt.getValue() instanceof IntegerType
            ? ((IntegerType) sampleExt.getValue()).getValue()
            : 0;

        vsa.location = extractLocation(obs);
        return vsa;
    }

    private String extractLocation(Observation obs) {
        Extension locExt = obs.getExtensionByUrl("http://patient-location");
        if (locExt != null) {
            Extension blockExt = locExt.getExtension().stream()
                .filter(e -> "block".equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() instanceof StringType) {
                return ((StringType) blockExt.getValue()).getValue();
            }
        }
        return "Unknown";
    }

    private boolean isNewer(Observation candidate, Observation current) {
        if (candidate == null) return false;
        if (current == null) return true;

        // Prefer meta.lastUpdated when available; fallback to effective date
        java.util.Date candDate = candidate.getMeta() != null ? candidate.getMeta().getLastUpdated() : null;
        java.util.Date currDate = current.getMeta() != null ? current.getMeta().getLastUpdated() : null;

        if (candDate != null && currDate != null) {
            return candDate.after(currDate);
        }
        if (candDate != null) return true;
        if (currDate != null) return false;

        // Fallback to effectiveDateTime
        java.util.Date candEff = candidate.getEffectiveDateTimeType() != null ? candidate.getEffectiveDateTimeType().getValue() : null;
        java.util.Date currEff = current.getEffectiveDateTimeType() != null ? current.getEffectiveDateTimeType().getValue() : null;
        if (candEff != null && currEff != null) {
            return candEff.after(currEff);
        }
        if (candEff != null) return true;
        return false;
    }

    private void writeJsonResponse(HttpServletResponse response, DashboardStats stats) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        // Manual JSON serialization (simple approach)
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"totalPatients\":").append(stats.totalPatients).append(",");
        json.append("\"totalConditions\":").append(stats.totalConditions).append(",");
        json.append("\"totalAverages\":").append(stats.totalAverages).append(",");
        json.append("\"avgNews2Score\":").append(String.format(Locale.US, "%.1f", stats.avgNews2Score)).append(",");
        
        // Location stats
        json.append("\"locationStats\":[");
        for (int i = 0; i < stats.locationStats.size(); i++) {
            LocationStats ls = stats.locationStats.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"location\":\"").append(ls.location).append("\",");
            json.append("\"patientCount\":").append(ls.patientCount).append(",");
            json.append("\"avgNews2\":").append(String.format(Locale.US, "%.1f", ls.news2Count > 0 ? ls.totalNews2 / ls.news2Count : 0)).append(",");
            json.append("\"meanNews2\":").append(String.format(Locale.US, "%.3f", ls.meanNews2)).append(",");
            json.append("\"medianNews2\":").append(String.format(Locale.US, "%.3f", ls.medianNews2)).append(",");
            json.append("\"varianceNews2\":").append(String.format(Locale.US, "%.3f", ls.varianceNews2)).append(",");
            json.append("\"stdDevNews2\":").append(String.format(Locale.US, "%.3f", ls.stdDevNews2)).append(",");
            json.append("\"incidenceRate\":").append(String.format(Locale.US, "%.4f", ls.incidenceRate)).append(",");
            json.append("\"prevalenceRate\":").append(String.format(Locale.US, "%.4f", ls.prevalenceRate)).append(",");
            json.append("\"bootstrapMeanCiLow\":").append(String.format(Locale.US, "%.3f", ls.bootstrapMeanCiLow)).append(",");
            json.append("\"bootstrapMeanCiHigh\":").append(String.format(Locale.US, "%.3f", ls.bootstrapMeanCiHigh));
            json.append("}");
        }
        json.append("],");
        
        // Patients
        json.append("\"patients\":[");
        for (int i = 0; i < Math.min(stats.patients.size(), 100); i++) {
            PatientSummary ps = stats.patients.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"patientId\":\"").append(ps.patientId).append("\",");
            json.append("\"news2Score\":").append(ps.news2Score).append(",");
            json.append("\"location\":\"").append(ps.location).append("\",");
            json.append("\"riskLevel\":\"").append(ps.riskLevel).append("\"");
            json.append("}");
        }
        json.append("],");
        
        // Conditions
        json.append("\"conditions\":[");
        for (int i = 0; i < stats.conditions.size(); i++) {
            ConditionSummary cs = stats.conditions.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"patientId\":\"").append(escapeJson(cs.patientId)).append("\",");
            json.append("\"conditionName\":\"").append(escapeJson(cs.conditionName)).append("\",");
            json.append("\"onsetDate\":").append(cs.onsetDate != null ? "\"" + cs.onsetDate + "\"" : "null");
            json.append("}");
        }
        json.append("],");
        
        // Vital sign averages
        json.append("\"vitalSignAverages\":[");
        for (int i = 0; i < stats.vitalSignAverages.size(); i++) {
            VitalSignAverage vsa = stats.vitalSignAverages.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"vitalSign\":\"").append(escapeJson(vsa.vitalSign)).append("\",");
            json.append("\"averageValue\":").append(String.format(Locale.US, "%.2f", vsa.averageValue)).append(",");
            json.append("\"unit\":\"").append(escapeJson(vsa.unit)).append("\",");
            json.append("\"sampleCount\":").append(vsa.sampleCount).append(",");
            json.append("\"location\":\"").append(escapeJson(vsa.location)).append("\"");
            json.append("}");
        }
        json.append("],");

        // Socioeconomic cluster analysis
        json.append("\"socioeconomicAnalysis\":{");
        json.append("\"sampleSize\":").append(stats.socioeconomicAnalysis.sampleSize()).append(",");
        json.append("\"clusterCount\":").append(stats.socioeconomicAnalysis.clusterCount()).append(",");
        json.append("\"quality\":{");
        json.append("\"selectedK\":").append(stats.socioeconomicAnalysis.quality().selectedK()).append(",");
        json.append("\"silhouetteScore\":").append(String.format(Locale.US, "%.3f", stats.socioeconomicAnalysis.quality().silhouetteScore())).append(",");
        json.append("\"runCount\":").append(stats.socioeconomicAnalysis.quality().runCount()).append(",");
        json.append("\"withinClusterSse\":").append(String.format(Locale.US, "%.3f", stats.socioeconomicAnalysis.quality().withinClusterSse())).append(",");
        json.append("\"minSilhouette\":").append(String.format(Locale.US, "%.3f", stats.socioeconomicAnalysis.quality().minSilhouette())).append(",");
        json.append("\"lowConfidence\":").append(stats.socioeconomicAnalysis.quality().lowConfidence()).append(",");
        json.append("\"minClusterSizeObserved\":").append(stats.socioeconomicAnalysis.quality().minClusterSizeObserved()).append(",");
        json.append("\"singletonClusterCount\":").append(stats.socioeconomicAnalysis.quality().singletonClusterCount()).append(",");
        json.append("\"adjustedSilhouetteScore\":").append(String.format(Locale.US, "%.3f", stats.socioeconomicAnalysis.quality().adjustedSilhouetteScore()));
        json.append("},");
        json.append("\"clusterProfiles\":[");
        for (int i = 0; i < stats.socioeconomicAnalysis.clusterProfiles().size(); i++) {
            BlockSocioeconomicAnalysisService.ClusterProfile profile = stats.socioeconomicAnalysis.clusterProfiles().get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"clusterId\":").append(profile.clusterId()).append(",");
            json.append("\"patientCount\":").append(profile.patientCount()).append(",");
            json.append("\"avgHeartRate\":").append(String.format(Locale.US, "%.2f", profile.avgHeartRate())).append(",");
            json.append("\"avgSystolicPressure\":").append(String.format(Locale.US, "%.2f", profile.avgSystolicPressure())).append(",");
            json.append("\"avgAge\":").append(String.format(Locale.US, "%.2f", profile.avgAge())).append(",");
            json.append("\"avgConditions\":").append(String.format(Locale.US, "%.2f", profile.avgConditions())).append(",");
            json.append("\"pctMale\":").append(String.format(Locale.US, "%.2f", profile.pctMale())).append(",");
            json.append("\"pctFemale\":").append(String.format(Locale.US, "%.2f", profile.pctFemale())).append(",");
            json.append("\"pc1\":").append(String.format(Locale.US, "%.3f", profile.pc1())).append(",");
            json.append("\"pc2\":").append(String.format(Locale.US, "%.3f", profile.pc2())).append(",");
            json.append("\"profileLabel\":\"").append(escapeJson(profile.profileLabel())).append("\"");
            json.append("}");
        }
        json.append("],");
        json.append("\"clusterPoints\":[");
        for (int i = 0; i < stats.socioeconomicAnalysis.clusterPoints().size(); i++) {
            BlockSocioeconomicAnalysisService.ClusterPoint point = stats.socioeconomicAnalysis.clusterPoints().get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"patientId\":\"").append(escapeJson(point.patientId())).append("\",");
            json.append("\"clusterId\":").append(point.clusterId()).append(",");
            json.append("\"heartRate\":").append(point.heartRate()).append(",");
            json.append("\"systolicPressure\":").append(point.systolicPressure()).append(",");
            json.append("\"age\":").append(point.age()).append(",");
            json.append("\"activeConditions\":").append(point.activeConditions()).append(",");
            json.append("\"maleFlag\":").append(point.maleFlag()).append(",");
            json.append("\"femaleFlag\":").append(point.femaleFlag()).append(",");
            json.append("\"pc1\":").append(String.format(Locale.US, "%.3f", point.pc1())).append(",");
            json.append("\"pc2\":").append(String.format(Locale.US, "%.3f", point.pc2()));
            json.append("}");
        }
        json.append("]}");
        
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
    static class DashboardStats {
        int totalPatients;
        int totalConditions;
        int totalAverages;
        double avgNews2Score;
        List<LocationStats> locationStats = new ArrayList<>();
        List<PatientSummary> patients = new ArrayList<>();
        List<ConditionSummary> conditions = new ArrayList<>();
        List<VitalSignAverage> vitalSignAverages = new ArrayList<>();
        BlockSocioeconomicAnalysisService.AnalysisOutput socioeconomicAnalysis =
            BlockSocioeconomicAnalysisService.AnalysisOutput.empty();
    }

    static class LocationStats {
        String location;
        int patientCount;
        double totalNews2;
        int news2Count;
        List<Integer> news2Values = new ArrayList<>();
        int incidentCaseCount;
        int prevalentCaseCount;

        double meanNews2;
        double medianNews2;
        double varianceNews2;
        double stdDevNews2;
        double incidenceRate;
        double prevalenceRate;
        double bootstrapMeanCiLow;
        double bootstrapMeanCiHigh;
        
        LocationStats(String location) {
            this.location = location;
        }

        void computeDistributionMetrics() {
            if (news2Values.isEmpty()) {
                meanNews2 = 0.0;
                medianNews2 = 0.0;
                varianceNews2 = 0.0;
                stdDevNews2 = 0.0;
                return;
            }

            double sum = 0.0;
            for (int value : news2Values) {
                sum += value;
            }
            meanNews2 = sum / news2Values.size();

            List<Integer> sorted = new ArrayList<>(news2Values);
            Collections.sort(sorted);
            int n = sorted.size();
            if (n % 2 == 0) {
                medianNews2 = (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
            } else {
                medianNews2 = sorted.get(n / 2);
            }

            if (n > 1) {
                double varianceSum = 0.0;
                for (int value : news2Values) {
                    double diff = value - meanNews2;
                    varianceSum += diff * diff;
                }
                varianceNews2 = varianceSum / (n - 1);
                stdDevNews2 = Math.sqrt(varianceNews2);
            } else {
                varianceNews2 = 0.0;
                stdDevNews2 = 0.0;
            }
        }

        void computeRates() {
            if (patientCount <= 0) {
                incidenceRate = 0.0;
                prevalenceRate = 0.0;
                return;
            }
            incidenceRate = (double) incidentCaseCount / patientCount;
            prevalenceRate = (double) prevalentCaseCount / patientCount;
        }
    }

    static class PatientSummary {
        String patientId;
        int news2Score;
        String location;
        String riskLevel;
    }

    static class ConditionSummary {
        String patientId;
        String conditionName;
        String onsetDate;
    }

    static class VitalSignAverage {
        String vitalSign;
        double averageValue;
        String unit;
        int sampleCount;
        String location;
    }
}
