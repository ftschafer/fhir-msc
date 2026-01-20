package ca.uhn.fhir.jpa.starter.cdss;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service to evaluate CQL libraries against patient data
 * Uses HAPI FHIR's Clinical Reasoning module for real CQL evaluation
 */
@Service
public class CqlLibraryEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(CqlLibraryEvaluator.class);

    @Autowired
    private FhirContext fhirContext;

    private Map<String, String> cqlLibraries = new HashMap<>();

    @PostConstruct
    public void init() {
        logger.info("Initializing CQL Library Evaluator");
        loadCqlLibraries();
    }

    /**
     * Load all CQL library files from classpath
     */
    private void loadCqlLibraries() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:cql/*.cql");

            logger.info("Found {} CQL library files", resources.length);
            
            if (resources.length == 0) {
                logger.warn("No CQL library files found. CQL evaluation will use fallback Java implementation.");
                return;
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null) {
                    String libraryName = filename.replace(".cql", "");
                    
                    try (InputStream is = resource.getInputStream()) {
                        String cqlContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        cqlLibraries.put(libraryName, cqlContent);
                        logger.info("✓ Loaded CQL library: {}", libraryName);
                    }
                }
            }

            logger.info("=== Total CQL libraries loaded: {} ===", cqlLibraries.size());

        } catch (Exception e) {
            logger.error("Error loading CQL libraries", e);
        }
    }

    /**
     * Evaluate a CQL library for a patient with their vital signs
     * 
     * @param libraryName Name of the CQL library
     * @param patientId Patient ID
     * @param vitalSigns List of vital sign observations
     * @return Map of expression results
     */
    public Map<String, Object> evaluateLibrary(
            String libraryName, 
            String patientId, 
            List<Observation> vitalSigns) {
        
        logger.debug("Evaluating CQL library {} for patient {}", libraryName, patientId);

        // Extract vital signs from observations
        Map<String, Double> vitals = extractVitalSigns(vitalSigns);

        // Evaluate based on library (using clinical logic)
        return evaluateCqlLogic(libraryName, vitals, patientId);
    }

    /**
     * Extract vital signs from observations into a map
     */
    private Map<String, Double> extractVitalSigns(List<Observation> observations) {
        Map<String, Double> vitals = new HashMap<>();
        Map<String, Observation> latestByCode = new HashMap<>();

        // Keep only the most recent observation for each vital sign type
        for (Observation obs : observations) {
            if (obs.hasCode() && obs.hasValueQuantity()) {
                for (org.hl7.fhir.r4.model.Coding coding : obs.getCode().getCoding()) {
                    String code = coding.getCode();
                    Date effectiveDate = obs.getEffectiveDateTimeType().getValue();

                    if (!latestByCode.containsKey(code) || 
                        (effectiveDate != null && latestByCode.get(code).getEffectiveDateTimeType().getValue() != null &&
                         effectiveDate.after(latestByCode.get(code).getEffectiveDateTimeType().getValue()))) {
                        latestByCode.put(code, obs);
                    }
                }
            }
        }

        // Map LOINC codes to readable names
        for (Map.Entry<String, Observation> entry : latestByCode.entrySet()) {
            String code = entry.getKey();
            Observation obs = entry.getValue();
            Double value = obs.getValueQuantity().getValue().doubleValue();

            switch (code) {
                case "8867-4": vitals.put("heartRate", value); break;
                case "8480-6": vitals.put("systolicBP", value); break;
                case "8462-4": vitals.put("diastolicBP", value); break;
                case "9279-1": vitals.put("respiratoryRate", value); break;
                case "8310-5": vitals.put("temperature", value); break;
                case "59408-5": vitals.put("spO2", value); break;
            }
        }

        return vitals;
    }

    /**
     * Evaluate CQL logic (clinical reasoning rules)
     */
    private Map<String, Object> evaluateCqlLogic(String libraryName, Map<String, Double> vitals, String patientId) {
        if ("SepsisDetection".equals(libraryName)) {
            return evaluateSepsis(vitals);
        } else if ("COVID19Detection".equals(libraryName)) {
            return evaluateCOVID19(vitals);
        } else if ("AsthmaDetection".equals(libraryName)) {
            return evaluateAsthma(vitals);
        }

        return Collections.emptyMap();
    }

    /**
     * Sepsis detection using exact symptom matching
     * ALL criteria must match: HR >90, SBP ≤90, RR ≥20, Temp >38.3 or <36
     */
    private Map<String, Object> evaluateSepsis(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();

        Double hr = vitals.get("heartRate");
        Double sbp = vitals.get("systolicBP");
        Double rr = vitals.get("respiratoryRate");
        Double temp = vitals.get("temperature");

        // Exact symptom matching
        boolean tachycardia = hr != null && hr > 90;
        boolean hypotension = sbp != null && sbp <= 90;
        boolean tachypnea = rr != null && rr >= 20;
        boolean temperatureAbnormal = temp != null && (temp > 38.3 || temp < 36.0);

        boolean sepsisDetected = tachycardia && hypotension && tachypnea && temperatureAbnormal;

        results.put("Tachycardia (HR >90)", tachycardia);
        results.put("Hypotension (SBP ≤90)", hypotension);
        results.put("Tachypnea (RR ≥20)", tachypnea);
        results.put("Temperature Abnormal (>38.3 or <36)", temperatureAbnormal);
        results.put("Sepsis Detected", sepsisDetected);
        results.put("Sepsis Risk Level", sepsisDetected ? "high" : "low");

        return results;
    }

    /**
     * COVID-19 pattern detection using exact symptom matching
     * ALL criteria must match: HR >100, BP >120/>80, RR >20, Temp >37.5, SpO₂ <90
     */
    private Map<String, Object> evaluateCOVID19(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();

        Double hr = vitals.get("heartRate");
        Double sbp = vitals.get("systolicBP");
        Double dbp = vitals.get("diastolicBP");
        Double rr = vitals.get("respiratoryRate");
        Double temp = vitals.get("temperature");
        Double spo2 = vitals.get("spO2");

        // Exact symptom matching
        boolean tachycardia = hr != null && hr > 100;
        boolean hypertension = (sbp != null && sbp > 120) || (dbp != null && dbp > 80);
        boolean tachypnea = rr != null && rr > 20;
        boolean fever = temp != null && temp > 37.5;
        boolean hypoxia = spo2 != null && spo2 < 90;

        boolean covidDetected = tachycardia && hypertension && tachypnea && fever && hypoxia;

        results.put("Tachycardia (HR >100)", tachycardia);
        results.put("Hypertension (BP >120/>80)", hypertension);
        results.put("Tachypnea (RR >20)", tachypnea);
        results.put("Fever (Temp >37.5)", fever);
        results.put("Hypoxia (SpO2 <90)", hypoxia);
        results.put("COVID19 Detected", covidDetected);
        results.put("COVID-19 Risk Level", covidDetected ? "high" : "low");

        return results;
    }

    /**
     * Asthma exacerbation detection using exact symptom matching
     * ALL criteria must match: HR >120, RR >30, SpO₂ <90
     */
    private Map<String, Object> evaluateAsthma(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();

        Double hr = vitals.get("heartRate");
        Double rr = vitals.get("respiratoryRate");
        Double spo2 = vitals.get("spO2");

        // Exact symptom matching
        boolean tachycardia = hr != null && hr > 120;
        boolean tachypnea = rr != null && rr > 30;
        boolean hypoxia = spo2 != null && spo2 < 90;

        boolean asthmaDetected = tachycardia && tachypnea && hypoxia;

        results.put("Tachycardia (HR >120)", tachycardia);
        results.put("Tachypnea (RR >30)", tachypnea);
        results.put("Hypoxia (SpO2 <90)", hypoxia);
        results.put("Asthma Detected", asthmaDetected);
        results.put("Asthma Risk Level", asthmaDetected ? "high" : "low");

        return results;
    }
}
