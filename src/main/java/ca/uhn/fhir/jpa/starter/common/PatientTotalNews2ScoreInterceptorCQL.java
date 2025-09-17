package ca.uhn.fhir.jpa.starter.common;

import java.util.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class PatientTotalNews2ScoreInterceptorCQL {

    private static final Logger logger = LoggerFactory.getLogger(PatientTotalNews2ScoreInterceptor.class);
    private static final String NEWS2_EXTENSION_URL = "http://example.org/fhir/StructureDefinition/news2-score";

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;

    @Autowired
    public PatientTotalNews2ScoreInterceptorCQL(DaoRegistry daoRegistry, FhirContext fhirContext) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    private IFhirResourceDao<Observation> observationDao() {
        return daoRegistry.getResourceDao(Observation.class);
    }

   @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onObservationCreated(
            IBaseResource resource,
            RequestDetails requestDetails,
            TransactionDetails transactionDetails) {

        if (!(resource instanceof Observation)) {
            return;
        }

        Observation obs = (Observation) resource;
        if (obs.getSubject() == null || obs.getSubject().getReference() == null) {
            return;
        }

        String patientRef = obs.getSubject().getReference(); // e.g., "Patient/123"
        if (!patientRef.startsWith("Patient/")) {
            return;
        }

        String patientId = patientRef.substring("Patient/".length());
        logger.debug("Computing NEWS2 score for patient: {}", patientId);

        try {
            int totalScore = computeNews2ForSinglePatient(patientId, requestDetails);

            Patient patient = patientDao().read(
                fhirContext.getVersion().newIdType("Patient", patientId),
                requestDetails
            );

            Extension news2Extension = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
            Integer oldScore = null;
            if (news2Extension != null && news2Extension.getValue() instanceof IntegerType) {
                oldScore = ((IntegerType) news2Extension.getValue()).getValue();
            }

            // Only update if the score changed or extension is missing
            if (news2Extension == null) {
                patient.addExtension(new Extension(NEWS2_EXTENSION_URL, new IntegerType(totalScore)));
                patientDao().update(patient, requestDetails);
                logger.info("Added NEWS2 score extension for Patient/{}, NEWS2 score={}", patientId, totalScore);
            } else if (!Objects.equals(oldScore, totalScore)) {
                news2Extension.setValue(new IntegerType(totalScore));
                patientDao().update(patient, requestDetails);
                logger.info("Updated Patient/{}, NEWS2 score changed from {} to {}", patientId, oldScore, totalScore);
            } else {
                logger.debug("NEWS2 score unchanged for Patient/{} (score={}), skipping update.", patientId, totalScore);
            }

        } catch (Exception e) {
            logger.error("Failed to compute/update NEWS2 score for patient {}", patientId, e);
        }
    }

    private int computeNews2ForSinglePatient(String patientId, RequestDetails requestDetails) {
        IGenericClient client = fhirContext.newRestfulGenericClient(requestDetails.getFhirServerBase());

        Parameters input = new Parameters();
        input.addParameter().setName("subject").setValue(new StringType("Patient/" + patientId));
        input.addParameter().setName("library").setValue(new StringType("News2|1.0.0"));
        input.addParameter().setName("expression").setValue(new StringType("TotalNEWS2Score"));

        Parameters result = client
            .operation()
            .onServer()
            .named("$cql")
            .withParameters(input)
            .execute();

        if (result != null && result.getParameter("return") != null) {
            if (result.getParameter("return").getValue() instanceof IntegerType) {
                return ((IntegerType) result.getParameter("return").getValue()).getValue();
            }
        }
        return 0;
    }
}
