package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Interceptor that automatically adds neighborhood extension to resources with block extension
 * Applies to: Patient, Observation, Condition
 */
@Component
@Interceptor
public class LocationExtensionInterceptor {

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String NEIGH_URL = "neighborhood";
    private static final String BLOCK_URL = "block";

    @Value("${location.neighborhood:center}")
    private String neighborhoodValue;

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
    public void handleCreate(IBaseResource resource) {
        ensureNeighborhoodExtension(resource);
    }

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
    public void handleUpdate(IBaseResource oldResource, IBaseResource newResource) {
        ensureNeighborhoodExtension(newResource);
    }

    private void ensureNeighborhoodExtension(IBaseResource resource) {
        if (resource instanceof Patient) {
            ensureNeighborhoodForPatient((Patient) resource);
        } else if (resource instanceof Observation) {
            ensureNeighborhoodForObservation((Observation) resource);
        } else if (resource instanceof Condition) {
            ensureNeighborhoodForCondition((Condition) resource);
        }
    }

    private void ensureNeighborhoodForPatient(Patient patient) {
        Extension locExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        
        // If no location extension at all, skip
        if (locExt == null) {
            return;
        }

        // Check if has block
        boolean hasBlock = locExt.getExtension().stream()
            .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);

        // Check if has neighborhood
        boolean hasNeighborhood = locExt.getExtension().stream()
            .anyMatch(e -> NEIGH_URL.equals(e.getUrl()) && e.getValue() != null);

        // If has block but no neighborhood, add it
        if (hasBlock && !hasNeighborhood) {
            locExt.addExtension(new Extension()
                .setUrl(NEIGH_URL)
                .setValue(new StringType(neighborhoodValue)));
        }
    }

    private void ensureNeighborhoodForObservation(Observation observation) {
        Extension locExt = observation.getExtensionByUrl(LOCATION_EXTENSION_URL);
        
        // If no location extension at all, skip
        if (locExt == null) {
            return;
        }

        // Check if has block
        boolean hasBlock = locExt.getExtension().stream()
            .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);

        // Check if has neighborhood
        boolean hasNeighborhood = locExt.getExtension().stream()
            .anyMatch(e -> NEIGH_URL.equals(e.getUrl()) && e.getValue() != null);

        // If has block but no neighborhood, add it
        if (hasBlock && !hasNeighborhood) {
            locExt.addExtension(new Extension()
                .setUrl(NEIGH_URL)
                .setValue(new StringType(neighborhoodValue)));
        }
    }

    private void ensureNeighborhoodForCondition(Condition condition) {
        Extension locExt = condition.getExtensionByUrl(LOCATION_EXTENSION_URL);
        
        // If no location extension at all, skip
        if (locExt == null) {
            return;
        }

        // Check if has block
        boolean hasBlock = locExt.getExtension().stream()
            .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);

        // Check if has neighborhood
        boolean hasNeighborhood = locExt.getExtension().stream()
            .anyMatch(e -> NEIGH_URL.equals(e.getUrl()) && e.getValue() != null);

        // If has block but no neighborhood, add it
        if (hasBlock && !hasNeighborhood) {
            locExt.addExtension(new Extension()
                .setUrl(NEIGH_URL)
                .setValue(new StringType(neighborhoodValue)));
        }
    }
}
