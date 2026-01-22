package ca.uhn.fhir.jpa.starter.cdss;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.common.UpstreamForwarder;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.TokenParam;

/**
 * Scheduled service that calculates average vital signs and forwards them to upstream server.
 * Runs every 5 minutes to compute vital sign averages from recent observations.
 */
@Service
public class ScheduledVitalSignAggregationService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledVitalSignAggregationService.class);

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired(required = false)
    private UpstreamForwarder upstreamForwarder;

    // Vital sign LOINC codes we track
    private static final Map<String, String> VITAL_SIGN_CODES = new HashMap<String, String>() {{
        put("8867-4", "Heart rate");
        put("9279-1", "Respiratory rate");
        put("8480-6", "Systolic blood pressure");
        put("8462-4", "Diastolic blood pressure");
        put("8310-5", "Body temperature");
        put("59408-5", "Oxygen saturation");
    }};

    // Run every 5 minutes (300000 ms), initial delay 20 seconds
    @Scheduled(fixedDelay = 300000, initialDelay = 20000)
    public void calculateAndForwardAverages() {
        logger.info("========================================");
        logger.info("VITAL SIGN AGGREGATION - Starting");
        logger.info("========================================");

        long startTime = System.currentTimeMillis();

        try {
            // Hardcoded location
            String location = "North";
            
            // Get recent vital signs from last 5 minutes (matching the schedule interval)
            List<Observation> recentVitalSigns = getRecentVitalSigns();
            
            logger.info("Found {} recent vital sign observations", recentVitalSigns.size());

            if (recentVitalSigns.isEmpty()) {
                logger.info("No recent vital signs to aggregate");
                return;
            }

            // Calculate averages by vital sign type
            Map<String, List<Observation>> observationsByCode = groupObservationsByCode(recentVitalSigns);
            
            List<Observation> aggregateObservations = new ArrayList<>();
            
            for (Map.Entry<String, List<Observation>> entry : observationsByCode.entrySet()) {
                String code = entry.getKey();
                List<Observation> observations = entry.getValue();
                
                Double average = calculateAverage(observations);
                if (average != null) {
                    Observation aggregateObs = createAggregateObservation(location, code, average, observations.size());
                    aggregateObservations.add(aggregateObs);
                    logger.info("Location {}: {} avg = {} (n={})", location, VITAL_SIGN_CODES.get(code), 
                               String.format("%.2f", average), observations.size());
                }
            }

            // Forward aggregate observations to upstream server
            if (!aggregateObservations.isEmpty() && upstreamForwarder != null) {
                try {
                    // First save to local database
                    IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
                    for (Observation aggObs : aggregateObservations) {
                        observationDao.create(aggObs);
                    }
                    logger.info("✓ Saved {} aggregate observations to local database", aggregateObservations.size());
                    
                    // Then forward to upstream
                    logger.info("Forwarding {} aggregate observations to upstream server", aggregateObservations.size());
                    upstreamForwarder.createObservations(aggregateObservations);
                    logger.info("✓ Successfully forwarded aggregate observations");
                } catch (Exception e) {
                    logger.error("Failed to save/forward aggregate observations", e);
                }
            } else if (upstreamForwarder == null) {
                logger.warn("UpstreamForwarder is NULL - aggregates NOT forwarded");
            } else {
                logger.info("No aggregate observations to forward");
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("========================================");
            logger.info("VITAL SIGN AGGREGATION - Complete");
            logger.info("Aggregate observations created: {}", aggregateObservations.size());
            logger.info("Duration: {} ms", duration);
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("Error in vital sign aggregation", e);
        }
    }

    /**
     * Get recent vital signs from all patients (last 5 minutes, matching schedule interval)
     */
    private List<Observation> getRecentVitalSigns() {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        
        SearchParameterMap searchMap = new SearchParameterMap();
        // Source observations should be the standard vital signs category
        searchMap.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs"));
        
        // Last 5 minutes (300000 ms) to match the schedule interval
        Date fiveMinutesAgo = new Date(System.currentTimeMillis() - 300000);
        searchMap.add("date", new DateRangeParam(fiveMinutesAgo, null));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = observationDao.search(searchMap);
        
        return results.getAllResources().stream()
            .filter(r -> r instanceof Observation)
            .map(r -> (Observation) r)
            .collect(Collectors.toList());
    }

    /**
     * Group observations by LOINC code
     */
    private Map<String, List<Observation>> groupObservationsByCode(List<Observation> observations) {
        Map<String, List<Observation>> grouped = new HashMap<>();
        
        for (Observation obs : observations) {
            if (obs.hasCode() && obs.getCode().hasCoding()) {
                for (Coding coding : obs.getCode().getCoding()) {
                    if (VITAL_SIGN_CODES.containsKey(coding.getCode())) {
                        grouped.computeIfAbsent(coding.getCode(), k -> new ArrayList<>()).add(obs);
                        break; // Only count each observation once
                    }
                }
            }
        }
        
        return grouped;
    }

    /**
     * Calculate average value from observations
     */
    private Double calculateAverage(List<Observation> observations) {
        List<Double> values = new ArrayList<>();
        
        for (Observation obs : observations) {
            if (obs.hasValueQuantity()) {
                values.add(obs.getValueQuantity().getValue().doubleValue());
            }
        }
        
        if (values.isEmpty()) {
            return null;
        }
        
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * Create an aggregate observation representing the average
     */
    private Observation createAggregateObservation(String location, String loincCode, Double average, int sampleCount) {
        Observation obs = new Observation();
        
        // Set status
        obs.setStatus(Observation.ObservationStatus.FINAL);
        
        // Set category - use "vital-signs-average" to distinguish from source observations
        CodeableConcept category = new CodeableConcept();
        category.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
            .setCode("vital-signs-average")
            .setDisplay("Vital Signs Average");
        obs.addCategory(category);
        
        // Add extension to indicate this is aggregate data
        Extension aggregateExt = new Extension();
        aggregateExt.setUrl("http://hl7.org/fhir/StructureDefinition/observation-statisticsCode");
        aggregateExt.setValue(new CodeableConcept().addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/observation-statistics")
            .setCode("average")
            .setDisplay("Average"));
        obs.addExtension(aggregateExt);
        
        // Add location extension
        Extension locationExt = new Extension();
        locationExt.setUrl("http://patient-location");
        Extension blockExt = new Extension();
        blockExt.setUrl("block");
        blockExt.setValue(new StringType(location));
        locationExt.addExtension(blockExt);
        obs.addExtension(locationExt);
        
        // Add sample count extension
        Extension sampleCountExt = new Extension();
        sampleCountExt.setUrl("http://observation-sample-count");
        sampleCountExt.setValue(new IntegerType(sampleCount));
        obs.addExtension(sampleCountExt);
        
        // Set code
        CodeableConcept code = new CodeableConcept();
        code.addCoding()
            .setSystem("http://loinc.org")
            .setCode(loincCode)
            .setDisplay(VITAL_SIGN_CODES.get(loincCode));
        obs.setCode(code);
        
        // Set value (average)
        Quantity value = new Quantity();
        value.setValue(average);
        value.setUnit(getUnitForCode(loincCode));
        obs.setValue(value);
        
        // Set effective time (now)
        obs.setEffective(new DateTimeType(new Date()));
        
        return obs;
    }

    /**
     * Get appropriate unit for vital sign code
     */
    private String getUnitForCode(String code) {
        switch (code) {
            case "8867-4": return "/min"; // Heart rate
            case "9279-1": return "/min"; // Respiratory rate
            case "8480-6": return "mm[Hg]"; // Systolic BP
            case "8462-4": return "mm[Hg]"; // Diastolic BP
            case "8310-5": return "Cel"; // Temperature
            case "59408-5": return "%"; // SpO2
            default: return "";
        }
    }
}
