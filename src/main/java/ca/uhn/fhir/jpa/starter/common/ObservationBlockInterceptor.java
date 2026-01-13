package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ObservationBlockInterceptor {

    private final ObservationEventQueue queue;

    public ObservationBlockInterceptor(ObservationEventQueue queue) {
        this.queue = queue;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void created(IBaseResource resource) {
        if (resource instanceof Observation obs) enqueueAfterCommit((Observation) resource);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void updated(IBaseResource oldRes, IBaseResource newRes) {
        if (newRes instanceof Observation obs) enqueueAfterCommit((Observation) newRes);
    }

    private void enqueueAfterCommit(Observation obs) {
        Observation copy = (Observation) obs.copy();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { queue.enqueue(copy); }
            });
        } else {
            queue.enqueue(copy);
        }
    }
}
