package ca.uhn.fhir.jpa.starter.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MeasureReport;
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
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String NEIGHBORHOOD_EXTENSION_URL = "neighborhood";

    @Value("${location.neighborhood}")
    private String neighborhoodValue;
    
    private final IGenericClient client;

    public UpstreamForwarder(FhirContext ctx, @Value("${upstream.fhir.base-url:http://localhost:8091/fhir}") String upstreamUrl) {
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Upstream-Internal-Request", "true"));
    }


    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        ourLog.debug("Forwarding {} observation(s) upstream", observations.size());
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Observation o : observations) {
                Observation obsCopy = o.copy();

                // Preserve incoming location sub-extensions (e.g., block) and ensure neighborhood is present
                ensureNeighborhoodExtension(obsCopy);
                
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(obsCopy);

                String observationId = o.getIdElement() != null ? o.getIdElement().getIdPart() : null;
                if (observationId != null && !observationId.isBlank()) {
                    // Preserve source ID upstream to avoid collisions across layers
                    e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("Observation/" + observationId);
                } else {
                    // Fallback to conditional upsert only when ID is missing
                    String conditionalUrl;
                    if (o.hasIdentifier() && o.getIdentifierFirstRep().hasSystem() && o.getIdentifierFirstRep().hasValue()) {
                        conditionalUrl = "Observation?identifier=" + o.getIdentifierFirstRep().getSystem() + "|" + o.getIdentifierFirstRep().getValue();
                    } else {
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
                    e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl(conditionalUrl);
                }
            }
            client.transaction().withBundle(tx).execute();
        } catch (BaseServerResponseException e) {
            ourLog.error("ERROR forwarding observations: {}", e.getMessage());
        }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        ourLog.debug("Forwarding {} patient(s) upstream", patients.size());
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
                    } catch (BaseServerResponseException | IllegalArgumentException | IllegalStateException fallbackException) {
                        ourLog.warn("Failed forwarding patient {} after {} attempt(s): {}",
                                patientId,
                                attempt,
                                fallbackException.getMessage());
                        return false;
                    }
                }

                ourLog.warn("Failed forwarding patient after {} attempt(s): {}", attempt, e.getMessage());
                return false;
            } catch (IllegalArgumentException | IllegalStateException e) {
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
        // Preserve incoming location sub-extensions (e.g., block) and ensure neighborhood is present
        ensureNeighborhoodExtension(patient);
        
        Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
        Bundle.BundleEntryComponent entry = tx.addEntry().setResource(patient);

        if (forceIdBased) {
            String id = patient.getIdElement().getIdPart();
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient/" + id);
        } else {
            applyPatientUpsertRequest(entry, patient);
        }

        ourLog.info("Upstream patient upsert request: method={} url={} patientId={} identifier={}",
            entry.getRequest().getMethod(),
            entry.getRequest().getUrl(),
            patient.getIdElement() != null ? patient.getIdElement().getIdPart() : null,
            (patient.hasIdentifier() && patient.getIdentifierFirstRep().hasSystem() && patient.getIdentifierFirstRep().hasValue())
                ? patient.getIdentifierFirstRep().getSystem() + "|" + patient.getIdentifierFirstRep().getValue()
                : "none");

        client.transaction().withBundle(tx).execute();
        ourLog.info("Upstream patient upsert succeeded: patientId={}",
            patient.getIdElement() != null ? patient.getIdElement().getIdPart() : null);
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
        ourLog.debug("Forwarding {} condition(s) and {} linked observation(s) upstream",
            conditions.size(),
            observations == null ? 0 : observations.size());
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
                    // Create-only placeholder: do NOT overwrite existing upstream patients
                    patientEntry.setFullUrl("urn:uuid:patient-" + patientId);
                    patientEntry.getRequest()
                        .setMethod(Bundle.HTTPVerb.POST)
                        .setUrl("Patient")
                        .setIfNoneExist("_id=" + patientId);
                    // Minimal patient placeholder only if missing upstream
                    Patient placeholderPatient = new Patient();
                    placeholderPatient.setId(patientId);
                    placeholderPatient.setActive(true);
                    patientEntry.setResource(placeholderPatient);
                }
                try {
                    client.transaction().withBundle(patientTx).execute();
                    ourLog.debug("Ensured {} patients exist upstream", referencedPatients.size());
                } catch (BaseServerResponseException e) {
                    ourLog.warn("Some patients may not exist upstream: {}", e.getMessage());
                    // Continue anyway - the main transaction will fail with a clearer error if patients don't exist
                }
            }
            
            // Step 2: Post all Observations and get their new IDs
            if (observations != null && !observations.isEmpty()) {
                ourLog.info("Condition forwarding will upsert linked Observation IDs: {}",
                    observations.stream()
                        .map(o -> o.getIdElement() != null ? o.getIdElement().getIdPart() : null)
                        .toList());
                Bundle obsTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                
                for (Observation obs : observations) {
                    Observation obsCopy = obs.copy();

                    // Preserve incoming location sub-extensions (e.g., block) and ensure neighborhood is present
                    ensureNeighborhoodExtension(obsCopy);
                    
                    Bundle.BundleEntryComponent e = obsTx.addEntry().setResource(obsCopy);

                    String observationId = obsCopy.getIdElement() != null ? obsCopy.getIdElement().getIdPart() : null;
                    if (observationId != null && !observationId.isBlank()) {
                        // Preserve source observation IDs for condition-linked references
                        e.getRequest()
                            .setMethod(Bundle.HTTPVerb.PUT)
                            .setUrl("Observation/" + observationId);
                    } else {
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

                        e.getRequest()
                            .setMethod(Bundle.HTTPVerb.PUT)
                            .setUrl("Observation?" + (criteria.length() > 0 ? criteria.toString() : "identifier=temp"));
                    }
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

                // Preserve incoming location sub-extensions (e.g., block) and ensure neighborhood is present
                ensureNeighborhoodExtension(conditionCopy);
                
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
        }
    }

    /**
     * Forward MeasureReport resources upstream using stable ID-based PUT.
     * Used for neighborhood-level aggregated reports.
     */
    public void upsertMeasureReports(List<MeasureReport> reports) {
        if (reports == null || reports.isEmpty()) return;
        ourLog.debug("Forwarding {} MeasureReport(s) upstream", reports.size());
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (MeasureReport report : reports) {
                MeasureReport copy = report.copy();
                ensureNeighborhoodExtension(copy);

                Bundle.BundleEntryComponent entry = tx.addEntry().setResource(copy);
                String id = copy.getIdElement() != null ? copy.getIdElement().getIdPart() : null;
                if (id != null && !id.isBlank()) {
                    entry.getRequest()
                        .setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("MeasureReport/" + id);
                } else if (copy.hasIdentifier()
                        && copy.getIdentifierFirstRep().hasSystem()
                        && copy.getIdentifierFirstRep().hasValue()) {
                    entry.getRequest()
                        .setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("MeasureReport?identifier="
                            + copy.getIdentifierFirstRep().getSystem() + "|"
                            + copy.getIdentifierFirstRep().getValue());
                } else {
                    entry.getRequest()
                        .setMethod(Bundle.HTTPVerb.POST)
                        .setUrl("MeasureReport");
                }
            }
            client.transaction().withBundle(tx).execute();
            ourLog.info("Forwarded {} MeasureReport(s) upstream", reports.size());
        } catch (BaseServerResponseException e) {
            ourLog.error("ERROR forwarding MeasureReports upstream: {}", e.getMessage());
        }
    }

    private void ensureNeighborhoodExtension(DomainResource resource) {
        Extension existingLocation = resource.getExtensionByUrl(LOCATION_EXTENSION_URL);

        Extension rebuiltLocation = new Extension().setUrl(LOCATION_EXTENSION_URL);
        Extension existingNeighborhood = null;
        if (existingLocation != null && existingLocation.hasExtension()) {
            for (Extension nested : existingLocation.getExtension()) {
                if (NEIGHBORHOOD_EXTENSION_URL.equals(nested.getUrl())) {
                    if (nested.getValue() != null
                            && nested.getValue().primitiveValue() != null
                            && !nested.getValue().primitiveValue().isBlank()) {
                        existingNeighborhood = nested.copy();
                    }
                    continue;
                }
                rebuiltLocation.addExtension(nested.copy());
            }
        }

        if (existingNeighborhood != null) {
            rebuiltLocation.addExtension(existingNeighborhood);
        } else if (neighborhoodValue != null && !neighborhoodValue.isBlank()) {
            rebuiltLocation.addExtension(new Extension()
                    .setUrl(NEIGHBORHOOD_EXTENSION_URL)
                    .setValue(new org.hl7.fhir.r4.model.StringType(neighborhoodValue)));
        }

        resource.getExtension().removeIf(ext -> LOCATION_EXTENSION_URL.equals(ext.getUrl()));
        if (rebuiltLocation.hasExtension()) {
            resource.addExtension(rebuiltLocation);
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
