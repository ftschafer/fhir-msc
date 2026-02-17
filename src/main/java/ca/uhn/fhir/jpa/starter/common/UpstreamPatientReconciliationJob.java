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

            List<IBaseResource> all = results.getResources(0, results.size());
            List<Patient> patients = new ArrayList<>();
            for (IBaseResource resource : all) {
                if (resource instanceof Patient patient) {
                    patients.add(patient);
                }
            }

            if (!patients.isEmpty()) {
                ourLog.info("Patient reconciliation forwarding {} patient(s) upstream", patients.size());
                upstreamForwarder.upsertPatients(patients);
            }
        } catch (RuntimeException e) {
            ourLog.warn("Patient reconciliation skipped due to error: {}", e.getMessage());
        }
    }
}
