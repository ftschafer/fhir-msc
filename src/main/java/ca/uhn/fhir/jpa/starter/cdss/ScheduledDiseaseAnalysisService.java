package ca.uhn.fhir.jpa.starter.cdss;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    // CQL libraries to evaluate
    private static final String[] CQL_LIBRARIES = {
        "NEWS2Scoring",
        "SepsisDetection",
        "COVID19Detection",
        "AsthmaDetection"
    };

    // Run every 5 minutes (300000 ms)
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
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

            // Analyze each patient
            for (IBaseResource resource : patients) {
                if (resource instanceof Patient) {
                    Patient patient = (Patient) resource;
                    String patientId = patient.getIdElement().getIdPart();
                    
                    try {
                        int created = analyzePatient(patientId);
                        conditionsCreated += created;
                        patientsAnalyzed++;
                    } catch (Exception e) {
                        logger.error("Error analyzing patient " + patientId, e);
                    }
                }
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
     * @return Number of conditions created
     */
    private int analyzePatient(String patientId) {
        logger.debug("Analyzing patient: {}", patientId);

        int conditionsCreated = 0;

        // Get recent vital sign observations (last 24 hours)
        List<Observation> vitalSigns = getRecentVitalSigns(patientId);
        
        if (vitalSigns.isEmpty()) {
            logger.debug("No vital signs found for patient {}, skipping", patientId);
            return 0;
        }

        // Get patient's current encounter and location
        Encounter currentEncounter = getCurrentEncounter(patientId);
        Location patientLocation = getPatientLocation(currentEncounter);

        // Evaluate each CQL library
        for (String libraryName : CQL_LIBRARIES) {
            try {
                // Evaluate CQL library for this patient
                Map<String, Object> results = cqlLibraryEvaluator.evaluateLibrary(
                    libraryName, 
                    patientId, 
                    vitalSigns
                );

                // Check if condition should be created
                if (shouldCreateCondition(libraryName, results)) {
                    // Check for duplicate before creating
                    if (!hasExistingActiveCondition(patientId, libraryName)) {
                        // Create condition
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
                        conditionDao.create(condition);
                        
                        conditionsCreated++;
                        logger.info("✓ Created Condition for patient {} using {}", patientId, libraryName);
                    } else {
                        logger.debug("Skipped duplicate condition for patient {} using {}", patientId, libraryName);
                    }
                }

            } catch (Exception e) {
                logger.error("Error evaluating " + libraryName + " for patient " + patientId, e);
            }
        }

        return conditionsCreated;
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
        
        return results.getAllResources().stream()
            .filter(r -> r instanceof Observation)
            .map(r -> (Observation) r)
            .collect(Collectors.toList());
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

        // NEWS2: Create if score >= 3
        if ("NEWS2Scoring".equals(libraryName)) {
            Object score = results.get("NEWS2 Total Score");
            return score instanceof Integer && (Integer) score >= 3;
        }

        // Sepsis: Create if match score >= 60
        if ("SepsisDetection".equals(libraryName)) {
            Object score = results.get("Sepsis Match Score");
            return score instanceof Integer && (Integer) score >= 60;
        }

        // COVID-19: Create if match score >= 60
        if ("COVID19Detection".equals(libraryName)) {
            Object score = results.get("COVID-19 Match Score");
            return score instanceof Integer && (Integer) score >= 60;
        }

        // Asthma: Create if match score >= 75
        if ("AsthmaDetection".equals(libraryName)) {
            Object score = results.get("Asthma Match Score");
            return score instanceof Integer && (Integer) score >= 75;
        }

        return false;
    }

    /**
     * Check if patient already has an active condition for this disease
     * Prevents duplicate conditions
     */
    private boolean hasExistingActiveCondition(String patientId, String libraryName) {
        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);

        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.add("patient", new ReferenceParam(patientId));
        searchMap.add("clinical-status", new TokenParam("http://terminology.hl7.org/CodeSystem/condition-clinical", "active"));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = conditionDao.search(searchMap);
        List<IBaseResource> conditions = results.getAllResources();

        // Check if any condition has a note mentioning this library
        for (IBaseResource resource : conditions) {
            if (resource instanceof Condition) {
                Condition condition = (Condition) resource;
                
                // Check notes for library name
                if (condition.hasNote()) {
                    for (Annotation note : condition.getNote()) {
                        if (note.hasText() && note.getText().contains(libraryName)) {
                            // Check if created recently (within last 24 hours)
                            if (condition.hasRecordedDate()) {
                                long hoursSinceCreated = 
                                    (System.currentTimeMillis() - condition.getRecordedDate().getTime()) / (1000 * 60 * 60);
                                if (hoursSinceCreated < 24) {
                                    return true; // Duplicate found
                                }
                            } else {
                                return true; // No date, assume recent
                            }
                        }
                    }
                }
            }
        }

        return false;
    }
}
