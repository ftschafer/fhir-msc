package ca.uhn.fhir.jpa.starter.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;

@Component
public class UpstreamForwarder {
    private static final Logger ourLog = LoggerFactory.getLogger(UpstreamForwarder.class);
    private static final int PATIENT_RETRY_ATTEMPTS = 6;

    @Value("${hapi.fhir.location.block:North}")
    private String blockValue;
    
    private final IGenericClient client;

    public UpstreamForwarder(FhirContext ctx, @Value("${upstream.fhir.base-url:http://18.218.25.8:8081/fhir}") String upstreamUrl) {
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
    }

    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Observation o : observations) {
                Observation obsCopy = o.copy();
                obsCopy.setId((String) null);
                
                // Remove any existing location extensions and add the block extension
                obsCopy.getExtension().removeIf(ext -> "http://patient-location".equals(ext.getUrl()));
                Extension locationExtension = new Extension();
                locationExtension.setUrl("http://patient-location");
                Extension blockExtension = new Extension();
                blockExtension.setUrl("block");
                blockExtension.setValue(new org.hl7.fhir.r4.model.StringType(blockValue));
                locationExtension.addExtension(blockExtension);
                obsCopy.addExtension(locationExtension);
                
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(obsCopy);
                
                // Prefer stable identifier when available to avoid duplicate aggregates
                String conditionalUrl;
                if (o.hasIdentifier() && o.getIdentifierFirstRep().hasSystem() && o.getIdentifierFirstRep().hasValue()) {
                    conditionalUrl = "Observation?identifier=" + o.getIdentifierFirstRep().getSystem() + "|" + o.getIdentifierFirstRep().getValue();
                } else {
                    // Build conditional criteria to update existing observations instead of creating duplicates
                    StringBuilder criteria = new StringBuilder();
                    if (o.hasSubject() && o.getSubject().hasReference()) {
                        criteria.append("subject=").append(o.getSubject().getReference());
                    }
                    if (o.hasCode() && o.getCode().hasCoding() && o.getCode().getCodingFirstRep().hasCode()) {
                        if (criteria.length() > 0) criteria.append("&");
                        criteria.append("code=").append(o.getCode().getCodingFirstRep().getCode());
                    }
                    if (o.hasEffectiveDateTimeType()) {
                        if (criteria.length() > 0) criteria.append("&");
                        criteria.append("date=").append(o.getEffectiveDateTimeType().getValueAsString());
                    }
                    conditionalUrl = "Observation?" + (criteria.length() > 0 ? criteria.toString() : "identifier=temp");
                }
                
                // Use PUT with conditional URL to update existing observations or create new ones
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl(conditionalUrl);
            }
            client.transaction().withBundle(tx).execute();
        } catch (BaseServerResponseException e) {
            ourLog.error("ERROR forwarding observations: {}", e.getMessage());
        } catch (Exception e) {
            ourLog.error("ERROR forwarding observations", e);
        }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        int total = patients.size();
        int successCount = 0;

        for (Patient patient : patients) {
            if (upsertPatientWithRetries(patient)) {
                successCount++;
            }
        }

        if (successCount < total) {
            ourLog.warn("Forwarded {}/{} patients upstream (some failed due to concurrent updates).", successCount, total);
        } else {
            ourLog.info("Forwarded {}/{} patients upstream.", successCount, total);
        }
    }

    private boolean upsertPatientWithRetries(Patient patient) {
        for (int attempt = 1; attempt <= PATIENT_RETRY_ATTEMPTS; attempt++) {
            try {
                upsertSinglePatient(patient, false);
                return true;
            } catch (BaseServerResponseException e) {
                if (isVersionConflict(e) && attempt < PATIENT_RETRY_ATTEMPTS) {
                    LockSupport.parkNanos(75L * attempt * 1_000_000L);
                    continue;
                }

                // Final fallback: ID-based upsert avoids conditional collisions
                String patientId = patient.getIdElement().getIdPart();
                if (patientId != null && !patientId.isBlank()) {
                    try {
                        upsertSinglePatient(patient, true);
                        return true;
                    } catch (Exception fallbackException) {
                        ourLog.warn("Failed forwarding patient {} after {} attempt(s): {}",
                                patientId,
                                attempt,
                                fallbackException.getMessage());
                        return false;
                    }
                }

                ourLog.warn("Failed forwarding patient after {} attempt(s): {}", attempt, e.getMessage());
                return false;
            } catch (Exception e) {
                String patientId = patient.getIdElement().getIdPart();
                ourLog.warn("Failed forwarding patient {} after {} attempt(s): {}",
                        patientId,
                        attempt,
                        e.getMessage());
                return false;
            }
        }

        return false;
    }

    private void upsertSinglePatient(Patient patient, boolean forceIdBased) {
        Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
        Bundle.BundleEntryComponent entry = tx.addEntry().setResource(patient);

        if (forceIdBased) {
            String id = patient.getIdElement().getIdPart();
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient/" + id);
        } else {
            applyPatientUpsertRequest(entry, patient);
        }

        client.transaction().withBundle(tx).execute();
    }

    private void applyPatientUpsertRequest(Bundle.BundleEntryComponent entry, Patient p) {
        String id = p.getIdElement().getIdPart();

        if (p.hasIdentifier() && p.getIdentifierFirstRep().hasSystem() && p.getIdentifierFirstRep().hasValue()) {
            String system = p.getIdentifierFirstRep().getSystem();
            String value = p.getIdentifierFirstRep().getValue();
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient?identifier=" + system + "|" + value);
        } else {
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl(id != null ? "Patient/" + id : "Patient");
        }
    }

    private boolean isVersionConflict(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof ResourceVersionConflictException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Forward Condition resources to upstream server
     * Used for auto-detected clinical conditions from CDSS
     */
    public void upsertConditions(List<Condition> conditions) {
        upsertConditionsWithObservations(conditions, null);
    }

    /**
     * Forward Condition resources along with their referenced Observations to upstream server
     * Ensures all referenced Observations and Patients exist before creating Conditions
     */
    public void upsertConditionsWithObservations(List<Condition> conditions, List<Observation> observations) {
        if (conditions == null || conditions.isEmpty()) return;
        try {
            Map<String, String> oldToNewObservationIds = new HashMap<>();
            
            // Step 0: Collect all referenced patients from conditions and observations
            Map<String, Patient> referencedPatients = new HashMap<>();
            
            // Get patients from conditions
            for (Condition c : conditions) {
                if (c.hasSubject() && c.getSubject().hasReference()) {
                    String patientRef = c.getSubject().getReference();
                    if (patientRef.startsWith("Patient/")) {
                        String patientId = patientRef.substring("Patient/".length());
                        if (!referencedPatients.containsKey(patientId)) {
                            referencedPatients.put(patientId, null); // Mark for later resolution
                        }
                    }
                }
            }
            
            // Get patients from observations
            if (observations != null) {
                for (Observation obs : observations) {
                    if (obs.hasSubject() && obs.getSubject().hasReference()) {
                        String patientRef = obs.getSubject().getReference();
                        if (patientRef.startsWith("Patient/")) {
                            String patientId = patientRef.substring("Patient/".length());
                            if (!referencedPatients.containsKey(patientId)) {
                                referencedPatients.put(patientId, null);
                            }
                        }
                    }
                }
            }
            
            ourLog.debug("Found {} unique patient references to ensure exist upstream", referencedPatients.size());
            
            // Step 1: Ensure all referenced patients exist upstream first
            if (!referencedPatients.isEmpty()) {
                Bundle patientTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                for (String patientId : referencedPatients.keySet()) {
                    Bundle.BundleEntryComponent patientEntry = patientTx.addEntry();
                    // Use conditional create/update to ensure patient exists
                    patientEntry.setFullUrl("Patient/" + patientId);
                    patientEntry.getRequest()
                        .setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("Patient/" + patientId)
                        .setIfNoneExist("_id=" + patientId);
                    // Create minimal patient resource as placeholder
                    Patient placeholderPatient = new Patient();
                    placeholderPatient.setId(patientId);
                    placeholderPatient.setActive(true);
                    patientEntry.setResource(placeholderPatient);
                }
                try {
                    client.transaction().withBundle(patientTx).execute();
                    ourLog.debug("Ensured {} patients exist upstream", referencedPatients.size());
                } catch (Exception e) {
                    ourLog.warn("Some patients may not exist upstream: {}", e.getMessage());
                    // Continue anyway - the main transaction will fail with a clearer error if patients don't exist
                }
            }
            
            // Step 2: Post all Observations and get their new IDs
            if (observations != null && !observations.isEmpty()) {
                Bundle obsTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                
                for (Observation obs : observations) {
                    Observation obsCopy = obs.copy();
                    obsCopy.setId((String) null);
                    
                    // Remove any existing location extensions and add the hardcoded one
                    obsCopy.getExtension().removeIf(ext -> "http://patient-location".equals(ext.getUrl()));
                    
                    Extension locationExtension = new Extension();
                    locationExtension.setUrl("http://patient-location");
                    Extension blockExtension = new Extension();
                    blockExtension.setUrl("block");
                    blockExtension.setValue(new org.hl7.fhir.r4.model.StringType(blockValue));
                    locationExtension.addExtension(blockExtension);
                    obsCopy.addExtension(locationExtension);
                    
                    Bundle.BundleEntryComponent e = obsTx.addEntry().setResource(obsCopy);
                    
                    // Use conditional create to avoid duplicates and numeric ID issues
                    // Build search criteria based on patient, code, and effective date
                    StringBuilder criteria = new StringBuilder();
                    if (obs.hasSubject() && obs.getSubject().hasReference()) {
                        criteria.append("subject=").append(obs.getSubject().getReference());
                    }
                    if (obs.hasCode() && obs.getCode().hasCoding() && obs.getCode().getCodingFirstRep().hasCode()) {
                        if (criteria.length() > 0) criteria.append("&");
                        criteria.append("code=").append(obs.getCode().getCodingFirstRep().getCode());
                    }
                    if (obs.hasEffectiveDateTimeType()) {
                        if (criteria.length() > 0) criteria.append("&");
                        criteria.append("date=").append(obs.getEffectiveDateTimeType().getValueAsString());
                    }
                    
                    // Use conditional UPDATE (PUT) to update existing observations or create new ones
                    e.getRequest()
                        .setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("Observation?" + (criteria.length() > 0 ? criteria.toString() : "identifier=temp"));
                }
                
                // Execute Observation transaction and map old IDs to new IDs
                Bundle obsResponse = client.transaction().withBundle(obsTx).execute();
                for (int i = 0; i < observations.size(); i++) {
                    String oldId = observations.get(i).getIdElement().getIdPart();
                    if (obsResponse.getEntry().size() > i && obsResponse.getEntry().get(i).hasResponse()) {
                        String location = obsResponse.getEntry().get(i).getResponse().getLocation();
                        if (location != null) {
                            // Extract new ID from location header (e.g., "Observation/123/_history/1")
                            String newId = extractIdFromLocation(location);
                            if (newId != null) {
                                oldToNewObservationIds.put(oldId, newId);
                            }
                        }
                    }
                }
                ourLog.info("Posted {} observations and mapped {} IDs for condition forwarding",
                    observations.size(),
                    oldToNewObservationIds.size());
            }
            
            // Step 3: Update Condition references and post Conditions
            Bundle conditionTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            
            for (Condition c : conditions) {
                // Create a copy and preserve deterministic ID for stable upstream upsert
                Condition conditionCopy = c.copy();
                
                // Update Observation references in evidence
                if (conditionCopy.hasEvidence()) {
                    for (Condition.ConditionEvidenceComponent evidence : conditionCopy.getEvidence()) {
                        for (Reference ref : evidence.getDetail()) {
                            if (ref.getReference() != null && ref.getReference().startsWith("Observation/")) {
                                String oldObsId = ref.getReference().substring("Observation/".length());
                                String newObsId = oldToNewObservationIds.get(oldObsId);
                                if (newObsId != null) {
                                    ref.setReference("Observation/" + newObsId);
                                }
                            }
                        }
                    }
                }
                
                Bundle.BundleEntryComponent e = conditionTx.addEntry().setResource(conditionCopy);

                String conditionId = conditionCopy.getIdElement().getIdPart();
                if (conditionId != null && !conditionId.isBlank()) {
                    // Stable ID-based upsert
                    e.getRequest()
                        .setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("Condition/" + conditionId);
                } else {
                    // Fallback conditional upsert
                    String patientRef = c.getSubject().getReference();
                    String code = c.getCode().getCodingFirstRep().getCode();
                    e.getRequest()
                        .setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("Condition?patient=" + patientRef + "&code=" + code + "&clinical-status=active");
                }
            }
            
            client.transaction().withBundle(conditionTx).execute();
            
        } catch (BaseServerResponseException e) {
            ourLog.error("ERROR forwarding conditions: {}", e.getMessage());
            throw new RuntimeException("Failed to forward conditions", e);
        } catch (Exception e) {
            ourLog.error("ERROR forwarding conditions", e);
            throw new RuntimeException("Failed to forward conditions", e);
        }
    }
    
    /**
     * Extract resource ID from location header
     * Example: "Observation/123/_history/1" -> "123"
     */
    private String extractIdFromLocation(String location) {
        if (location == null) return null;
        // Location format: "ResourceType/id/_history/version" or "ResourceType/id"
        String[] parts = location.split("/");
        if (parts.length >= 2) {
            return parts[1]; // Return the ID part
        }
        return null;
    }
}