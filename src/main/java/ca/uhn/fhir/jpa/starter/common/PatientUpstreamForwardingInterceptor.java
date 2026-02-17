package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Component
public class PatientUpstreamForwardingInterceptor {
    private static final Logger ourLog = LoggerFactory.getLogger(PatientUpstreamForwardingInterceptor.class);

    private static final String INTERNAL_REQUEST_HEADER = "X-Upstream-Internal-Request";

    private final UpstreamForwarder upstreamForwarder;

    public PatientUpstreamForwardingInterceptor(UpstreamForwarder upstreamForwarder) {
        this.upstreamForwarder = upstreamForwarder;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void created(IBaseResource resource, RequestDetails requestDetails) {
        if (requestDetails == null || isInternalRequest(requestDetails)) return;
        if (resource instanceof Patient patient) {
                ourLog.info("Patient create detected for upstream forwarding: Patient/{}",
                    patient.getIdElement() != null ? patient.getIdElement().getIdPart() : null);
            enqueueAfterCommit(patient);
        }
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void updated(IBaseResource oldResource, IBaseResource newResource, RequestDetails requestDetails) {
        if (requestDetails == null || isInternalRequest(requestDetails)) return;
        if (newResource instanceof Patient patient) {
                ourLog.info("Patient update detected for upstream forwarding: Patient/{}",
                    patient.getIdElement() != null ? patient.getIdElement().getIdPart() : null);
            enqueueAfterCommit(patient);
        }
    }

    private void enqueueAfterCommit(Patient patient) {
        Patient copy = (Patient) patient.copy();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                        ourLog.info("After-commit patient forwarding: Patient/{}",
                            copy.getIdElement() != null ? copy.getIdElement().getIdPart() : null);
                    upstreamForwarder.upsertPatients(List.of(copy));
                }
            });
        } else {
                ourLog.info("Immediate patient forwarding (no transaction sync): Patient/{}",
                    copy.getIdElement() != null ? copy.getIdElement().getIdPart() : null);
            upstreamForwarder.upsertPatients(List.of(copy));
        }
    }

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }
}
