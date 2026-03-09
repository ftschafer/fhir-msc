package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PatientBlockInterceptor {

    private static final String INTERNAL_REQUEST_HEADER = "X-Internal-Request";

    private final PatientEventQueue queue;

    public PatientBlockInterceptor(PatientEventQueue queue) {
        this.queue = queue;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void created(IBaseResource resource, RequestDetails requestDetails) {
        if (isInternalRequest(requestDetails)) return;
        if (resource instanceof Patient p) enqueueAfterCommit(p);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void updated(IBaseResource oldRes, IBaseResource newRes, RequestDetails requestDetails) {
        if (isInternalRequest(requestDetails)) return;
        if (newRes instanceof Patient p) enqueueAfterCommit(p);
    }

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }

    private void enqueueAfterCommit(Patient p) {
        Patient copy = (Patient) p.copy();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { queue.enqueue(copy); }
            });
        } else {
            queue.enqueue(copy);
        }
    }
}
