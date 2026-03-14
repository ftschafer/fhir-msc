package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Ensures eventual consistency for patient forwarding when upstream is temporarily unavailable.
 */
@Component
public class UpstreamPatientReconciliationJob {

    private static final Logger ourLog = LoggerFactory.getLogger(UpstreamPatientReconciliationJob.class);

    private final DaoRegistry daoRegistry;
    private final UpstreamForwarder upstreamForwarder;

    @Value("${upstream.reconcile.patients.enabled:true}")
    private boolean enabled;

    @Value("${upstream.reconcile.patients.chunk-size:300}")
    private int reconcileChunkSize;

    public UpstreamPatientReconciliationJob(DaoRegistry daoRegistry, UpstreamForwarder upstreamForwarder) {
        this.daoRegistry = daoRegistry;
        this.upstreamForwarder = upstreamForwarder;
    }

    @Scheduled(initialDelayString = "${upstream.reconcile.patients.initial-delay-ms:30000}", fixedDelayString = "${upstream.reconcile.patients.fixed-delay-ms:120000}")
    public void reconcilePatients() {
        if (!enabled) {
            return;
        }

        try {
            IFhirResourceDao<Patient> dao = daoRegistry.getResourceDao(Patient.class);
            SearchParameterMap search = new SearchParameterMap();
            search.setLoadSynchronous(true);

            IBundleProvider results = dao.search(search);
            if (results == null || results.size() == null || results.size() <= 0) {
                return;
            }

            int total = results.size().intValue();
            int chunkSize = Math.max(50, reconcileChunkSize);
            int forwarded = 0;

            for (int start = 0; start < total; start += chunkSize) {
                int end = Math.min(total, start + chunkSize);
                List<IBaseResource> page = results.getResources(start, end);
                List<Patient> patients = new ArrayList<>();
                for (IBaseResource resource : page) {
                    if (resource instanceof Patient patient) {
                        patients.add(patient);
                    }
                }

                if (!patients.isEmpty()) {
                    upstreamForwarder.upsertPatients(patients);
                    forwarded += patients.size();
                }
            }

            if (forwarded > 0) {
                ourLog.info("Patient reconciliation forwarded {} patient(s) upstream in chunked mode", forwarded);
            }
        } catch (RuntimeException e) {
            ourLog.warn("Patient reconciliation skipped due to error: {}", e.getMessage());
        }
    }
}
