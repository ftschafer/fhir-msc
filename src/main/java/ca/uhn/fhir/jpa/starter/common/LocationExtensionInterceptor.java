package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ensures `city` is present in `http://patient-location` when `block` exists.
 */
@Component
@Interceptor
public class LocationExtensionInterceptor {

    private static final String INTERNAL_REQUEST_HEADER = "X-Internal-Request";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String CITY_URL = "city";
    private static final String BLOCK_URL = "block";

    @Value("${location.city}")
    private String cityValue;

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
    public void handleCreate(IBaseResource resource, RequestDetails requestDetails) {
        if (isInternalRequest(requestDetails)) return;
        ensureCityExtension(resource);
    }

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
    public void handleUpdate(IBaseResource oldResource, IBaseResource newResource, RequestDetails requestDetails) {
        if (isInternalRequest(requestDetails)) return;
        ensureCityExtension(newResource);
    }

    private void ensureCityExtension(IBaseResource resource) {
        if (resource instanceof Patient patient) {
            ensureCity(patient.getExtensionByUrl(LOCATION_EXTENSION_URL));
        } else if (resource instanceof Observation observation) {
            ensureCity(observation.getExtensionByUrl(LOCATION_EXTENSION_URL));
        } else if (resource instanceof Condition condition) {
            ensureCity(condition.getExtensionByUrl(LOCATION_EXTENSION_URL));
        }
    }

    private void ensureCity(Extension locationExt) {
        if (locationExt == null) return;

        boolean hasBlock = locationExt.getExtension().stream()
                .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);
        if (!hasBlock) return;

        boolean hasCity = locationExt.getExtension().stream()
                .anyMatch(e -> CITY_URL.equals(e.getUrl()) && e.getValue() != null);

        if (!hasCity) {
            locationExt.addExtension(new Extension()
                    .setUrl(CITY_URL)
                    .setValue(new StringType(cityValue)));
        }
    }

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }
}
