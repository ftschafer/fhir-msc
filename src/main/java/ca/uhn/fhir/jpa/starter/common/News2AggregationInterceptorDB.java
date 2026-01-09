package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class News2AggregationInterceptorDB {
    private static final String TX_OBS = "NEWS2_OBS";
    private static final String TX_DONE = "NEWS2_DONE";

    private final News2AggregationService service;

    public News2AggregationInterceptorDB(News2AggregationService service) {
        this.service = service;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onCreated(Object resource, RequestDetails req, TransactionDetails tx) {
        if (isInternal(req) || Boolean.TRUE.equals(tx.getUserData(TX_DONE))) return;
        if (resource instanceof Observation) {
            List<Observation> list = (List<Observation>) tx.getUserData(TX_OBS);
            if (list == null) {
                list = new ArrayList<>();
                tx.putUserData(TX_OBS, list);
            }
            list.add((Observation) resource);
        }
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onUpdated(Object resource, RequestDetails req, TransactionDetails tx) {
        if (isInternal(req) || Boolean.TRUE.equals(tx.getUserData(TX_DONE))) return;
        if (resource instanceof Observation) {
            List<Observation> list = (List<Observation>) tx.getUserData(TX_OBS);
            if (list == null) {
                list = new ArrayList<>();
                tx.putUserData(TX_OBS, list);
            }
            list.add((Observation) resource);
        }
    }

    @Hook(Pointcut.STORAGE_TRANSACTION_PROCESSED)
    public void afterProcessed(RequestDetails req, TransactionDetails tx) {
        if (isInternal(req) || Boolean.TRUE.equals(tx.getUserData(TX_DONE))) return;
        List<Observation> obs = (List<Observation>) tx.getUserData(TX_OBS);
        if (obs == null || obs.isEmpty()) {
            tx.putUserData(TX_DONE, Boolean.TRUE);
            return;
        }

        try {
            // 1) Aggregate observations -> DB upserts
            Set<String> patients = service.processObservations(obs);
            // 2) Update Patient exactly once per transaction, only if NEWS2 changed
            for (String pid : patients) {
                service.updatePatientNews2IfChanged(pid);
            }
        } finally {
            tx.putUserData(TX_DONE, Boolean.TRUE);
        }
    }

    private boolean isInternal(RequestDetails req) {
        return req != null && "true".equals(req.getHeader("X-Internal-Request"));
    }
}
