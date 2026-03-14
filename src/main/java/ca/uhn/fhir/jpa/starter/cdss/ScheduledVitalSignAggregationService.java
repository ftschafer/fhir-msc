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
import org.springframework.beans.factory.annotation.Value;
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
    private static final String AGGREGATE_IDENTIFIER_SYSTEM = "urn:aggregate:vitalsign";

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired(required = false)
    private UpstreamForwarder upstreamForwarder;
    
    @Autowired
    private VitalSignHistoryService historyService;
    
    @Value("${hapi.fhir.location.block:Block-Default}")
    private String currentBlock;

    // Vital sign LOINC codes we track
    private static final Map<String, String> VITAL_SIGN_CODES = new HashMap<String, String>() {{
        put("8867-4", "Heart rate");
        put("9279-1", "Respiratory rate");
        put("8480-6", "Systolic blood pressure");
        put("8462-4", "Diastolic blood pressure");
        put("8310-5", "Body temperature");
        put("59408-5", "Oxygen saturation");
    }};

    // Run every 15 seconds (15000 ms)
    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    public void calculateAndForwardAverages() {
        logger.info("========================================");
        logger.info("VITAL SIGN AGGREGATION - Starting");
        logger.info("========================================");

        long startTime = System.currentTimeMillis();

        try {
            // Use configured block location
            String location = currentBlock;
            
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
                List<Observation> observations = collapseToLatestPerPatient(entry.getValue());

                List<Double> normalizedValues = extractNormalizedValues(observations, code);
                if (normalizedValues.isEmpty()) {
                    logger.info("Block {}: {} skipped (no valid values)", currentBlock, VITAL_SIGN_CODES.get(code));
                    continue;
                }

                double average = normalizedValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                int sampleCount = normalizedValues.size();

                String aggregateIdentifier = buildAggregateIdentifier(code);
                Observation aggregateObs = createAggregateObservation(currentBlock, code, average, sampleCount, aggregateIdentifier);
                aggregateObservations.add(aggregateObs);
                
                // Store in history for charting
                historyService.addDataPoint(currentBlock, code, average, sampleCount);
                logger.info("✓ Stored in history: block={}, code={}, avg={}", currentBlock, code, String.format("%.2f", average));
                
                logger.info("Block {}: {} avg = {} (n={})", currentBlock, VITAL_SIGN_CODES.get(code), 
                           String.format("%.2f", average), sampleCount);
            }

            // Forward aggregate observations to upstream server
            if (!aggregateObservations.isEmpty()) {
                IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
                List<Observation> persistedAggregates = new ArrayList<>();

                for (Observation aggObs : aggregateObservations) {
                    Observation existing = findExistingAggregate(observationDao, aggObs.getIdentifierFirstRep().getValue());
                    if (existing != null) {
                        aggObs.setId(existing.getIdElement());
                        observationDao.update(aggObs);
                        logger.info("Updated existing aggregate for code {} (identifier {})", 
                            aggObs.getCode().getCodingFirstRep().getCode(), aggObs.getIdentifierFirstRep().getValue());
                    } else {
                        observationDao.create(aggObs);
                        logger.info("Created new aggregate for code {} (identifier {})", 
                            aggObs.getCode().getCodingFirstRep().getCode(), aggObs.getIdentifierFirstRep().getValue());
                    }
                    persistedAggregates.add(aggObs);
                }

                logger.info("✓ Upserted {} aggregate observations to local database", persistedAggregates.size());

                if (upstreamForwarder != null) {
                    try {
                        logger.info("Forwarding {} aggregate observations to upstream server", persistedAggregates.size());
                        boolean forwarded = upstreamForwarder.createObservations(persistedAggregates);
                        if (forwarded) {
                            logger.info("✓ Successfully forwarded aggregate observations");
                        } else {
                            logger.warn("Aggregate observations were not forwarded (upstream unavailable/suppressed)");
                        }
                    } catch (Exception e) {
                        logger.error("Failed to forward aggregate observations", e);
                    }
                } else {
                    logger.warn("UpstreamForwarder is NULL - aggregates NOT forwarded");
                }
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

        List<Observation> observations = results.getAllResources().stream()
            .filter(r -> r instanceof Observation)
            .map(r -> (Observation) r)
            .collect(Collectors.toList());

        if (!observations.isEmpty()) {
            return observations;
        }

        // Fallback 1: category may be missing/misaligned in incoming source observations
        SearchParameterMap fallbackByDate = new SearchParameterMap();
        fallbackByDate.add("date", new DateRangeParam(fiveMinutesAgo, null));
        fallbackByDate.setLoadSynchronous(true);

        List<Observation> byDateNoCategory = observationDao.search(fallbackByDate)
            .getAllResources().stream()
            .filter(r -> r instanceof Observation)
            .map(r -> (Observation) r)
            .filter(this::isRelevantVitalSign)
            .collect(Collectors.toList());

        if (!byDateNoCategory.isEmpty()) {
            logger.info("No category=vital-signs in last 5 minutes; using {} fallback observations by LOINC code",
                byDateNoCategory.size());
            return byDateNoCategory;
        }

        // Fallback 2: historical payloads (effectiveDateTime older than now-window)
        SearchParameterMap fallbackAll = new SearchParameterMap();
        fallbackAll.setLoadSynchronous(true);

        List<Observation> historical = observationDao.search(fallbackAll)
            .getAllResources().stream()
            .filter(r -> r instanceof Observation)
            .map(r -> (Observation) r)
            .filter(this::isRelevantVitalSign)
            .collect(Collectors.toList());

        if (!historical.isEmpty()) {
            logger.info("No recent vital signs in 5 minutes; using {} historical observations", historical.size());
        }

        return historical;
    }

    private boolean isRelevantVitalSign(Observation obs) {
        if (!obs.hasCode() || !obs.getCode().hasCoding()) {
            return false;
        }

        // Never use already-aggregated observations as source input
        boolean isAverageCategory = obs.getCategory().stream()
            .flatMap(cat -> cat.getCoding().stream())
            .anyMatch(c -> "vital-signs-average".equals(c.getCode()));
        if (isAverageCategory) {
            return false;
        }

        boolean hasStatisticsAverageExtension = obs.getExtension().stream()
            .anyMatch(ext -> "http://hl7.org/fhir/StructureDefinition/observation-statisticsCode".equals(ext.getUrl()));
        if (hasStatisticsAverageExtension) {
            return false;
        }

        // Source vitals must belong to a patient
        if (!obs.hasSubject() || !obs.getSubject().hasReference() || !obs.getSubject().getReference().startsWith("Patient/")) {
            return false;
        }

        return obs.getCode().getCoding().stream().anyMatch(coding -> VITAL_SIGN_CODES.containsKey(coding.getCode()));
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
     * Keep only the latest observation per patient for a given code.
     */
    private List<Observation> collapseToLatestPerPatient(List<Observation> observations) {
        Map<String, Observation> latest = new HashMap<>();

        for (Observation obs : observations) {
            if (!obs.hasSubject() || !obs.getSubject().hasReference() || !obs.getSubject().getReference().startsWith("Patient/")) {
                continue;
            }
            String patientKey = obs.getSubject().getReference();

            Observation current = latest.get(patientKey);
            if (current == null || isNewerObservation(obs, current)) {
                latest.put(patientKey, obs);
            }
        }

        return new ArrayList<>(latest.values());
    }

    /**
     * Calculate average value from observations
     */
    /**
     * Normalize values (unit conversion + plausibility filter) and return valid values only
     */
    private List<Double> extractNormalizedValues(List<Observation> observations, String loincCode) {
        List<Double> values = new ArrayList<>();

        for (Observation obs : observations) {
            Double v = normalizeValue(obs, loincCode);
            if (v != null) {
                values.add(v);
            }
        }

        return values;
    }

    /**
     * Convert units when needed and drop implausible values to keep aggregates sane.
     */
    private Double normalizeValue(Observation obs, String loincCode) {
        if (!obs.hasValueQuantity()) return null;

        Quantity q = obs.getValueQuantity();
        if (q == null || q.getValue() == null) return null;

        Double raw = q.getValue().doubleValue();
        String unit = (q.hasUnit() ? q.getUnit() : q.getCode());
        String unitNorm = unit != null ? unit.trim().toLowerCase() : "";

        switch (loincCode) {
            case "8310-5": { // Body temperature
                double tempC;
                if (unitNorm.contains("f")) {
                    // Fahrenheit -> Celsius
                    tempC = (raw - 32.0) / 1.8;
                } else if (unitNorm.contains("cel") || unitNorm.contains("c")) {
                    tempC = raw;
                } else {
                    // Heuristic: if no unit and value looks like Fahrenheit, convert
                    tempC = raw > 80 ? (raw - 32.0) / 1.8 : raw;
                }
                // Plausibility window 25C-45C
                return (tempC >= 25.0 && tempC <= 45.0) ? tempC : null;
            }
            case "8867-4": // Heart rate
                return (raw >= 20 && raw <= 250) ? raw : null;
            case "9279-1": // Respiratory rate
                return (raw >= 5 && raw <= 80) ? raw : null;
            case "8480-6": // Systolic BP
                return (raw >= 50 && raw <= 300) ? raw : null;
            case "8462-4": // Diastolic BP
                return (raw >= 30 && raw <= 200) ? raw : null;
            case "59408-5": // SpO2
                return (raw >= 0 && raw <= 100) ? raw : null;
            default:
                return raw;
        }
    }

    /**
     * Determine if candidate is newer than current using meta.lastUpdated then effectiveDateTime
     */
    private boolean isNewerObservation(Observation candidate, Observation current) {
        if (candidate == null) return false;
        if (current == null) return true;

        java.util.Date candMeta = candidate.getMeta() != null ? candidate.getMeta().getLastUpdated() : null;
        java.util.Date currMeta = current.getMeta() != null ? current.getMeta().getLastUpdated() : null;
        if (candMeta != null && currMeta != null) {
            return candMeta.after(currMeta);
        }
        if (candMeta != null) return true;
        if (currMeta != null) return false;

        java.util.Date candEff = candidate.getEffectiveDateTimeType() != null ? candidate.getEffectiveDateTimeType().getValue() : null;
        java.util.Date currEff = current.getEffectiveDateTimeType() != null ? current.getEffectiveDateTimeType().getValue() : null;
        if (candEff != null && currEff != null) {
            return candEff.after(currEff);
        }
        if (candEff != null) return true;
        return false;
    }

    /**
     * Create an aggregate observation representing the average
     */
    private Observation createAggregateObservation(String location, String loincCode, Double average, int sampleCount, String aggregateIdentifier) {
        Observation obs = new Observation();
        
        // Set status
        obs.setStatus(Observation.ObservationStatus.FINAL);

        // Stable identifier so each schedule run updates the same aggregate instead of adding duplicates
        obs.addIdentifier()
            .setSystem(AGGREGATE_IDENTIFIER_SYSTEM)
            .setValue(aggregateIdentifier);
        
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
        
        // Add location extension (only block level for now)
        Extension locationExt = new Extension();
        locationExt.setUrl("http://patient-location");
        Extension blockExt = new Extension();
        blockExt.setUrl("block");
        blockExt.setValue(new StringType(currentBlock));
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
     * Build a stable identifier for aggregated observations (block + LOINC code)
     */
    private String buildAggregateIdentifier(String loincCode) {
        return currentBlock + "-" + loincCode;
    }

    /**
     * Find an existing aggregate observation by its identifier
     */
    private Observation findExistingAggregate(IFhirResourceDao<Observation> observationDao, String aggregateIdentifier) {
        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.add("identifier", new TokenParam(AGGREGATE_IDENTIFIER_SYSTEM, aggregateIdentifier));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = observationDao.search(searchMap);
        List<IBaseResource> resources = results.getAllResources();
        if (!resources.isEmpty() && resources.get(0) instanceof Observation) {
            return (Observation) resources.get(0);
        }
        return null;
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
