package ca.uhn.fhir.jpa.starter.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;

@Component
public class UpstreamForwarder {
    private final IGenericClient client;

    @Value("${hapi.fhir.location.block:North}")
    private String blockValue;

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
                String conditionalUrl = null;
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
        } catch (Exception e) {
            System.err.println("ERROR forwarding observations: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Patient p : patients) {
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(p);
                String id = p.getIdElement().getIdPart();
                // Use conditional PUT to update existing or create new patient
                // Try to match by identifier first, then by ID
                if (p.hasIdentifier() && p.getIdentifierFirstRep().hasSystem() && p.getIdentifierFirstRep().hasValue()) {
                    String system = p.getIdentifierFirstRep().getSystem();
                    String value = p.getIdentifierFirstRep().getValue();
                    e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl("Patient?identifier=" + system + "|" + value);
                } else {
                    // Fall back to ID-based update, but use conditional to avoid errors if patient doesn't exist
                    e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                        .setUrl(id != null ? "Patient/" + id : "Patient");
                }
            }
            client.transaction().withBundle(tx).execute();
        } catch (Exception e) {
            System.err.println("ERROR forwarding patients: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to forward patients", e);
        }
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
            
            System.out.println("Found " + referencedPatients.size() + " unique patient references to ensure exist upstream");
            
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
                    System.out.println("Ensured " + referencedPatients.size() + " patients exist upstream");
                } catch (Exception e) {
                    System.err.println("Warning: Some patients may not exist upstream: " + e.getMessage());
                    // Continue anyway - the main transaction will fail with a clearer error if patients don't exist
                }
            }
            
            // Step 2: Post all Observations and get their new IDs
            if (observations != null && !observations.isEmpty()) {
                Bundle obsTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                
                for (Observation obs : observations) {
                    String oldId = obs.getIdElement().getIdPart();
                    
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
                System.out.println("Posted " + observations.size() + " Observations, mapped " + oldToNewObservationIds.size() + " IDs");
            }
            
            // Step 3: Update Condition references and post Conditions
            Bundle conditionTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            
            for (Condition c : conditions) {
                // Create a copy without the ID to avoid conflicts
                Condition conditionCopy = c.copy();
                conditionCopy.setId((String) null);
                
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
                
                // Use conditional update (PUT) to update existing or create new
                String patientRef = c.getSubject().getReference();
                String code = c.getCode().getCodingFirstRep().getCode();
                
                e.getRequest()
                    .setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Condition?patient=" + patientRef + "&code=" + code + "&clinical-status=active");
            }
            
            client.transaction().withBundle(conditionTx).execute();
            
        } catch (Exception e) {
            System.err.println("ERROR forwarding conditions: " + e.getMessage());
            e.printStackTrace();
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