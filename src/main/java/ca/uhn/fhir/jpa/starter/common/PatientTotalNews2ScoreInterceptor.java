package ca.uhn.fhir.jpa.starter.common;

import java.util.*;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;

@Component
public class PatientTotalNews2ScoreInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(PatientTotalNews2ScoreInterceptor.class);
    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String[] NEWS2_LOINC_CODES = {"8867-4", "9279-1", "8310-5", "59408-5", "8480-6"};

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;
    private final PerfMetricsService perfMetrics;

    @Autowired
    public PatientTotalNews2ScoreInterceptor(DaoRegistry daoRegistry, FhirContext fhirContext, PerfMetricsService perfMetrics) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
        this.perfMetrics = perfMetrics;
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    private IFhirResourceDao<Observation> observationDao() {
        return daoRegistry.getResourceDao(Observation.class);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onObservationCreated(IBaseResource resource, RequestDetails requestDetails, TransactionDetails transactionDetails) {
        if (resource instanceof Patient) {
            perfMetrics.recordPatientIngested();
            return;
        }
        if (resource instanceof Condition) {
            perfMetrics.conditionCreatedCounter.increment();
            return;
        }
        if (!(resource instanceof Observation)) return;

        perfMetrics.recordObservationIngested();
        Observation obs = (Observation) resource;
        String patientId = extractPatientId(obs.getSubject());
        if (patientId == null) return;

        int totalScore = computeNews2ForSinglePatient(patientId, obs);
        updatePatientNews2Score(patientId, totalScore, requestDetails);
    }

    private String extractPatientId(Reference subject) {
        if (subject == null) return null;
        String ref = subject.getReference(); // e.g., "Patient/123"
        if (ref != null && ref.startsWith("Patient/")) {
            return ref.substring("Patient/".length());
        }
        return null;
    }

    private int computeNews2ForSinglePatient(String patientId, Observation newObservation) {
        IBundleProvider results = observationDao().search(buildObservationSearchParams(patientId));
        int totalScore = 0;
        Set<String> processedCodes = new HashSet<>();

        
        String code = newObservation.getCode().getCodingFirstRep().getCode();
        if (Arrays.asList(NEWS2_LOINC_CODES).contains(code) && processedCodes.add(code)) {
            totalScore += extractNews2Score(newObservation);
            // No break needed here since there is no loop
        }
        return totalScore;
    }

    private SearchParameterMap buildObservationSearchParams(String patientId) {
        SearchParameterMap paramMap = new SearchParameterMap();
        paramMap.add(Observation.SP_SUBJECT, new ReferenceParam("Patient/" + patientId));

        TokenOrListParam codeParam = new TokenOrListParam();
        for (String code : NEWS2_LOINC_CODES) {
            codeParam.addOr(new TokenParam("http://loinc.org", code));
        }
        paramMap.add(Observation.SP_CODE, codeParam);

        paramMap.setSort(new SortSpec(Observation.SP_DATE, SortOrderEnum.DESC));
        paramMap.setLoadSynchronous(true);

        return paramMap;
    }

    private int extractNews2Score(Observation obs) {
        return obs.getExtension().stream()
                .filter(ext -> NEWS2_EXTENSION_URL.equals(ext.getUrl()))
                .map(ext -> ((IntegerType) ext.getValue()).getValue())
                .findFirst()
                .orElse(0);
    }

    private void updatePatientNews2Score(String patientId, int score, RequestDetails requestDetails) {
        Patient patient = patientDao().read(fhirContext.getVersion().newIdType("Patient", patientId), requestDetails);

        Extension ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        if (ext != null) {
            ext.setValue(new IntegerType(score));
        } else {
            patient.addExtension(new Extension(NEWS2_EXTENSION_URL, new IntegerType(score)));
        }

        patientDao().update(patient, requestDetails);
    }
}
