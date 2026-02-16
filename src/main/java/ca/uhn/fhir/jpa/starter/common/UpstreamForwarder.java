package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UpstreamForwarder {
    private final IGenericClient client;
    private final String neighborhood;
    private final Map<String, Object> patientLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> observationLocks = new ConcurrentHashMap<>();
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String NEIGH_URL = "neighborhood";
    private static final String BLOCK_URL = "block";
    private static final int MAX_RETRIES = 3;

    public UpstreamForwarder(FhirContext ctx, 
                           @Value("${upstream.fhir.base-url:http://localhost:8082/fhir}") String upstreamUrl,
                           @Value("${location.neighborhood:center}") String neighborhood) {
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
        this.neighborhood = neighborhood;
    }

    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Map<String, Observation> byId = new HashMap<>();
            for (Observation o : observations) {
                ensureNeighborhoodExtension(o);
                // Extract block from observation location extension
                String block = extractBlock(o);
                if (block == null || block.isBlank()) continue;
                String resourceId = o.getIdElement() != null ? o.getIdElement().getIdPart() : null;
                if (resourceId == null || resourceId.isBlank()) continue;
                String scopedId = buildScopedId(neighborhood, block, resourceId);
                
                // Add identifier for block-scoped tracking
                upsertIdentifier(o, "urn:observation:neigh-block-scope", scopedId);

                // Keep only the latest payload per resource ID in this call
                byId.put(resourceId, o);
            }

            for (Map.Entry<String, Observation> entry : byId.entrySet()) {
                String resourceId = entry.getKey();
                Observation o = entry.getValue();
                Object lock = observationLocks.computeIfAbsent(resourceId, k -> new Object());
                synchronized (lock) {
                    executeObservationUpsertWithRetry(resourceId, o);
                }
            }
        } catch (Exception ignored) { }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        try {
            Map<String, Patient> byId = new HashMap<>();
            for (Patient p : patients) {
                ensureNeighborhoodExtension(p);
                // Extract block from patient location extension
                String block = extractBlock(p);
                if (block == null || block.isBlank()) continue;
                String resourceId = p.getIdElement() != null ? p.getIdElement().getIdPart() : null;
                if (resourceId == null || resourceId.isBlank()) continue;
                String scopedId = buildScopedId(neighborhood, block, resourceId);
                
                // Add identifier for block-scoped tracking
                upsertIdentifier(p, "urn:patient:neigh-block-scope", scopedId);

                // Keep only the latest payload per resource ID in this call
                byId.put(resourceId, p);
            }

            for (Map.Entry<String, Patient> entry : byId.entrySet()) {
                String resourceId = entry.getKey();
                Patient p = entry.getValue();
                Object lock = patientLocks.computeIfAbsent(resourceId, k -> new Object());
                synchronized (lock) {
                    executePatientUpsertWithRetry(resourceId, p);
                }
            }
        } catch (Exception ignored) { }
    }

    private void executePatientUpsertWithRetry(String resourceId, Patient patient) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                Bundle.BundleEntryComponent e = tx.addEntry().setResource((Patient) patient.copy());
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient/" + resourceId);
                client.transaction().withBundle(tx).execute();
                return;
            } catch (ResourceVersionConflictException ex) {
                if (attempts >= MAX_RETRIES) return;
                sleepBackoff(attempts);
            } catch (Exception ex) {
                return;
            }
        }
    }

    private void executeObservationUpsertWithRetry(String resourceId, Observation observation) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                Bundle.BundleEntryComponent e = tx.addEntry().setResource((Observation) observation.copy());
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Observation/" + resourceId);
                client.transaction().withBundle(tx).execute();
                return;
            } catch (ResourceVersionConflictException ex) {
                if (attempts >= MAX_RETRIES) return;
                sleepBackoff(attempts);
            } catch (Exception ex) {
                return;
            }
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            long delayMs = 50L * attempt;
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    
    private String buildScopedId(String neighborhood, String block, String resourceId) {
        return neighborhood + "-" + block + "-" + resourceId;
    }
    
    private String extractBlock(org.hl7.fhir.r4.model.Resource resource) {
        org.hl7.fhir.r4.model.Extension locExt = null;
        if (resource instanceof Patient) {
            locExt = ((Patient) resource).getExtensionByUrl(LOCATION_EXTENSION_URL);
        } else if (resource instanceof Observation) {
            locExt = ((Observation) resource).getExtensionByUrl(LOCATION_EXTENSION_URL);
        }
        
        if (locExt != null) {
            org.hl7.fhir.r4.model.Extension blockExt = locExt.getExtension().stream()
                .filter(e -> BLOCK_URL.equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() != null) {
                return blockExt.getValue().primitiveValue();
            }
        }
        return null;
    }

    private void ensureNeighborhoodExtension(org.hl7.fhir.r4.model.Resource resource) {
        org.hl7.fhir.r4.model.Extension locExt = null;
        if (resource instanceof Patient) {
            locExt = ((Patient) resource).getExtensionByUrl(LOCATION_EXTENSION_URL);
        } else if (resource instanceof Observation) {
            locExt = ((Observation) resource).getExtensionByUrl(LOCATION_EXTENSION_URL);
        }
        if (locExt == null) {
            return;
        }

        boolean hasBlock = locExt.getExtension().stream()
            .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);
        if (!hasBlock) {
            return;
        }

        boolean hasNeighborhood = locExt.getExtension().stream()
            .anyMatch(e -> NEIGH_URL.equals(e.getUrl()) && e.getValue() != null);
        if (!hasNeighborhood) {
            locExt.addExtension(new org.hl7.fhir.r4.model.Extension()
                .setUrl(NEIGH_URL)
                .setValue(new org.hl7.fhir.r4.model.StringType(neighborhood)));
        }
    }

    private void upsertIdentifier(org.hl7.fhir.r4.model.DomainResource resource, String system, String value) {
        List<Identifier> identifiers = null;
        if (resource instanceof Patient) {
            identifiers = ((Patient) resource).getIdentifier();
        } else if (resource instanceof Observation) {
            identifiers = ((Observation) resource).getIdentifier();
        }
        
        if (identifiers == null) return;
        
        Identifier existing = identifiers.stream()
            .filter(i -> system.equals(i.getSystem()))
            .findFirst()
            .orElse(null);
        if (existing != null) {
            existing.setValue(value);
        } else {
            Identifier newId = new Identifier().setSystem(system).setValue(value);
            if (resource instanceof Patient) {
                ((Patient) resource).addIdentifier(newId);
            } else if (resource instanceof Observation) {
                ((Observation) resource).addIdentifier(newId);
            }
        }
    }
}
