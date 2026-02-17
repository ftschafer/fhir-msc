package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Component
public class ObservationUpstreamForwardingInterceptor {
    private static final Logger ourLog = LoggerFactory.getLogger(ObservationUpstreamForwardingInterceptor.class);

    private static final String INTERNAL_REQUEST_HEADER = "X-Upstream-Internal-Request";
    private static final String STATISTICS_CODE_URL = "http://hl7.org/fhir/StructureDefinition/observation-statisticsCode";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";

    private final NeighborhoodVitalAggregationService aggregationService;

    public ObservationUpstreamForwardingInterceptor(NeighborhoodVitalAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void created(IBaseResource resource, RequestDetails requestDetails) {
        if (requestDetails == null || isInternalRequest(requestDetails)) return;
        if (resource instanceof Observation observation && shouldAggregateForward(observation)) {
            enqueueAfterCommit(observation);
        }
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void updated(IBaseResource oldResource, IBaseResource newResource, RequestDetails requestDetails) {
        if (requestDetails == null || isInternalRequest(requestDetails)) return;
        if (newResource instanceof Observation observation && shouldAggregateForward(observation)) {
            enqueueAfterCommit(observation);
        }
    }

    private boolean shouldAggregateForward(Observation observation) {
        if (observation == null) {
            return false;
        }

        boolean hasAverageStatistics = false;
        for (Extension ext : observation.getExtension()) {
            if (STATISTICS_CODE_URL.equals(ext.getUrl()) && ext.getValue() != null
                    && "average".equals(ext.getValue().primitiveValue())) {
                hasAverageStatistics = true;
                break;
            }
        }

        if (!hasAverageStatistics) {
            return false;
        }

        Extension location = observation.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (location == null || !location.hasExtension()) {
            return false;
        }

        for (Extension nested : location.getExtension()) {
            if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() != null
                    && nested.getValue().primitiveValue() != null
                    && !nested.getValue().primitiveValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private void enqueueAfterCommit(Observation observation) {
        Observation copy = (Observation) observation.copy();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ourLog.info("Trigger upstream average processing for Observation/{}", copy.getIdElement().getIdPart());
                    aggregationService.processBlockAverages(List.of(copy));
                }
            });
        } else {
            ourLog.info("Trigger upstream average processing for Observation/{}", copy.getIdElement().getIdPart());
            aggregationService.processBlockAverages(List.of(copy));
        }
    }

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }
}
