package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UpstreamForwarder {
    private static final Logger ourLog = LoggerFactory.getLogger(UpstreamForwarder.class);

    private final FhirContext fhirContext;
    private final IGenericClient client;
    private final IFhirResourceDao<Patient> patientDao;
    private final String city;

    private final Map<String, Object> patientLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> observationLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> conditionLocks = new ConcurrentHashMap<>();

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String CITY_URL = "city";
    private static final String NEIGH_URL = "neighborhood";
    private static final String BLOCK_URL = "block";
    private static final String PATIENT_CITY_BLOCK_SCOPE = "urn:patient:city-block-scope";
    private static final String OBS_CITY_BLOCK_SCOPE = "urn:observation:city-block-scope";
    private static final String COND_CITY_BLOCK_SCOPE = "urn:condition:city-block-scope";
    private static final String PATIENT_NEIGH_BLOCK_SCOPE = "urn:patient:neigh-block-scope";
    private static final String OBS_NEIGH_BLOCK_SCOPE = "urn:observation:neigh-block-scope";
    private static final String COND_NEIGH_BLOCK_SCOPE = "urn:condition:neigh-block-scope";
    private static final int MAX_RETRIES = 3;

    public UpstreamForwarder(
            FhirContext ctx,
            DaoRegistry daoRegistry,
            @Value("${upstream.fhir.base-url:http://localhost:8083/fhir}") String upstreamUrl,
            @Value("${location.city}") String city
    ) {
        this.fhirContext = ctx;
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
        this.patientDao = daoRegistry.getResourceDao(Patient.class);
        this.city = city;
    }

    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Map<String, Observation> byId = new HashMap<>();
            for (Observation o : observations) {
                ensureLocationCompleteness(o, OBS_CITY_BLOCK_SCOPE, OBS_NEIGH_BLOCK_SCOPE);

                String block = extractBlock(o);
                if (block == null || block.isBlank()) continue;

                String resourceId = normalizeIdPart(o.getIdElement() != null ? o.getIdElement().getIdPart() : null);
                if (resourceId == null || resourceId.isBlank()) continue;

                String scopedId = buildScopedId(city, block, resourceId);
                upsertIdentifier(o, OBS_CITY_BLOCK_SCOPE, scopedId);
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
                ensureLocationCompleteness(p, PATIENT_CITY_BLOCK_SCOPE, PATIENT_NEIGH_BLOCK_SCOPE);

                String block = extractBlock(p);
                if (block == null || block.isBlank()) continue;

                String resourceId = normalizeIdPart(p.getIdElement() != null ? p.getIdElement().getIdPart() : null);
                if (resourceId == null || resourceId.isBlank()) continue;

                String scopedId = buildScopedId(city, block, resourceId);
                upsertIdentifier(p, PATIENT_CITY_BLOCK_SCOPE, scopedId);
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
                ensureLocationCompleteness(c, COND_CITY_BLOCK_SCOPE, COND_NEIGH_BLOCK_SCOPE);

                String block = extractBlock(c);
                if (block == null || block.isBlank()) continue;

                String resourceId = normalizeIdPart(c.getIdElement() != null ? c.getIdElement().getIdPart() : null);
                if (resourceId == null || resourceId.isBlank()) continue;

                String scopedId = buildScopedId(city, block, resourceId);
                upsertIdentifier(c, COND_CITY_BLOCK_SCOPE, scopedId);
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
        List<Extension> locationExtensions = getLocationExtensions(resource);
        for (Extension locExt : locationExtensions) {
            if (locExt == null) continue;
            Extension blockExt = locExt.getExtension().stream()
                    .filter(e -> urlMatches(e.getUrl(), BLOCK_URL) && e.getValue() != null)
                    .findFirst()
                    .orElse(null);
            if (blockExt != null) {
                String block = blockExt.getValue().primitiveValue();
                if (block != null && !block.isBlank()) {
                    return block;
                }
            }
        }
        return deriveBlockFromResource(resource);
    }

    private void ensureLocationCompleteness(org.hl7.fhir.r4.model.Resource resource, String cityScopeSystem, String neighScopeSystem) {
        Extension locExt = getOrCreateLocationExtension(resource);
        if (locExt == null) return;

        String block = extractNestedLocationValue(resource, BLOCK_URL);
        String neighborhood = extractNestedLocationValue(resource, NEIGH_URL);
        LocationParts subjectLocation = extractLocationFromSubjectPatient(resource);

        if ((block == null || block.isBlank()) && subjectLocation != null) {
            block = subjectLocation.block;
        }
        if ((neighborhood == null || neighborhood.isBlank()) && subjectLocation != null) {
            neighborhood = subjectLocation.neighborhood;
        }

        if (block == null || block.isBlank()) {
            block = deriveBlockFromResource(resource);
        }

        ScopedParts cityScoped = extractScopedParts(resource, cityScopeSystem);
        ScopedParts neighScoped = extractScopedParts(resource, neighScopeSystem);

        if ((block == null || block.isBlank()) && cityScoped != null && cityScoped.block != null && !cityScoped.block.isBlank()) {
            block = cityScoped.block;
        }
        if ((block == null || block.isBlank()) && neighScoped != null && neighScoped.block != null && !neighScoped.block.isBlank()) {
            block = neighScoped.block;
        }
        if ((neighborhood == null || neighborhood.isBlank()) && neighScoped != null && neighScoped.scope != null && !neighScoped.scope.isBlank()) {
            neighborhood = neighScoped.scope;
        }

        upsertNestedValue(locExt, BLOCK_URL, block);
        upsertNestedValue(locExt, NEIGH_URL, neighborhood);
        String cityValue = (subjectLocation != null && subjectLocation.city != null && !subjectLocation.city.isBlank())
            ? subjectLocation.city
            : city;
        upsertNestedValue(locExt, CITY_URL, cityValue);

        // Keep a single normalized location extension to avoid partial/duplicate variants.
        compactLocationExtensions(resource, locExt);
    }

    private Extension getOrCreateLocationExtension(org.hl7.fhir.r4.model.Resource resource) {
        List<Extension> locExtList = getLocationExtensions(resource);
        if (!locExtList.isEmpty()) {
            // Prefer the extension that already has block.
            for (Extension ext : locExtList) {
                if (nestedValue(ext, BLOCK_URL) != null) {
                    return ext;
                }
            }
            return locExtList.get(0);
        }

        Extension created = new Extension(LOCATION_EXTENSION_URL);
        if (resource instanceof Patient patient) {
            patient.addExtension(created);
        } else if (resource instanceof Observation observation) {
            observation.addExtension(created);
        } else if (resource instanceof Condition condition) {
            condition.addExtension(created);
        }
        return created;
    }

    private List<Extension> getLocationExtensions(org.hl7.fhir.r4.model.Resource resource) {
        List<Extension> result = new ArrayList<>();
        if (!(resource instanceof DomainResource domainResource)) return result;
        List<Extension> extensions = domainResource.getExtension();
        if (extensions == null) return result;
        for (Extension ext : extensions) {
            if (ext != null && (LOCATION_EXTENSION_URL.equals(ext.getUrl()) || urlMatches(ext.getUrl(), "patient-location"))) {
                result.add(ext);
            }
        }
        return result;
    }

    private String firstNestedValue(List<Extension> locationExtensions, String url) {
        for (Extension ext : locationExtensions) {
            String v = nestedValue(ext, url);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String extractNestedLocationValue(org.hl7.fhir.r4.model.Resource resource, String nestedUrl) {
        List<Extension> locationExtensions = getLocationExtensions(resource);
        return firstNestedValue(locationExtensions, nestedUrl);
    }

    private String nestedValue(Extension container, String url) {
        if (container == null) return null;
        Extension nested = container.getExtension().stream()
                .filter(e -> urlMatches(e.getUrl(), url) && e.getValue() != null)
                .findFirst()
                .orElse(null);
        return nested == null ? null : nested.getValue().primitiveValue();
    }

    private boolean urlMatches(String actual, String expectedLeaf) {
        if (actual == null) return false;
        String normalized = normalizeUrl(actual);
        String leaf = normalizeUrl(expectedLeaf);
        if (leaf.equals(normalized)) return true;
        return normalized.endsWith("/" + leaf) || normalized.endsWith(":" + leaf);
    }

    private String normalizeUrl(String url) {
        if (url == null) return "";
        String normalized = url.trim().toLowerCase();
        int query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) normalized = normalized.substring(0, fragment);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String deriveBlockFromResource(org.hl7.fhir.r4.model.Resource resource) {
        String fromId = deriveBlockFromIdPart(normalizeIdPart(resource.getIdElement() != null ? resource.getIdElement().getIdPart() : null));
        if (fromId != null) {
            return fromId;
        }
        if (resource instanceof Observation observation
                && observation.getSubject() != null
                && observation.getSubject().getReferenceElement() != null) {
            String subjectId = normalizeIdPart(observation.getSubject().getReferenceElement().getIdPart());
            return deriveBlockFromIdPart(subjectId);
        }
        if (resource instanceof Condition condition
                && condition.getSubject() != null
                && condition.getSubject().getReferenceElement() != null) {
            String subjectId = normalizeIdPart(condition.getSubject().getReferenceElement().getIdPart());
            return deriveBlockFromIdPart(subjectId);
        }
        LocationParts subjectLocation = extractLocationFromSubjectPatient(resource);
        if (subjectLocation != null && subjectLocation.block != null && !subjectLocation.block.isBlank()) {
            return subjectLocation.block;
        }
        return null;
    }

    private LocationParts extractLocationFromSubjectPatient(org.hl7.fhir.r4.model.Resource resource) {
        String subjectPatientId = null;

        if (resource instanceof Observation observation
                && observation.getSubject() != null
                && observation.getSubject().getReferenceElement() != null) {
            subjectPatientId = normalizeIdPart(observation.getSubject().getReferenceElement().getIdPart());
        } else if (resource instanceof Condition condition
                && condition.getSubject() != null
                && condition.getSubject().getReferenceElement() != null) {
            subjectPatientId = normalizeIdPart(condition.getSubject().getReferenceElement().getIdPart());
        }

        if (subjectPatientId == null || subjectPatientId.isBlank() || patientDao == null) {
            return null;
        }

        try {
            Patient patient = patientDao.read(fhirContext.getVersion().newIdType("Patient", subjectPatientId), null);
            if (patient == null) return null;

            String patientBlock = extractNestedLocationValue(patient, BLOCK_URL);
            String patientNeighborhood = extractNestedLocationValue(patient, NEIGH_URL);
            String patientCity = extractNestedLocationValue(patient, CITY_URL);

            if ((patientBlock == null || patientBlock.isBlank())
                    && (patientNeighborhood == null || patientNeighborhood.isBlank())
                    && (patientCity == null || patientCity.isBlank())) {
                return null;
            }

            return new LocationParts(patientCity, patientNeighborhood, patientBlock);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String deriveBlockFromIdPart(String idPart) {
        if (idPart == null || idPart.isBlank()) return null;
        int dash = idPart.indexOf('-');
        if (dash <= 1) return null;
        String prefix = idPart.substring(0, dash).trim();
        if (prefix.length() < 2) return null;
        char first = Character.toLowerCase(prefix.charAt(0));
        if (first != 'b') return null;
        String numeric = prefix.substring(1);
        for (int i = 0; i < numeric.length(); i++) {
            if (!Character.isDigit(numeric.charAt(i))) return null;
        }
        return "B" + numeric;
    }

    private void upsertNestedValue(Extension container, String url, String value) {
        if (container == null || value == null || value.isBlank()) return;

        Extension nested = container.getExtension().stream()
                .filter(e -> urlMatches(e.getUrl(), url))
                .findFirst()
                .orElse(null);

        if (nested != null) {
            nested.setValue(new StringType(value));
        } else {
            container.addExtension(new Extension().setUrl(url).setValue(new StringType(value)));
        }
    }

    private void compactLocationExtensions(org.hl7.fhir.r4.model.Resource resource, Extension keep) {
        if (!(resource instanceof DomainResource domainResource)) return;
        List<Extension> ext = domainResource.getExtension();
        if (ext == null || ext.isEmpty()) return;
        ext.removeIf(e -> e != null
                && e != keep
                && (LOCATION_EXTENSION_URL.equals(e.getUrl()) || urlMatches(e.getUrl(), "patient-location")));
    }

    private ScopedParts extractScopedParts(org.hl7.fhir.r4.model.Resource resource, String system) {
        List<Identifier> identifiers = null;
        if (resource instanceof Patient) {
            identifiers = ((Patient) resource).getIdentifier();
        } else if (resource instanceof Observation) {
            identifiers = ((Observation) resource).getIdentifier();
        } else if (resource instanceof Condition) {
            identifiers = ((Condition) resource).getIdentifier();
        }
        if (identifiers == null || identifiers.isEmpty()) return null;

        for (Identifier identifier : identifiers) {
            if (identifier == null || identifier.getSystem() == null || identifier.getValue() == null) continue;
            if (!system.equals(identifier.getSystem())) continue;

            String value = identifier.getValue();
            int first = value.indexOf('-');
            int second = value.indexOf('-', first + 1);
            if (first <= 0 || second <= first + 1) continue;

            String scope = value.substring(0, first).trim();
            String block = value.substring(first + 1, second).trim();
            if (scope.isEmpty() || block.isEmpty()) continue;
            return new ScopedParts(scope, block);
        }
        return null;
    }

    private static class ScopedParts {
        private final String scope;
        private final String block;

        private ScopedParts(String scope, String block) {
            this.scope = scope;
            this.block = block;
        }
    }

    private static class LocationParts {
        private final String city;
        private final String neighborhood;
        private final String block;

        private LocationParts(String city, String neighborhood, String block) {
            this.city = city;
            this.neighborhood = neighborhood;
            this.block = block;
        }
    }

    private void upsertIdentifier(org.hl7.fhir.r4.model.DomainResource resource, String system, String value) {
        List<Identifier> identifiers = null;
        if (resource instanceof Patient) {
            identifiers = ((Patient) resource).getIdentifier();
        } else if (resource instanceof Observation) {
            identifiers = ((Observation) resource).getIdentifier();
        } else if (resource instanceof Condition) {
            identifiers = ((Condition) resource).getIdentifier();
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
            } else if (resource instanceof Condition) {
                ((Condition) resource).addIdentifier(newId);
            }
        }
    }
}
