package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
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

    private final FhirContext fhirContext;
    private final IFhirResourceDao<Patient> patientDao;

    public LocationExtensionInterceptor(FhirContext fhirContext, DaoRegistry daoRegistry) {
        this.fhirContext = fhirContext;
        this.patientDao = daoRegistry.getResourceDao(Patient.class);
    }

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
            ensureLocation(patient, patient.getExtensionByUrl(LOCATION_EXTENSION_URL));
        } else if (resource instanceof Observation observation) {
            ensureLocation(observation, observation.getExtensionByUrl(LOCATION_EXTENSION_URL));
        } else if (resource instanceof Condition condition) {
            ensureLocation(condition, condition.getExtensionByUrl(LOCATION_EXTENSION_URL));
        }
    }

    private void ensureLocation(org.hl7.fhir.r4.model.Resource resource, Extension locationExt) {
        if (locationExt == null) {
            locationExt = new Extension(LOCATION_EXTENSION_URL);
            if (resource instanceof Patient patient) {
                patient.addExtension(locationExt);
            } else if (resource instanceof Observation observation) {
                observation.addExtension(locationExt);
            } else if (resource instanceof Condition condition) {
                condition.addExtension(locationExt);
            }
        }

        String block = nested(locationExt, BLOCK_URL);
        String neighborhood = nested(locationExt, "neighborhood");

        if ((block == null || block.isBlank()) || (neighborhood == null || neighborhood.isBlank())) {
            LocationParts fromPatient = extractFromSubjectPatient(resource);
            if (fromPatient != null) {
                if ((block == null || block.isBlank()) && fromPatient.block != null && !fromPatient.block.isBlank()) {
                    upsertNested(locationExt, BLOCK_URL, fromPatient.block);
                    block = fromPatient.block;
                }
                if ((neighborhood == null || neighborhood.isBlank()) && fromPatient.neighborhood != null && !fromPatient.neighborhood.isBlank()) {
                    upsertNested(locationExt, "neighborhood", fromPatient.neighborhood);
                }
                if (fromPatient.city != null && !fromPatient.city.isBlank()) {
                    upsertNested(locationExt, CITY_URL, fromPatient.city);
                }
            }
        }

        if (nested(locationExt, BLOCK_URL) == null || nested(locationExt, BLOCK_URL).isBlank()) return;

        if (nested(locationExt, CITY_URL) == null || nested(locationExt, CITY_URL).isBlank()) {
            upsertNested(locationExt, CITY_URL, cityValue);
        }
    }

    private String nested(Extension locationExt, String url) {
        if (locationExt == null || locationExt.getExtension() == null) return null;
        Extension nested = locationExt.getExtension().stream()
                .filter(e -> e != null && url.equals(e.getUrl()) && e.getValue() != null)
                .findFirst()
                .orElse(null);
        return nested == null ? null : nested.getValue().primitiveValue();
    }

    private void upsertNested(Extension locationExt, String url, String value) {
        if (locationExt == null || value == null || value.isBlank()) return;
        Extension nested = locationExt.getExtension().stream()
                .filter(e -> e != null && url.equals(e.getUrl()))
                .findFirst()
                .orElse(null);
        if (nested == null) {
            locationExt.addExtension(new Extension().setUrl(url).setValue(new StringType(value)));
        } else {
            nested.setValue(new StringType(value));
        }
    }

    private LocationParts extractFromSubjectPatient(org.hl7.fhir.r4.model.Resource resource) {
        String subjectId = null;
        if (resource instanceof Observation observation
                && observation.getSubject() != null
                && observation.getSubject().getReferenceElement() != null) {
            subjectId = observation.getSubject().getReferenceElement().getIdPart();
        } else if (resource instanceof Condition condition
                && condition.getSubject() != null
                && condition.getSubject().getReferenceElement() != null) {
            subjectId = condition.getSubject().getReferenceElement().getIdPart();
        }

        if (subjectId == null || subjectId.isBlank()) return null;

        try {
            Patient patient = patientDao.read(fhirContext.getVersion().newIdType("Patient", subjectId), null);
            if (patient == null) return null;

            Extension loc = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
            if (loc == null) return null;

            String pBlock = nested(loc, BLOCK_URL);
            String pNeighborhood = nested(loc, "neighborhood");
            String pCity = nested(loc, CITY_URL);

            return new LocationParts(pCity, pNeighborhood, pBlock);
        } catch (Exception ignored) {
            return null;
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

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }
}
