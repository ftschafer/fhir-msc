package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UpstreamForwarder {
    private static final Logger ourLog = LoggerFactory.getLogger(UpstreamForwarder.class);

    private final IGenericClient client;
    private final String city;

    private final Map<String, Object> patientLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> observationLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> conditionLocks = new ConcurrentHashMap<>();

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String CITY_URL = "city";
    private static final String BLOCK_URL = "block";
    private static final int MAX_RETRIES = 3;

    public UpstreamForwarder(
            FhirContext ctx,
            @Value("${upstream.fhir.base-url:http://localhost:8083/fhir}") String upstreamUrl,
            @Value("${location.city}") String city
    ) {
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
        this.city = city;
    }

    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Map<String, Observation> byId = new HashMap<>();
            for (Observation o : observations) {
                ensureCityExtension(o);

                String block = extractBlock(o);
                if (block == null || block.isBlank()) continue;

                String resourceId = normalizeIdPart(o.getIdElement() != null ? o.getIdElement().getIdPart() : null);
                if (resourceId == null || resourceId.isBlank()) continue;

                String scopedId = buildScopedId(city, block, resourceId);
                upsertIdentifier(o, "urn:observation:city-block-scope", scopedId);
                byId.put(resourceId, o);
            }

            for (Map.Entry<String, Observation> entry : byId.entrySet()) {
                String resourceId = entry.getKey();
                Observation observation = entry.getValue();
                Object lock = observationLocks.computeIfAbsent(resourceId, k -> new Object());
                synchronized (lock) {
                    executeObservationUpsertWithRetry(resourceId, observation);
                }
            }
        } catch (Exception ex) {
            ourLog.warn("Failed to forward observations upstream", ex);
        }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        try {
            Map<String, Patient> byId = new HashMap<>();
            for (Patient p : patients) {
                ensureCityExtension(p);

                String block = extractBlock(p);
                if (block == null || block.isBlank()) continue;

                String resourceId = normalizeIdPart(p.getIdElement() != null ? p.getIdElement().getIdPart() : null);
                if (resourceId == null || resourceId.isBlank()) continue;

                String scopedId = buildScopedId(city, block, resourceId);
                upsertIdentifier(p, "urn:patient:city-block-scope", scopedId);
                byId.put(resourceId, p);
            }

            for (Map.Entry<String, Patient> entry : byId.entrySet()) {
                String resourceId = entry.getKey();
                Patient patient = entry.getValue();
                Object lock = patientLocks.computeIfAbsent(resourceId, k -> new Object());
                synchronized (lock) {
                    executePatientUpsertWithRetry(resourceId, patient);
                }
            }
        } catch (Exception ex) {
            ourLog.warn("Failed to forward patients upstream", ex);
        }
    }

    public void upsertConditions(List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) return;
        try {
            Map<String, Condition> byId = new HashMap<>();
            for (Condition c : conditions) {
                String resourceId = normalizeIdPart(c.getIdElement() != null ? c.getIdElement().getIdPart() : null);
                if (resourceId == null || resourceId.isBlank()) continue;
                byId.put(resourceId, c);
            }

            for (Map.Entry<String, Condition> entry : byId.entrySet()) {
                String resourceId = entry.getKey();
                Condition condition = entry.getValue();
                Object lock = conditionLocks.computeIfAbsent(resourceId, k -> new Object());
                synchronized (lock) {
                    executeConditionUpsertWithRetry(resourceId, condition);
                }
            }
        } catch (Exception ex) {
            ourLog.warn("Failed to forward conditions upstream", ex);
        }
    }

    private void executePatientUpsertWithRetry(String resourceId, Patient patient) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                Patient outbound = (Patient) patient.copy();
                outbound.setId("Patient/" + resourceId);
                Bundle.BundleEntryComponent entry = tx.addEntry().setResource(outbound);
                entry.getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Patient/" + resourceId);
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
                Observation outbound = (Observation) observation.copy();
                outbound.setId("Observation/" + resourceId);
                Bundle.BundleEntryComponent entry = tx.addEntry().setResource(outbound);
                entry.getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Observation/" + resourceId);
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

    private void executeConditionUpsertWithRetry(String resourceId, Condition condition) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                Condition outbound = (Condition) condition.copy();
                outbound.setId("Condition/" + resourceId);
                Bundle.BundleEntryComponent entry = tx.addEntry().setResource(outbound);
                entry.getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Condition/" + resourceId);
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

    private String buildScopedId(String city, String block, String resourceId) {
        return city + "-" + block + "-" + resourceId;
    }

    private String normalizeIdPart(String idPart) {
        if (idPart == null) return null;
        String id = idPart.trim();
        if (id.isEmpty()) return null;
        int historyMarker = id.indexOf("/_history/");
        if (historyMarker > 0) {
            id = id.substring(0, historyMarker);
        }
        return id;
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
                    .findFirst()
                    .orElse(null);
            if (blockExt != null && blockExt.getValue() != null) {
                return blockExt.getValue().primitiveValue();
            }
        }
        return null;
    }

    private void ensureCityExtension(org.hl7.fhir.r4.model.Resource resource) {
        org.hl7.fhir.r4.model.Extension locExt = null;
        if (resource instanceof Patient) {
            locExt = ((Patient) resource).getExtensionByUrl(LOCATION_EXTENSION_URL);
        } else if (resource instanceof Observation) {
            locExt = ((Observation) resource).getExtensionByUrl(LOCATION_EXTENSION_URL);
        }
        if (locExt == null) return;

        boolean hasBlock = locExt.getExtension().stream()
                .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);
        if (!hasBlock) return;

        boolean hasCity = locExt.getExtension().stream()
                .anyMatch(e -> CITY_URL.equals(e.getUrl()) && e.getValue() != null);
        if (!hasCity) {
            locExt.addExtension(new org.hl7.fhir.r4.model.Extension()
                    .setUrl(CITY_URL)
                    .setValue(new org.hl7.fhir.r4.model.StringType(city)));
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
