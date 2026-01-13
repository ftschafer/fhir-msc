package ca.uhn.fhir.jpa.starter.common;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class News2AggregationService {

    private static final Logger logger = LoggerFactory.getLogger(News2AggregationService.class);

    // ---- metrics (names only) ----
    private static final String METRIC_BUNDLE_TIMER = "fhir_news2_bundle_processing";
    private static final String METRIC_OBSERVATIONS = "fhir_news2_bundle_observations_count";
    private static final String METRIC_PATIENTS = "fhir_news2_bundle_patients_count";
    private static final String METRIC_UPSERT_TIMER = "fhir_news2_aggregate_upsert";
    private static final String METRIC_PATIENT_UPDATE_TIMER = "fhir_news2_patient_update";
    private static final String METRIC_PATIENT_UPDATE_ERROR = "fhir_news2_patient_update_errors";

    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final Set<String> LOINC_CODES =
            Set.of("8867-4","9279-1","8310-5","59408-5","8480-6");

    @PersistenceContext
    private EntityManager em;

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;
    private final MeterRegistry meterRegistry;

    public News2AggregationService(
            DaoRegistry daoRegistry,
            FhirContext fhirContext,
            MeterRegistry meterRegistry
    ) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
        this.meterRegistry = meterRegistry;
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    // Call this once per received bundle.
    @Transactional
    public void processBundleObservations(List<Observation> observations) {

        if (observations == null || observations.isEmpty()) return;

        Timer.Sample bundleSample = Timer.start(meterRegistry);
        meterRegistry.counter(METRIC_OBSERVATIONS).increment(observations.size());

        try {
            // 1) keep only newest observation per patient+code from this bundle
            Map<String, Map<String, Observation>> newest = new HashMap<>();

            for (Observation o : observations) {
                if (o.getSubject() == null || o.getSubject().getReference() == null) continue;
                String ref = o.getSubject().getReference();
                if (!ref.startsWith("Patient/")) continue;
                String pid = ref.substring("Patient/".length());

                if (o.getCode() == null || o.getCode().getCoding().isEmpty()) continue;
                String code = o.getCode().getCodingFirstRep().getCode();
                if (!LOINC_CODES.contains(code)) continue;

                Instant obsInstant = extractObservationInstant(o);
                newest.computeIfAbsent(pid, p -> new HashMap<>());
                Map<String, Observation> byCode = newest.get(pid);
                Observation cur = byCode.get(code);
                if (cur == null || obsInstant.isAfter(extractObservationInstant(cur))) {
                    byCode.put(code, o);
                }
            }

            if (newest.isEmpty()) return;

            meterRegistry.counter(METRIC_PATIENTS).increment(newest.size());

            // 2) upsert aggregation rows
            Timer.Sample upsertSample = Timer.start(meterRegistry);
            Set<String> affectedPatients = new HashSet<>();

            for (var e : newest.entrySet()) {
                String pid = e.getKey();
                for (var ce : e.getValue().entrySet()) {
                    Observation o = ce.getValue();
                    upsertAggregate(
                            pid,
                            ce.getKey(),
                            extractNews2Score(o),
                            extractObservationInstant(o)
                    );
                    affectedPatients.add(pid);
                }
            }
            upsertSample.stop(
                    Timer.builder(METRIC_UPSERT_TIMER)
                            .description("Time spent upserting NEWS2 aggregates")
                            .register(meterRegistry)
            );

            // 3) compute totals
            List<Object[]> rows = em.createQuery(
                    "SELECT a.id.patientId, SUM(a.score) " +
                    "FROM News2Aggregate a WHERE a.id.patientId IN :p " +
                    "GROUP BY a.id.patientId",
                    Object[].class)
                .setParameter("p", affectedPatients)
                .getResultList();

            Map<String, Integer> totals = new HashMap<>();
            for (Object[] r : rows) {
                totals.put((String) r[0], ((Number) r[1]).intValue());
            }

            // 4) update Patient extension only if changed
            for (String pid : affectedPatients) {
                Timer.Sample patientSample = Timer.start(meterRegistry);
                try {
                    Patient patient = patientDao().read(
                            fhirContext.getVersion().newIdType("Patient", pid), null);

                    Extension ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
                    Integer old =
                            ext != null && ext.getValue() instanceof IntegerType
                                    ? ((IntegerType) ext.getValue()).getValue()
                                    : null;

                    int total = totals.getOrDefault(pid, 0);

                    if (!Objects.equals(old, total)) {
                        if (ext == null) {
                            patient.addExtension(
                                    new Extension(NEWS2_EXTENSION_URL, new IntegerType(total)));
                        } else {
                            ext.setValue(new IntegerType(total));
                        }
                        patientDao().update(patient);
                    }

                } catch (Exception ex) {
                    meterRegistry.counter(METRIC_PATIENT_UPDATE_ERROR).increment();
                    logger.warn("Failed updating patient {} NEWS2", pid, ex);
                } finally {
                    patientSample.stop(
                            Timer.builder(METRIC_PATIENT_UPDATE_TIMER)
                                    .description("Time spent updating patient NEWS2 extension")
                                    .register(meterRegistry)
                    );
                }
            }

        } finally {
            bundleSample.stop(
                    Timer.builder(METRIC_BUNDLE_TIMER)
                            .description("End-to-end NEWS2 bundle processing latency")
                            .register(meterRegistry)
            );
        }
    }

    private Instant extractObservationInstant(Observation o) {
        if (o.getEffective() instanceof DateTimeType) {
            Date d = ((DateTimeType) o.getEffective()).getValue();
            return d == null ? Instant.EPOCH : d.toInstant();
        }
        if (o.getIssued() != null) return o.getIssued().toInstant();
        return Instant.EPOCH;
    }

    private int extractNews2Score(Observation o) {
        if (o.hasExtension()) {
            for (Extension ext : o.getExtension()) {
                if (NEWS2_EXTENSION_URL.equals(ext.getUrl())
                        && ext.getValue() instanceof IntegerType) {
                    return ((IntegerType) ext.getValue()).getValue();
                }
            }
        }
        if (o.hasValueIntegerType()) return o.getValueIntegerType().getValue();
        if (o.hasValueQuantity() && o.getValueQuantity().getValue() != null)
            return o.getValueQuantity().getValue().intValue();
        return 0;
    }

    private void upsertAggregate(String patientId, String code, int value, Instant obsAt) {
        News2Aggregate.Id id = new News2Aggregate.Id(patientId, code);
        News2Aggregate a = em.find(News2Aggregate.class, id);
        if (a == null) {
            em.persist(new News2Aggregate(patientId, code, value, obsAt));
            return;
        }
        if (a.getObservedAt() == null || obsAt.isAfter(a.getObservedAt())) {
            a.setValue(value);
            a.setObservedAt(obsAt);
            em.merge(a);
        }
    }

    @Transactional
    public Set<String> processBundleObservationsReturningPatients(List<Observation> observations) {
        processBundleObservations(observations);
        Set<String> ids = new HashSet<>();
        for (Observation o : observations) {
            if (o.getSubject() != null
                    && o.getSubject().getReference() != null
                    && o.getSubject().getReference().startsWith("Patient/")) {
                ids.add(o.getSubject().getReference().substring("Patient/".length()));
            }
        }
        return ids;
    }
}
