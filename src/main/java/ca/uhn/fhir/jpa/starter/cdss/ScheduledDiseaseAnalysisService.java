package ca.uhn.fhir.jpa.starter.cdss;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
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
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;

/**
 * Scheduled service that periodically analyzes all patients for clinical conditions
 * based on vital sign patterns using CQL evaluation.
 * 
 * Runs every 5 minutes to check all patients and create Condition resources
 * when disease patterns are detected.
 */
@Service
public class ScheduledDiseaseAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledDiseaseAnalysisService.class);

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired
    private CqlLibraryEvaluator cqlLibraryEvaluator;

    @Autowired
    private ConditionFactory conditionFactory;

    @Autowired(required = false)
    private UpstreamForwarder upstreamForwarder;

    // CQL libraries to evaluate
    private static final String[] CQL_LIBRARIES = {
        "SepsisDetection",
        "COVID19Detection",
        "AsthmaDetection"
    };

    // Run every 5 minutes (300000 ms)
    @Scheduled(fixedDelay = 3000, initialDelay = 20000)
    public void analyzeAllPatients() {
        logger.info("========================================");
        logger.info("SCHEDULED DISEASE ANALYSIS - Starting");
        logger.info("========================================");

        long startTime = System.currentTimeMillis();
        int patientsAnalyzed = 0;
        int conditionsCreated = 0;

        try {
            // Get all patients
            IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
            SearchParameterMap searchMap = new SearchParameterMap();
            searchMap.setLoadSynchronous(true);
            
            IBundleProvider patientResults = patientDao.search(searchMap);
            List<IBaseResource> patients = patientResults.getAllResources();

            logger.info("Found {} patients to analyze", patients.size());
            
            // Forward all patients first to ensure they exist upstream
            if (upstreamForwarder != null && !patients.isEmpty()) {
                try {
                    List<Patient> patientList = patients.stream()
                        .filter(p -> p instanceof Patient)
                        .map(p -> (Patient) p)
                        .collect(java.util.stream.Collectors.toList());
                    logger.info("Forwarding {} patients to upstream server", patientList.size());
                    upstreamForwarder.upsertPatients(patientList);
                    logger.info("✓ Successfully forwarded patients upstream");
                } catch (Exception e) {
                    logger.error("Failed to forward patients upstream", e);
                }
            }

            List<Condition> newConditions = new ArrayList<>();
            Set<String> observationIds = new HashSet<>();

            // Analyze each patient
            for (IBaseResource resource : patients) {
                if (resource instanceof Patient) {
                    Patient patient = (Patient) resource;
                    String patientId = patient.getIdElement().getIdPart();
                    
                    try {
                        List<Condition> created = analyzePatient(patientId);
                        newConditions.addAll(created);
                        
                        // Collect all Observation IDs referenced in the conditions
                        for (Condition condition : created) {
                            if (condition.hasEvidence()) {
                                for (Condition.ConditionEvidenceComponent evidence : condition.getEvidence()) {
                                    for (Reference ref : evidence.getDetail()) {
                                        if (ref.getReference() != null && ref.getReference().startsWith("Observation/")) {
                                            String obsId = ref.getReference().substring("Observation/".length());
                                            observationIds.add(obsId);
                                        }
                                    }
                                }
                            }
                        }
                        
                        conditionsCreated += created.size();
                        patientsAnalyzed++;
                    } catch (Exception e) {
                        logger.error("Error analyzing patient " + patientId, e);
                    }
                }
            }

            // Forward all new conditions to upstream server (with their referenced Observations)
            if (!newConditions.isEmpty() && upstreamForwarder != null) {
                try {
                    logger.info("Forwarding {} conditions to upstream server", newConditions.size());
                    
                    // Fetch all referenced Observations
                    List<Observation> referencedObservations = new ArrayList<>();
                    if (!observationIds.isEmpty()) {
                        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
                        for (String obsId : observationIds) {
                            try {
                                Observation obs = observationDao.read(new org.hl7.fhir.r4.model.IdType(obsId));
                                if (obs != null) {
                                    referencedObservations.add(obs);
                                }
                            } catch (Exception e) {
                                logger.warn("Could not fetch Observation/{} for forwarding: {}", obsId, e.getMessage());
                            }
                        }
                        logger.info("Collected {} referenced Observations for forwarding", referencedObservations.size());
                    }
                    
                    upstreamForwarder.upsertConditionsWithObservations(newConditions, referencedObservations);
                    logger.info("✓ Successfully forwarded conditions upstream");
                } catch (Exception e) {
                    logger.error("Failed to forward conditions upstream", e);
                }
            } else if (upstreamForwarder == null) {
                logger.warn("UpstreamForwarder is NULL - conditions NOT forwarded");
            } else if (newConditions.isEmpty()) {
                logger.info("No new conditions to forward");
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("========================================");
            logger.info("SCHEDULED DISEASE ANALYSIS - Complete");
            logger.info("Patients analyzed: {}", patientsAnalyzed);
            logger.info("Conditions created: {}", conditionsCreated);
            logger.info("Duration: {} ms", duration);
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("Error in scheduled disease analysis", e);
        }
    }

    /**
     * Analyze a single patient for disease conditions
     * Re-evaluates existing conditions and resolves them if no longer applicable
     * Creates new conditions for currently detected diseases
     * @return List of conditions created
     */
    private List<Condition> analyzePatient(String patientId) {
        logger.debug("Analyzing patient: {}", patientId);

        List<Condition> createdConditions = new ArrayList<>();
        int conditionsResolved = 0;

        // Get recent vital sign observations (last 24 hours)
        List<Observation> vitalSigns = getRecentVitalSigns(patientId);
        
        if (vitalSigns.isEmpty()) {
            logger.debug("No vital signs found for patient {}, skipping", patientId);
            return Collections.emptyList();
        }

        // Get patient's current encounter and location
        Encounter currentEncounter = getCurrentEncounter(patientId);
        Location patientLocation = getPatientLocation(currentEncounter);

        // Step 1: Re-evaluate existing active conditions and resolve if no longer applicable
        List<Condition> existingConditions = getActiveConditions(patientId);
        Map<String, Boolean> currentDiseaseStates = new HashMap<>();

        // Evaluate each CQL library to determine current state
        for (String libraryName : CQL_LIBRARIES) {
            try {
                // Evaluate CQL library for this patient
                Map<String, Object> results = cqlLibraryEvaluator.evaluateLibrary(
                    libraryName, 
                    patientId, 
                    vitalSigns
                );

                // Track which diseases are currently detected
                boolean diseaseDetected = shouldCreateCondition(libraryName, results);
                currentDiseaseStates.put(libraryName, diseaseDetected);

            } catch (Exception e) {
                logger.error("Error evaluating " + libraryName + " for patient " + patientId, e);
            }
        }

        // Step 2: Resolve existing conditions that no longer apply
        for (Condition existingCondition : existingConditions) {
            String libraryName = extractLibraryNameFromCondition(existingCondition);
            if (libraryName != null && currentDiseaseStates.containsKey(libraryName)) {
                Boolean stillDetected = currentDiseaseStates.get(libraryName);
                if (stillDetected != null && !stillDetected) {
                    // Disease no longer detected - resolve the condition
                    resolveCondition(existingCondition);
                    conditionsResolved++;
                    logger.info("✓ Resolved Condition for patient {} ({}): no longer detected", 
                               patientId, libraryName);
                }
            }
        }

        // Step 3: Create new conditions for currently detected diseases (if not already exists)
        for (Map.Entry<String, Boolean> entry : currentDiseaseStates.entrySet()) {
            String libraryName = entry.getKey();
            Boolean detected = entry.getValue();

            if (detected != null && detected) {
                // Check if we already have an active condition for this
                if (!hasActiveConditionForLibrary(existingConditions, libraryName)) {
                    try {
                        // Re-evaluate to get full results for condition creation
                        Map<String, Object> results = cqlLibraryEvaluator.evaluateLibrary(
                            libraryName, 
                            patientId, 
                            vitalSigns
                        );

                        // Create new condition
                        Condition condition = conditionFactory.createCondition(
                            patientId,
                            libraryName,
                            results,
                            vitalSigns,
                            currentEncounter,
                            patientLocation
                        );

                        // Persist condition
                        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
                        Condition createdCondition = (Condition) conditionDao.create(condition).getResource();
                        
                        createdConditions.add(createdCondition);
                        logger.info("✓ Created NEW Condition for patient {} using {}", patientId, libraryName);
                    } catch (Exception e) {
                        logger.error("Error creating condition for " + libraryName, e);
                    }
                } else {
                    logger.debug("Condition still active for patient {} using {}", patientId, libraryName);
                }
            }
        }

        if (conditionsResolved > 0 || !createdConditions.isEmpty()) {
            logger.info("Patient {}: {} created, {} resolved", patientId, createdConditions.size(), conditionsResolved);
        }

        return createdConditions;
    }

    /**
     * Get recent vital sign observations for a patient (last 24 hours)
     */
    private List<Observation> getRecentVitalSigns(String patientId) {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        
        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.add("patient", new ReferenceParam(patientId));
        searchMap.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs"));
        
        // Last 24 hours
        Date oneDayAgo = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
        searchMap.add("date", new ca.uhn.fhir.rest.param.DateRangeParam(oneDayAgo, null));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = observationDao.search(searchMap);

        List<Observation> observations = results.getAllResources().stream()
            .filter(r -> r instanceof Observation)
            .map(r -> (Observation) r)
            .collect(Collectors.toList());

        if (!observations.isEmpty()) {
            return observations;
        }

        // Fallback 1: some feeds do not set category=vital-signs correctly
        SearchParameterMap fallbackByDate = new SearchParameterMap();
        fallbackByDate.add("patient", new ReferenceParam(patientId));
        fallbackByDate.add("date", new ca.uhn.fhir.rest.param.DateRangeParam(oneDayAgo, null));
        fallbackByDate.setLoadSynchronous(true);

        List<Observation> byDateNoCategory = observationDao.search(fallbackByDate)
                .getAllResources().stream()
                .filter(r -> r instanceof Observation)
                .map(r -> (Observation) r)
                .filter(this::isRelevantVitalSign)
                .collect(Collectors.toList());

        if (!byDateNoCategory.isEmpty()) {
            logger.info("Patient {}: no category=vital-signs in last 24h; using {} fallback observations by LOINC code",
                    patientId,
                    byDateNoCategory.size());
            return byDateNoCategory;
        }

        // Fallback 2: historical datasets (effectiveDateTime older than 24h)
        SearchParameterMap fallbackAll = new SearchParameterMap();
        fallbackAll.add("patient", new ReferenceParam(patientId));
        fallbackAll.setLoadSynchronous(true);

        List<Observation> historical = observationDao.search(fallbackAll)
                .getAllResources().stream()
                .filter(r -> r instanceof Observation)
                .map(r -> (Observation) r)
                .filter(this::isRelevantVitalSign)
                .collect(Collectors.toList());

        if (!historical.isEmpty()) {
            logger.info("Patient {}: no recent vitals in 24h; using {} historical observations",
                    patientId,
                    historical.size());
        }

        return historical;
    }

    private boolean isRelevantVitalSign(Observation obs) {
        if (!obs.hasCode() || !obs.getCode().hasCoding()) {
            return false;
        }

        // Exclude aggregate observations from CDSS signal input
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

        if (!obs.hasSubject() || !obs.getSubject().hasReference() || !obs.getSubject().getReference().startsWith("Patient/")) {
            return false;
        }

        return obs.getCode().getCoding().stream().anyMatch(coding -> {
            String code = coding.getCode();
            return "8867-4".equals(code)
                    || "8480-6".equals(code)
                    || "8462-4".equals(code)
                    || "9279-1".equals(code)
                    || "8310-5".equals(code)
                    || "59408-5".equals(code);
        });
    }

    /**
     * Get the patient's current encounter
     */
    private Encounter getCurrentEncounter(String patientId) {
        IFhirResourceDao<Encounter> encounterDao = daoRegistry.getResourceDao(Encounter.class);
        
        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.add("patient", new ReferenceParam(patientId));
        searchMap.add("status", new TokenParam("in-progress"));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = encounterDao.search(searchMap);
        List<IBaseResource> encounters = results.getAllResources();

        if (!encounters.isEmpty() && encounters.get(0) instanceof Encounter) {
            return (Encounter) encounters.get(0);
        }

        return null;
    }

    /**
     * Get the location from an encounter
     */
    private Location getPatientLocation(Encounter encounter) {
        if (encounter == null || !encounter.hasLocation()) {
            return null;
        }

        // Get the first location reference
        Encounter.EncounterLocationComponent locationComponent = encounter.getLocationFirstRep();
        if (!locationComponent.hasLocation()) {
            return null;
        }

        String locationId = locationComponent.getLocation().getReferenceElement().getIdPart();
        
        try {
            IFhirResourceDao<Location> locationDao = daoRegistry.getResourceDao(Location.class);
            return locationDao.read(new IdType("Location", locationId));
        } catch (Exception e) {
            logger.warn("Could not retrieve location {}", locationId);
            return null;
        }
    }

    /**
     * Determine if a condition should be created based on CQL results
     */
    private boolean shouldCreateCondition(String libraryName, Map<String, Object> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }

        // Sepsis: Create if "Sepsis Detected" is true (ALL exact symptoms matched)
        if ("SepsisDetection".equals(libraryName)) {
            Object detected = results.get("Sepsis Detected");
            return detected instanceof Boolean && (Boolean) detected;
        }

        // COVID-19: Create if "COVID19 Detected" is true (ALL exact symptoms matched)
        if ("COVID19Detection".equals(libraryName)) {
            Object detected = results.get("COVID19 Detected");
            return detected instanceof Boolean && (Boolean) detected;
        }

        // Asthma: Create if "Asthma Detected" is true (ALL exact symptoms matched)
        if ("AsthmaDetection".equals(libraryName)) {
            Object detected = results.get("Asthma Detected");
            return detected instanceof Boolean && (Boolean) detected;
        }

        return false;
    }

    /**
     * Get all active conditions for a patient
     */
    private List<Condition> getActiveConditions(String patientId) {
        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);

        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.add("patient", new ReferenceParam(patientId));
        searchMap.add("clinical-status", new TokenParam("http://terminology.hl7.org/CodeSystem/condition-clinical", "active"));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = conditionDao.search(searchMap);
        
        return results.getAllResources().stream()
            .filter(r -> r instanceof Condition)
            .map(r -> (Condition) r)
            .collect(Collectors.toList());
    }

    /**
     * Extract library name from condition notes
     */
    private String extractLibraryNameFromCondition(Condition condition) {
        if (!condition.hasNote()) {
            return null;
        }

        for (Annotation note : condition.getNote()) {
            if (note.hasText()) {
                String text = note.getText();
                // Check if note contains any of our library names
                for (String libraryName : CQL_LIBRARIES) {
                    if (text.contains(libraryName)) {
                        return libraryName;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if an active condition exists for a specific library
     */
    private boolean hasActiveConditionForLibrary(List<Condition> conditions, String libraryName) {
        for (Condition condition : conditions) {
            String conditionLibrary = extractLibraryNameFromCondition(condition);
            if (libraryName.equals(conditionLibrary)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve a condition by setting clinical status to 'resolved'
     * Updates the condition to mark it as no longer active
     */
    private void resolveCondition(Condition condition) {
        try {
            // Update clinical status to resolved
            CodeableConcept resolvedStatus = new CodeableConcept();
            resolvedStatus.addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                .setCode("resolved")
                .setDisplay("Resolved");
            condition.setClinicalStatus(resolvedStatus);

            // Add note about resolution
            Annotation resolutionNote = new Annotation();
            resolutionNote.setText("Automatically resolved by CDSS: condition no longer detected based on current vital signs.");
            resolutionNote.setTime(new Date());
            condition.addNote(resolutionNote);

            // Set abatement date
            condition.setAbatement(new DateTimeType(new Date()));

            // Update the condition
            IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
            conditionDao.update(condition);

            // Forward resolved condition to upstream server
            if (upstreamForwarder != null) {
                try {
                    upstreamForwarder.upsertConditions(Arrays.asList(condition));
                    logger.info("✓ Forwarded resolved Condition to upstream: {}", condition.getIdElement().getIdPart());
                } catch (Exception e) {
                    logger.warn("Failed to forward resolved condition upstream", e);
                }
            }

            logger.debug("Resolved condition: {}", condition.getIdElement().getIdPart());
        } catch (Exception e) {
            logger.error("Error resolving condition " + condition.getIdElement().getIdPart(), e);
        }
    }

    /**
     * Check if patient already has an active condition for this disease
     * Prevents duplicate conditions
     */
    private boolean hasExistingActiveCondition(String patientId, String libraryName) {
        List<Condition> activeConditions = getActiveConditions(patientId);
        return hasActiveConditionForLibrary(activeConditions, libraryName);
    }
}
