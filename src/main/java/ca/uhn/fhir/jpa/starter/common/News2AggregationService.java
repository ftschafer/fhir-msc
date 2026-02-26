package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class News2AggregationService {

    // ------------------------
    // Domain constants
    // ------------------------
    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";
    private static final String BLOCK_AVG_IDENTIFIER_SYSTEM = "urn:aggregate:news2";
    private static final String BLOCK_AVG_CODE_SYSTEM = "http://news2-score";
    private static final String BLOCK_AVG_CODE = "block-average";

    private static final Set<String> LOINC_CODES =
            Set.of("8867-4", "9279-1", "8310-5", "59408-5", "8480-6");

    // ------------------------
    // Metrics
    // ------------------------
    private static final String METRIC_PROCESSING = "fhir_news2_processing_seconds";
    private static final String METRIC_DB = "fhir_news2_db_update_seconds";
    private static final String METRIC_UPSTREAM = "fhir_news2_upstream_seconds";
    private static final String METRIC_PATIENTS = "fhir_news2_patients_processed_total";
    private static final String METRIC_OUTCOME = "fhir_news2_patient_update_total";

    @PersistenceContext
    private EntityManager em;

    @Value("${hapi.fhir.location.block:North}")
    private String blockValue;

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;
    private final PatientLockingService locks;
    private final UpstreamForwarder upstreamForwarder;
    private final MeterRegistry meterRegistry;

    public News2AggregationService(
            DaoRegistry daoRegistry,
            FhirContext fhirContext,
            PatientLockingService locks,
            UpstreamForwarder upstreamForwarder,
            MeterRegistry meterRegistry
    ) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
        this.locks = locks;
        this.upstreamForwarder = upstreamForwarder;
        this.meterRegistry = meterRegistry;
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    private IFhirResourceDao<Observation> observationDao() {
        return daoRegistry.getResourceDao(Observation.class);
    }

    // ----------------------------------------------------
    // ENTRY POINT
    // ----------------------------------------------------
    @Transactional
    public Set<String> processObservations(List<Observation> observations) {

        if (observations == null || observations.isEmpty()) {
            return Collections.emptySet();
        }

        Timer.Sample batchTimer = Timer.start(meterRegistry);

        Map<String, List<Observation>> byPatient = observations.stream()
                .filter(o -> o.getSubject() != null
                        && o.getSubject().getReference() != null
                        && o.getSubject().getReference().startsWith("Patient/"))
                .collect(Collectors.groupingBy(
                        o -> o.getSubject().getReference().substring("Patient/".length())
                ));

        for (Map.Entry<String, List<Observation>> e : byPatient.entrySet()) {
            String patientId = e.getKey();
            var lock = locks.getLockForPatient(patientId);
            lock.lock();
            try {
                processOne(patientId, e.getValue());
            } finally {
                lock.unlock();
            }
        }

        for (String patientId : byPatient.keySet()) {
            updatePatientNews2IfChanged(patientId);
        }

        meterRegistry.counter(METRIC_PATIENTS)
                .increment(byPatient.size());

        batchTimer.stop(
                Timer.builder(METRIC_PROCESSING)
                        .description("End-to-end NEWS2 aggregation latency")
                        .register(meterRegistry)
        );

        return byPatient.keySet();
    }

    // ----------------------------------------------------
    // OBSERVATION → AGGREGATE
    // ----------------------------------------------------
    private void processOne(String patientId, List<Observation> observations) {

        Timer.Sample dbTimer = Timer.start(meterRegistry);

        Map<String, Observation> newest = new HashMap<>();

        for (Observation o : observations) {
            if (o.getCode() == null || o.getCode().getCoding().isEmpty()) continue;

            String code = o.getCode().getCodingFirstRep().getCode();
            if (!LOINC_CODES.contains(code)) continue;

            Instant at = extractInstant(o);
            Observation prev = newest.get(code);

            if (prev == null || at.isAfter(extractInstant(prev))) {
                newest.put(code, o);
            }
        }

        for (Map.Entry<String, Observation> entry : newest.entrySet()) {
            em.createNativeQuery(
                            "MERGE INTO news2_agg " +
                                    "(code, patient_id, obs_instant, news2_value) " +
                                    "KEY(code, patient_id) VALUES (?, ?, ?, ?)")
                    .setParameter(1, entry.getKey())
                    .setParameter(2, patientId)
                    .setParameter(3, java.sql.Timestamp.from(extractInstant(entry.getValue())))
                    .setParameter(4, extractScore(entry.getValue()))
                    .executeUpdate();
        }

        dbTimer.stop(
                Timer.builder(METRIC_DB)
                        .description("Time spent updating NEWS2 aggregates")
                        .register(meterRegistry)
        );
    }

    // ----------------------------------------------------
    // PATIENT UPDATE + UPSTREAM
    // ----------------------------------------------------
    @Transactional
    public void updatePatientNews2IfChanged(String patientId) {

        boolean patientModified = false;

        Number totalNum = em.createQuery(
                        "SELECT COALESCE(SUM(a.score), 0) FROM News2Aggregate a WHERE a.id.patientId = :pid",
                        Number.class)
                .setParameter("pid", patientId)
                .getSingleResult();

        int total = totalNum == null ? 0 : totalNum.intValue();

        Patient patient = patientDao().read(
                fhirContext.getVersion().newIdType("Patient", patientId), null);

        Extension news2Ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        Integer oldTotal = news2Ext != null && news2Ext.getValue() instanceof IntegerType
                ? ((IntegerType) news2Ext.getValue()).getValue()
                : null;

        if (!Objects.equals(oldTotal, total)) {
            if (news2Ext == null) {
                patient.addExtension(new Extension(NEWS2_EXTENSION_URL, new IntegerType(total)));
            } else {
                news2Ext.setValue(new IntegerType(total));
            }
            patientModified = true;
        }

        Extension locExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locExt == null) {
            locExt = new Extension(LOCATION_EXTENSION_URL);
            patient.addExtension(locExt);
            patientModified = true;
        }

        boolean hasBlock = locExt.getExtension().stream()
                .anyMatch(e -> BLOCK_URL.equals(e.getUrl()) && e.getValue() != null);

        if (!hasBlock) {
            locExt.addExtension(
                    new Extension().setUrl(BLOCK_URL).setValue(new StringType(blockValue)));
            patientModified = true;
        }

        String patientBlock = extractBlock(patient);
        if (patientBlock == null || patientBlock.isBlank()) {
            patientBlock = blockValue;
        }

        upsertPatientNews2Current(patientId, patientBlock, total);

        if (patientModified) {
            patientDao().update(patient);

            meterRegistry.counter(METRIC_OUTCOME, "result", "changed").increment();

            Patient finalPatient = patient;
        BlockAverageSnapshot blockAverage = computeBlockAverage(patientBlock);

            TransactionSynchronizationManager.registerSynchronization(
                    new PatientAndBlockForwardSync(finalPatient, blockAverage)
            );

        } else {
            meterRegistry.counter(METRIC_OUTCOME, "result", "unchanged").increment();
        }
    }

    private BlockAverageSnapshot computeBlockAverage(String block) {
        Object result = em.createNativeQuery(
                        "SELECT COALESCE(AVG(news2), 0), COUNT(*) FROM patient_news2_current WHERE block_id = ?")
                .setParameter(1, block)
                .getSingleResult();

        Object[] row = (Object[]) result;
        Number avgNum = (Number) row[0];
        Number countNum = (Number) row[1];

        double average = avgNum == null ? 0.0 : avgNum.doubleValue();
        int count = countNum == null ? 0 : countNum.intValue();
        return new BlockAverageSnapshot(block, average, count);
    }

    private void upsertPatientNews2Current(String patientId, String block, int news2) {
        em.createNativeQuery(
                        "MERGE INTO patient_news2_current (patient_id, block_id, news2, updated_at) " +
                                "KEY(patient_id) VALUES (?, ?, ?, ?)")
                .setParameter(1, patientId)
                .setParameter(2, block)
                .setParameter(3, news2)
                .setParameter(4, java.sql.Timestamp.from(Instant.now()))
                .executeUpdate();
    }

    private String extractBlock(Patient patient) {
        Extension locationExtension = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locationExtension == null) {
            return null;
        }

        for (Extension nested : locationExtension.getExtension()) {
            if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() instanceof StringType stringType) {
                return stringType.getValue();
            }
        }
        return null;
    }

    private Observation toBlockAverageObservation(BlockAverageSnapshot snapshot) {
        Observation observation = new Observation();
        observation.setStatus(Observation.ObservationStatus.FINAL);

        observation.addIdentifier()
                .setSystem(BLOCK_AVG_IDENTIFIER_SYSTEM)
                .setValue(snapshot.block() + "|" + BLOCK_AVG_CODE);

        observation.addCategory()
                .addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("survey")
                .setDisplay("Survey");

        observation.getCode()
                .addCoding()
                .setSystem(BLOCK_AVG_CODE_SYSTEM)
                .setCode(BLOCK_AVG_CODE)
                .setDisplay("NEWS2 Block Average");

        observation.setValue(new Quantity()
                .setValue(snapshot.average())
                .setUnit("score")
                .setSystem("http://unitsofmeasure.org")
                .setCode("{score}"));

        Extension locationExtension = new Extension();
        locationExtension.setUrl(LOCATION_EXTENSION_URL);
        locationExtension.addExtension(new Extension()
                .setUrl(BLOCK_URL)
                .setValue(new StringType(snapshot.block())));
        observation.addExtension(locationExtension);

        observation.addExtension(new Extension("http://observation-sample-count", new IntegerType(snapshot.sampleCount())));
        observation.setEffective(new DateTimeType(new Date()));
        return observation;
    }

    private record BlockAverageSnapshot(String block, double average, int sampleCount) {
    }

    private void upsertLocalBlockAverageObservation(Observation blockAverageObservation) {
        String aggregateIdentifier = blockAverageObservation.getIdentifierFirstRep().getValue();
        Observation existing = findExistingAggregateByIdentifier(aggregateIdentifier);
        if (existing != null) {
            blockAverageObservation.setId(existing.getIdElement());
            observationDao().update(blockAverageObservation);
            return;
        }
        observationDao().create(blockAverageObservation);
    }

    private Observation findExistingAggregateByIdentifier(String aggregateIdentifier) {
        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.add("identifier", new TokenParam(BLOCK_AVG_IDENTIFIER_SYSTEM, aggregateIdentifier));
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = observationDao().search(searchMap);
        List<IBaseResource> resources = results.getAllResources();
        if (!resources.isEmpty() && resources.get(0) instanceof Observation) {
            return (Observation) resources.get(0);
        }
        return null;
    }

    private class PatientAndBlockForwardSync implements TransactionSynchronization {
        private final Patient patient;
        private final BlockAverageSnapshot blockAverage;

        private PatientAndBlockForwardSync(Patient patient, BlockAverageSnapshot blockAverage) {
            this.patient = patient;
            this.blockAverage = blockAverage;
        }

        @Override
        public void afterCommit() {
            Timer.Sample upstreamTimer = Timer.start(meterRegistry);
            Observation blockAverageObservation = toBlockAverageObservation(blockAverage);
            upsertLocalBlockAverageObservation(blockAverageObservation);
            upstreamForwarder.upsertPatients(List.of(patient));
            upstreamForwarder.createObservations(List.of(blockAverageObservation));
            upstreamTimer.stop(
                    Timer.builder(METRIC_UPSTREAM)
                            .description("Time spent forwarding patients upstream")
                            .register(meterRegistry)
            );
        }
    }

    // ----------------------------------------------------
    // HELPERS
    // ----------------------------------------------------
    private Instant extractInstant(Observation o) {
        if (o.getIssued() != null) return o.getIssued().toInstant();
        if (o.getEffective() instanceof DateTimeType dt && dt.getValue() != null) {
            return dt.getValue().toInstant();
        }
        return Instant.EPOCH;
    }

    private int extractScore(Observation o) {
        if (o.hasExtension()) {
            for (Extension ext : o.getExtension()) {
                if (NEWS2_EXTENSION_URL.equals(ext.getUrl())
                        && ext.getValue() instanceof IntegerType it) {
                    return it.getValue();
                }
            }
        }
        if (o.hasValueIntegerType()) return o.getValueIntegerType().getValue();
        if (o.hasValueQuantity() && o.getValueQuantity().getValue() != null) {
            return o.getValueQuantity().getValue().intValue();
        }
        return 0;
    }
}
