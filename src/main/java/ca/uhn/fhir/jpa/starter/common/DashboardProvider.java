package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Provides a custom dashboard API endpoint that aggregates clinical data
 * Accessible at: GET [base]/Patient/$dashboard-stats?block=Block-North-A
 */
@Component
public class DashboardProvider implements IResourceProvider {

    private final DaoRegistry daoRegistry;
    
    @PersistenceContext
    private EntityManager em;
    
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
        
        // Query BlockNews2Aggregate to get block-level aggregates
        List<BlockNews2Aggregate> blockAggregates;
        if (filterBlock != null && !filterBlock.isEmpty()) {
            blockAggregates = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b WHERE b.id.block = :block",
                BlockNews2Aggregate.class
            )
            .setParameter("block", filterBlock)
            .getResultList();
        } else {
            blockAggregates = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b",
                BlockNews2Aggregate.class
            )
            .getResultList();
        }
        
        // Calculate totals and per-block stats
        Map<String, BlockStats> blockStatsMap = new HashMap<>();
        Map<String, NeighborhoodStats> neighborhoodStatsMap = new HashMap<>();
        int totalPatients = 0;
        
        for (BlockNews2Aggregate agg : blockAggregates) {
            String blockName = agg.getBlock();
            BlockStats bs = blockStatsMap.computeIfAbsent(blockName, k -> new BlockStats(k));
            
            bs.patientCount += agg.getPatientCount();
            bs.totalScore += agg.getTotalScore();
            bs.city = agg.getCity();
            bs.region = agg.getRegion();
            
            totalPatients += agg.getPatientCount();
            
            // Aggregate by neighborhood (city)
            String neighborhood = agg.getCity();
            NeighborhoodStats ns = neighborhoodStatsMap.computeIfAbsent(neighborhood, k -> new NeighborhoodStats(k));
            ns.totalScore += agg.getTotalScore();
            ns.patientCount += agg.getPatientCount();
        }
        
        // Get all patients to map to blocks
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        IBundleProvider patientResults = patientDao.search(patientSearch);
        List<IBaseResource> patients = patientResults.getAllResources();
        
        Map<String, String> patientBlockMap = new HashMap<>();
        for (IBaseResource res : patients) {
            Patient patient = (Patient) res;
            String patientId = patient.getIdElement().getIdPart();
            String block = extractBlock(patient);
            if (filterBlock == null || filterBlock.isEmpty() || block.equals(filterBlock)) {
                patientBlockMap.put(patientId, block);
            }
        }
        
        // Get active conditions grouped by block
        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
        SearchParameterMap conditionSearch = new SearchParameterMap();
        conditionSearch.add("clinical-status", new TokenParam("http://terminology.hl7.org/CodeSystem/condition-clinical", "active"));
        conditionSearch.setLoadSynchronous(true);
        IBundleProvider conditionResults = conditionDao.search(conditionSearch);
        List<IBaseResource> conditions = conditionResults.getAllResources();
        
        stats.totalConditions = conditions.size();
        for (IBaseResource res : conditions) {
            Condition c = (Condition) res;
            String patientRef = c.getSubject() != null ? c.getSubject().getReference() : null;
            if (patientRef != null && patientRef.startsWith("Patient/")) {
                String patientId = patientRef.substring("Patient/".length());
                String block = patientBlockMap.get(patientId);
                if (block != null) {
                    BlockStats bs = blockStatsMap.get(block);
                    if (bs != null) {
                        bs.conditionCount++;
                    }
                }
            }
        }
        
        // Get vital sign averages grouped by block
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap avgSearch = new SearchParameterMap();
        avgSearch.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs-average"));
        avgSearch.setLoadSynchronous(true);
        IBundleProvider avgResults = observationDao.search(avgSearch);
        List<IBaseResource> averages = avgResults.getAllResources();
        
        for (IBaseResource res : averages) {
            Observation obs = (Observation) res;
            String block = extractBlockFromObservation(obs);
            if (block != null && (filterBlock == null || filterBlock.isEmpty() || block.equals(filterBlock))) {
                BlockStats bs = blockStatsMap.get(block);
                if (bs != null) {
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
            if (blockExt != null && blockExt.getValue() instanceof StringType) {
                return ((StringType) blockExt.getValue()).getValue();
            }
        }
        return "UNKNOWN";
    }
    
    private String extractBlockFromObservation(Observation obs) {
        Extension locExt = obs.getExtensionByUrl("http://patient-location");
        if (locExt != null) {
            Extension blockExt = locExt.getExtension().stream()
                .filter(e -> "block".equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() instanceof StringType) {
                return ((StringType) blockExt.getValue()).getValue();
            }
        }
        return null;
    }

    private String getRiskLevel(int news2Score) {
        if (news2Score >= 7) return "HIGH";
        if (news2Score >= 5) return "MEDIUM";
        return "LOW";
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
