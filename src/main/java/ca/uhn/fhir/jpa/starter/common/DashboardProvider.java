package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Provides a custom dashboard API endpoint that aggregates clinical data
 * Accessible at: GET [base]/Patient/$dashboard-stats?block=Block-A
 */
@Component
public class DashboardProvider implements IResourceProvider {

    private final DaoRegistry daoRegistry;
    
    @Value("${hapi.fhir.location.block:}")
    private String currentBlock;
    
    // Cache results for 30 seconds
    private DashboardStats cachedStats;
    private long cacheTimestamp = 0;
    private static final long CACHE_DURATION_MS = 3000;

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
        
        // Get all patients
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        IBundleProvider patientResults = patientDao.search(patientSearch);
        List<IBaseResource> patients = patientResults.getAllResources();
        
        stats.totalPatients = patients.size();
        
        // Process patients by location
        Map<String, LocationStats> locationMap = new HashMap<>();
        List<PatientSummary> patientList = new ArrayList<>();
        double totalNews2 = 0;
        int news2Count = 0;
        
        for (IBaseResource res : patients) {
            Patient patient = (Patient) res;
            String patientId = patient.getIdElement().getIdPart();
            
            // Get NEWS2 score
            Integer news2Score = null;
            Extension news2Ext = patient.getExtensionByUrl("http://news2-score");
            if (news2Ext != null && news2Ext.getValue() instanceof IntegerType) {
                news2Score = ((IntegerType) news2Ext.getValue()).getValue();
                totalNews2 += news2Score;
                news2Count++;
            }
            
            // Get location
            String location = currentBlock;
            Extension locationExt = patient.getExtensionByUrl("http://patient-location");
            if (locationExt != null) {
                Extension blockExt = locationExt.getExtension().stream()
                    .filter(e -> "block".equals(e.getUrl()))
                    .findFirst().orElse(null);
                if (blockExt != null && blockExt.getValue() instanceof StringType) {
                    String block = ((StringType) blockExt.getValue()).getValue();
                    if (block != null && !block.isBlank()) {
                        location = block;
                    }
                }
            }

            if (location == null || location.isBlank()) {
                location = "Unknown";
            }
            
            // Filter by block if specified
            if (filterBlock != null && !filterBlock.isEmpty() && !Objects.equals(location.trim().toLowerCase(Locale.ROOT), filterBlock.trim().toLowerCase(Locale.ROOT))) {
                continue; // Skip patients not in the requested block
            }
            
            // Add to location stats
            LocationStats locStats = locationMap.computeIfAbsent(location, k -> new LocationStats(k));
            locStats.patientCount++;
            if (news2Score != null) {
                locStats.totalNews2 += news2Score;
                locStats.news2Count++;
            }
            
            // Add patient summary
            PatientSummary ps = new PatientSummary();
            ps.patientId = patientId;
            ps.news2Score = news2Score != null ? news2Score : 0;
            ps.location = location;
            ps.riskLevel = getRiskLevel(ps.news2Score);
            patientList.add(ps);
        }
        
        stats.avgNews2Score = news2Count > 0 ? totalNews2 / news2Count : 0;
        stats.locationStats = new ArrayList<>(locationMap.values());
        stats.patients = patientList;
        
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
            json.append("\"avgNews2\":").append(String.format(Locale.US, "%.1f", ls.news2Count > 0 ? ls.totalNews2 / ls.news2Count : 0));
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
    static class DashboardStats {
        int totalPatients;
        int totalConditions;
        int totalAverages;
        double avgNews2Score;
        List<LocationStats> locationStats = new ArrayList<>();
        List<PatientSummary> patients = new ArrayList<>();
        List<ConditionSummary> conditions = new ArrayList<>();
        List<VitalSignAverage> vitalSignAverages = new ArrayList<>();
    }

    static class LocationStats {
        String location;
        int patientCount;
        double totalNews2;
        int news2Count;
        
        LocationStats(String location) {
            this.location = location;
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
