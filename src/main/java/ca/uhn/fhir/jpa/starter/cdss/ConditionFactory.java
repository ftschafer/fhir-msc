package ca.uhn.fhir.jpa.starter.cdss;

import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Factory class to create FHIR Condition resources from disease detection results
 * Links conditions to patients, observations, and locations
 */
@Component
public class ConditionFactory {

    private static final Logger logger = LoggerFactory.getLogger(ConditionFactory.class);

    // Extension URL for patient location (matches News2AggregationService pattern)
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";

    /**
     * Create a Condition resource from CQL evaluation results
     * 
     * @param patientId Patient ID
     * @param libraryName CQL library name that detected the condition
     * @param cqlResults Results from CQL evaluation
     * @param vitalSignObservations Observations that triggered the condition
     * @param encounter Current encounter (may be null)
     * @param location Patient location (may be null)
     * @return Condition resource ready to persist
     */
    public Condition createCondition(
            String patientId,
            String libraryName,
            Map<String, Object> cqlResults,
            List<Observation> vitalSignObservations,
            Encounter encounter,
            Location location) {

        Condition condition = new Condition();

        // Set subject (patient)
        condition.setSubject(new Reference("Patient/" + patientId));

        // Set code and display based on library
        setConditionCode(condition, libraryName, cqlResults);

        // Set clinical status (active)
        CodeableConcept clinicalStatus = new CodeableConcept();
        clinicalStatus.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
            .setCode("active")
            .setDisplay("Active");
        condition.setClinicalStatus(clinicalStatus);

        // Set verification status (provisional - auto-detected)
        CodeableConcept verificationStatus = new CodeableConcept();
        verificationStatus.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status")
            .setCode("provisional")
            .setDisplay("Provisional");
        condition.setVerificationStatus(verificationStatus);

        // Set severity
        setSeverity(condition, libraryName, cqlResults);

        // Set category (encounter-diagnosis)
        CodeableConcept category = new CodeableConcept();
        category.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/condition-category")
            .setCode("encounter-diagnosis")
            .setDisplay("Encounter Diagnosis");
        condition.addCategory(category);

        // Link to observations as evidence
        if (vitalSignObservations != null && !vitalSignObservations.isEmpty()) {
            Condition.ConditionEvidenceComponent evidence = new Condition.ConditionEvidenceComponent();
            for (Observation obs : vitalSignObservations) {
                evidence.addDetail(new Reference("Observation/" + obs.getIdElement().getIdPart()));
            }
            condition.addEvidence(evidence);
        }

        // Link to encounter
        if (encounter != null) {
            condition.setEncounter(new Reference("Encounter/" + encounter.getIdElement().getIdPart()));
        }

        // Add location as extension (matches News2AggregationService pattern)
        if (location != null) {
            Extension locationExtension = new Extension();
            locationExtension.setUrl(LOCATION_EXTENSION_URL);
            locationExtension.setValue(new Reference("Location/" + location.getIdElement().getIdPart()));
            condition.addExtension(locationExtension);

            logger.debug("Added location extension: {}", location.getName());
        }

        // Set recorded date
        condition.setRecordedDate(new Date());

        // Add note with CQL results and library name
        Annotation note = new Annotation();
        note.setText("Auto-detected by Clinical Decision Support System using " + libraryName + 
                     " CQL library. Results: " + formatResults(cqlResults));
        note.setTime(new Date());
        condition.addNote(note);

        return condition;
    }

    /**
     * Set the condition code based on library name
     */
    private void setConditionCode(Condition condition, String libraryName, Map<String, Object> results) {
        CodeableConcept code = new CodeableConcept();

        if ("NEWS2Scoring".equals(libraryName)) {
            Object score = results.get("NEWS2 Total Score");
            code.addCoding()
                .setSystem("http://snomed.info/sct")
                .setCode("443685005")
                .setDisplay("Clinical deterioration (NEWS2 Score: " + score + ")");
        } else if ("SepsisDetection".equals(libraryName)) {
            Object score = results.get("Sepsis Match Score");
            code.addCoding()
                .setSystem("http://snomed.info/sct")
                .setCode("91302008")
                .setDisplay("Suspected Sepsis (Match: " + score + "%)");
        } else if ("COVID19Detection".equals(libraryName)) {
            Object score = results.get("COVID-19 Match Score");
            code.addCoding()
                .setSystem("http://snomed.info/sct")
                .setCode("840539006")
                .setDisplay("Suspected COVID-19 (Match: " + score + "%)");
        } else if ("AsthmaDetection".equals(libraryName)) {
            Object severity = results.get("Asthma Severity");
            code.addCoding()
                .setSystem("http://snomed.info/sct")
                .setCode("195967001")
                .setDisplay("Asthma Exacerbation (" + severity + ")");
        } else {
            code.addCoding()
                .setSystem("http://snomed.info/sct")
                .setCode("404684003")
                .setDisplay("Clinical finding");
        }

        condition.setCode(code);
    }

    /**
     * Set severity based on CQL results
     */
    private void setSeverity(Condition condition, String libraryName, Map<String, Object> results) {
        String severityCode;
        String severityDisplay;

        if ("NEWS2Scoring".equals(libraryName)) {
            Object score = results.get("NEWS2 Total Score");
            if (score instanceof Integer) {
                int news2 = (Integer) score;
                if (news2 >= 7) {
                    severityCode = "24484000";
                    severityDisplay = "Severe";
                } else if (news2 >= 5) {
                    severityCode = "6736007";
                    severityDisplay = "Moderate";
                } else {
                    severityCode = "255604002";
                    severityDisplay = "Mild";
                }
            } else {
                severityCode = "6736007";
                severityDisplay = "Moderate";
            }
        } else if ("SepsisDetection".equals(libraryName)) {
            Boolean highRisk = (Boolean) results.get("High Risk Sepsis");
            if (highRisk != null && highRisk) {
                severityCode = "24484000";
                severityDisplay = "Severe";
            } else {
                severityCode = "6736007";
                severityDisplay = "Moderate";
            }
        } else if ("COVID19Detection".equals(libraryName)) {
            Boolean highRisk = (Boolean) results.get("High Risk COVID-19");
            if (highRisk != null && highRisk) {
                severityCode = "24484000";
                severityDisplay = "Severe";
            } else {
                severityCode = "6736007";
                severityDisplay = "Moderate";
            }
        } else if ("AsthmaDetection".equals(libraryName)) {
            Object severity = results.get("Asthma Severity");
            if ("severe".equals(severity)) {
                severityCode = "24484000";
                severityDisplay = "Severe";
            } else if ("moderate".equals(severity)) {
                severityCode = "6736007";
                severityDisplay = "Moderate";
            } else {
                severityCode = "255604002";
                severityDisplay = "Mild";
            }
        } else {
            severityCode = "6736007";
            severityDisplay = "Moderate";
        }

        CodeableConcept severityCC = new CodeableConcept();
        severityCC.addCoding()
            .setSystem("http://snomed.info/sct")
            .setCode(severityCode)
            .setDisplay(severityDisplay);
        condition.setSeverity(severityCC);
    }

    /**
     * Format CQL results for display
     */
    private String formatResults(Map<String, Object> results) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : results.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
