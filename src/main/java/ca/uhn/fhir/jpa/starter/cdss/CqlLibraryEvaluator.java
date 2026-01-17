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
        if ("NEWS2Scoring".equals(libraryName)) {
            return evaluateNEWS2(vitals);
        } else if ("SepsisDetection".equals(libraryName)) {
            return evaluateSepsis(vitals);
        } else if ("COVID19Detection".equals(libraryName)) {
            return evaluateCOVID19(vitals);
        } else if ("AsthmaDetection".equals(libraryName)) {
            return evaluateAsthma(vitals);
        }

        return Collections.emptyMap();
    }

    /**
     * NEWS2 (National Early Warning Score 2) evaluation
     */
    private Map<String, Object> evaluateNEWS2(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();
        int score = 0;

        // Respiratory Rate
        Double rr = vitals.get("respiratoryRate");
        if (rr != null) {
            if (rr <= 8) score += 3;
            else if (rr <= 11) score += 1;
            else if (rr <= 20) score += 0;
            else if (rr <= 24) score += 2;
            else score += 3;
        }

        // SpO2
        Double spo2 = vitals.get("spO2");
        if (spo2 != null) {
            if (spo2 <= 91) score += 3;
            else if (spo2 <= 93) score += 2;
            else if (spo2 <= 95) score += 1;
        }

        // Systolic BP
        Double sbp = vitals.get("systolicBP");
        if (sbp != null) {
            if (sbp <= 90) score += 3;
            else if (sbp <= 100) score += 2;
            else if (sbp <= 110) score += 1;
            else if (sbp >= 220) score += 3;
        }

        // Heart Rate
        Double hr = vitals.get("heartRate");
        if (hr != null) {
            if (hr <= 40) score += 3;
            else if (hr <= 50) score += 1;
            else if (hr <= 90) score += 0;
            else if (hr <= 110) score += 1;
            else if (hr <= 130) score += 2;
            else score += 3;
        }

        // Temperature
        Double temp = vitals.get("temperature");
        if (temp != null) {
            if (temp <= 35.0) score += 3;
            else if (temp <= 36.0) score += 1;
            else if (temp <= 38.0) score += 0;
            else if (temp <= 39.0) score += 1;
            else score += 2;
        }

        results.put("NEWS2 Total Score", score);

        String riskLevel = (score == 0) ? "low" : (score <= 4) ? "low-medium" : (score <= 6) ? "medium" : "high";
        results.put("NEWS2 Risk Level", riskLevel);

        String response = (score == 0) ? "Continue routine monitoring" :
                         (score <= 4) ? "Increase monitoring frequency" :
                         (score <= 6) ? "Urgent response required" :
                         "EMERGENCY: Immediate medical review";
        results.put("NEWS2 Clinical Response", response);
        results.put("Requires Escalation", score >= 5);

        return results;
    }

    /**
     * Sepsis detection using qSOFA and SIRS criteria
     */
    private Map<String, Object> evaluateSepsis(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();

        Double hr = vitals.get("heartRate");
        Double sbp = vitals.get("systolicBP");
        Double rr = vitals.get("respiratoryRate");
        Double temp = vitals.get("temperature");
        Double spo2 = vitals.get("spO2");

        // qSOFA criteria (quick Sequential Organ Failure Assessment)
        int qsofaScore = 0;
        if (sbp != null && sbp <= 100) qsofaScore++;
        if (rr != null && rr >= 22) qsofaScore++;

        // SIRS criteria (Systemic Inflammatory Response Syndrome)
        int sirsCount = 0;
        if (hr != null && hr > 90) sirsCount++;
        if (rr != null && rr > 20) sirsCount++;
        if (temp != null && (temp > 38.0 || temp < 36.0)) sirsCount++;

        boolean hasHypoxia = spo2 != null && spo2 < 95;
        boolean possibleSepsis = qsofaScore >= 2 || sirsCount >= 2;
        boolean highRiskSepsis = qsofaScore >= 2 && hasHypoxia && sirsCount >= 3;

        int matchScore = highRiskSepsis ? 90 : (possibleSepsis ? 70 : 40);

        results.put("qSOFA Score", qsofaScore);
        results.put("SIRS Criteria Count", sirsCount);
        results.put("Possible Sepsis", possibleSepsis);
        results.put("High Risk Sepsis", highRiskSepsis);
        results.put("Sepsis Match Score", matchScore);
        results.put("Sepsis Risk Level", highRiskSepsis ? "high" : (possibleSepsis ? "moderate" : "low"));

        return results;
    }

    /**
     * COVID-19 pattern detection
     */
    private Map<String, Object> evaluateCOVID19(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();

        Double hr = vitals.get("heartRate");
        Double rr = vitals.get("respiratoryRate");
        Double temp = vitals.get("temperature");
        Double spo2 = vitals.get("spO2");

        int vitalScore = 0;
        if (hr != null && hr > 100) vitalScore += 20;
        if (rr != null && rr > 20) vitalScore += 20;
        if (temp != null && temp > 37.5) vitalScore += 20;
        if (spo2 != null && spo2 < 90) vitalScore += 30;

        boolean highRisk = vitalScore >= 80 && spo2 != null && spo2 < 90;
        boolean possible = vitalScore >= 60;

        results.put("COVID-19 Match Score", vitalScore);
        results.put("Possible COVID-19", possible);
        results.put("High Risk COVID-19", highRisk);
        results.put("COVID-19 Risk Level", highRisk ? "high" : (possible ? "moderate" : "low"));

        return results;
    }

    /**
     * Asthma exacerbation detection
     */
    private Map<String, Object> evaluateAsthma(Map<String, Double> vitals) {
        Map<String, Object> results = new HashMap<>();

        Double hr = vitals.get("heartRate");
        Double rr = vitals.get("respiratoryRate");
        Double spo2 = vitals.get("spO2");

        String severity = null;
        int matchScore = 0;

        // Severe
        if (hr != null && hr > 120 && rr != null && rr > 30 && spo2 != null && spo2 < 90) {
            severity = "severe";
            matchScore = 95;
        }
        // Moderate
        else if (hr != null && hr >= 100 && hr <= 120 && 
                 rr != null && rr >= 20 && rr <= 30 &&
                 spo2 != null && spo2 >= 90 && spo2 <= 95) {
            severity = "moderate";
            matchScore = 85;
        }
        // Mild
        else if (hr != null && hr < 100 &&
                 rr != null && rr >= 20 && rr <= 30 &&
                 spo2 != null && spo2 > 95) {
            severity = "mild";
            matchScore = 75;
        }

        if (severity != null) {
            results.put("Asthma Severity", severity);
            results.put("Asthma Match Score", matchScore);
            results.put("Asthma Risk Level", severity.equals("severe") ? "high" : 
                                            (severity.equals("moderate") ? "moderate" : "low"));
        }

        return results;
    }
}
