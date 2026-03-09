package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class CityNews2AggregationService {

    // ------------------------
    // Metric names
    // ------------------------
    private static final String METRIC_TOTAL = "fhir.patient.processing.total";
    private static final String METRIC_READ = "fhir.patient.processing.read";
    private static final String METRIC_AGGREGATE = "fhir.patient.processing.aggregate";
    private static final String METRIC_UPSTREAM = "fhir.patient.processing.upstream";
    private static final String METRIC_OUTCOME = "fhir.patient.processing.outcome";
    
    // Bundle processing metrics
    private static final String METRIC_BUNDLE_TIMER = "fhir_news2_bundle_processing";
    private static final String METRIC_OBSERVATIONS = "fhir_news2_bundle_observations_count";
    private static final String METRIC_PATIENTS = "fhir_news2_bundle_patients_count";
    
    // Aggregate upsert metrics
    private static final String METRIC_UPSERT_TIMER = "fhir_news2_aggregate_upsert";
    
    // Patient update metrics
    private static final String METRIC_PATIENT_UPDATE_TIMER = "fhir_news2_patient_update";
    private static final String METRIC_PATIENT_UPDATE_ERROR = "fhir_news2_patient_update_errors";
    private static final String METRIC_PATIENT_UPDATE_SUCCESS = "fhir_news2_patient_update_success";

    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String NEIGH_URL = "neighborhood";
    private static final String CITY_URL = "city";
    private static final String BLOCK_URL = "block";

    @Value("${location.city}")
    private String configuredCity;

    private final MeterRegistry meterRegistry;

    @PersistenceContext
    private EntityManager em;

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;
    private final UpstreamForwarder upstreamForwarder;
    private final ThreadPoolTaskExecutor aggExecutor;

    public CityNews2AggregationService(
            DaoRegistry daoRegistry,
            FhirContext fhirContext,
            UpstreamForwarder upstreamForwarder,
            ThreadPoolTaskExecutor aggExecutor,
            MeterRegistry meterRegistry
    ) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
        this.upstreamForwarder = upstreamForwarder;
        this.aggExecutor = aggExecutor;
        this.meterRegistry = meterRegistry;
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    // ----------------------------------------------------
    // MAIN ENTRY POINT
    // ----------------------------------------------------
    @Transactional
    public void updateCityForPatient(String patientId) {

        Timer.Sample totalSample = Timer.start(meterRegistry);

        boolean success = false;
        String outcome = "success";

        try {
            // ---------- FHIR READ ----------
            Timer.Sample readSample = Timer.start(meterRegistry);
            Patient patient = patientDao().read(
                    fhirContext.getVersion().newIdType("Patient", patientId), null
            );
            readSample.stop(meterRegistry.timer(METRIC_READ));

            // ---------- AGGREGATION ----------
            Timer.Sample aggSample = Timer.start(meterRegistry);
            processPatient(patient);
            aggSample.stop(meterRegistry.timer(METRIC_AGGREGATE));

            success = true;

        } catch (Exception e) {
            outcome = e.getClass().getSimpleName();
            throw e;

        } finally {
            boolean finalSuccess = success;
            String finalOutcome = outcome;

            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        totalSample.stop(
                            meterRegistry.timer(METRIC_TOTAL)
                        );

                        meterRegistry.counter(
                                METRIC_OUTCOME,
                                "result", finalSuccess ? "success" : "error",
                                "reason", finalOutcome
                        ).increment();
                    }
                }
            );
        }
    }

    // ----------------------------------------------------
    // CORE BUSINESS LOGIC (unchanged, just extracted)
    // ----------------------------------------------------
    private void processPatient(Patient patient) {

        Timer.Sample patientUpdateSample = Timer.start(meterRegistry);
        boolean updateSuccess = false;
        
        try {
            Date patientLastUpdated = Optional.ofNullable(patient.getMeta())
                    .map(Meta::getLastUpdated)
                    .orElse(null);

            String patientId = patient.getIdElement().getIdPart();
            PatientBlock pb = em.find(PatientBlock.class, patientId);

            boolean patientMutated = ensureCityIfMissing(patient);
        if (patientMutated) {
            try {
                patient = patientDao().read(patient.getIdElement().withVersion(null), null);
                patientLastUpdated = Optional.ofNullable(patient.getMeta())
                        .map(Meta::getLastUpdated)
                        .orElse(patientLastUpdated);
            } catch (Exception ignored) { }
        }

        if (pb != null && pb.getLastUpdated() != null && patientLastUpdated != null
                && !patientLastUpdated.after(pb.getLastUpdated())) {
            return;
        }

        String neighborhood = null;
        String block = null;
        String city = null;

        Extension locExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locExt != null) {
            for (Extension nested : locExt.getExtension()) {
                if (CITY_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                    city = nested.getValue().primitiveValue();
                } else if (NEIGH_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                    neighborhood = nested.getValue().primitiveValue();
                } else if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                    block = nested.getValue().primitiveValue();
                }
            }
        }

        city = resolveConfiguredCity();
        neighborhood = normalize(neighborhood);
        block = normalize(block);

        Extension news2Ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        int newScore = (news2Ext != null && news2Ext.getValue() instanceof IntegerType)
                ? ((IntegerType) news2Ext.getValue()).getValue()
                : 0;

        String oldBlock = pb == null ? null : pb.getBlock();
        Integer oldScore = pb == null ? null : pb.getLastScore();
        boolean moved = pb != null && !Objects.equals(oldBlock, block);

        if (pb == null) {
            pb = new PatientBlock(patientId, block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.persist(pb);
            adjustBlock(city, neighborhood, block, newScore, 1);
        } else if (moved) {
            if (oldScore != null) adjustBlock(city, oldBlock, null, -oldScore, -1);
            pb.update(block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.merge(pb);
            adjustBlock(city, neighborhood, block, newScore, 1);
        } else {
            int delta = (oldScore == null ? newScore : newScore - oldScore);
            pb.update(block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.merge(pb);
            adjustBlock(city, neighborhood, block, delta, 0);
        }

        em.flush();
        
            updateSuccess = true;
            meterRegistry.counter(METRIC_PATIENT_UPDATE_SUCCESS).increment();
            
        } catch (Exception e) {
            meterRegistry.counter(METRIC_PATIENT_UPDATE_ERROR).increment();
            throw e;
        } finally {
            patientUpdateSample.stop(meterRegistry.timer(METRIC_PATIENT_UPDATE_TIMER));
        }
    }

    // ----------------------------------------------------
    // ENSURE CITY + ASYNC UPSTREAM METRICS
    // ----------------------------------------------------
    private boolean ensureCityIfMissing(Patient patient) {

        Extension locExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locExt == null) {
            locExt = new Extension(LOCATION_EXTENSION_URL);
            patient.addExtension(locExt);
        }

        String fixedCity = resolveConfiguredCity();
        Extension cityExt = locExt.getExtension().stream()
                .filter(e -> CITY_URL.equals(e.getUrl()))
                .findFirst()
                .orElse(null);

        String currentCity = null;
        if (cityExt != null && cityExt.getValue() != null) {
            currentCity = normalize(cityExt.getValue().primitiveValue());
        }

        boolean needsUpdate = currentCity == null || !fixedCity.equalsIgnoreCase(currentCity);

        if (needsUpdate) {
            if (cityExt == null) {
                locExt.addExtension(new Extension()
                        .setUrl(CITY_URL)
                        .setValue(new StringType(fixedCity)));
            } else {
                cityExt.setValue(new StringType(fixedCity));
            }

            patientDao().update(patient);

            Patient copy = (Patient) patient.copy();

            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Runnable task = () -> {
                            Timer.Sample asyncSample = Timer.start(meterRegistry);
                            try {
                                if (upstreamForwarder != null) {
                                    upstreamForwarder.upsertPatients(List.of(copy));
                                }
                            } finally {
                                asyncSample.stop(
                                    meterRegistry.timer(METRIC_UPSTREAM)
                                );
                            }
                        };

                        if (aggExecutor != null) {
                            aggExecutor.execute(task);
                        } else {
                            task.run();
                        }
                    }
                }
            );
            return true;
        }
        return false;
    }

    private void adjustBlock(String city, String neighborhood, String block,
                             int scoreDelta, int patientDelta) {

        Timer.Sample upsertSample = Timer.start(meterRegistry);
        try {
            BlockKey key = new BlockKey(city, neighborhood, block);
            BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, key);

            if (agg == null) {
                agg = new BlockNews2Aggregate(city, neighborhood, block);
                agg.applyDelta(scoreDelta, patientDelta);
                em.persist(agg);
            } else {
                agg.applyDelta(scoreDelta, patientDelta);
                em.merge(agg);
            }
        } finally {
            upsertSample.stop(meterRegistry.timer(METRIC_UPSERT_TIMER));
        }
    }

    @PostConstruct
    public void registerGauges() {

        if (aggExecutor == null || aggExecutor.getThreadPoolExecutor() == null) {
            return;
        }

        ThreadPoolExecutor executor = aggExecutor.getThreadPoolExecutor();

        // Queue size (backpressure indicator)
        meterRegistry.gauge(
            "fhir.patient.aggregation.queue.size",
            executor.getQueue(),
            q -> q.size()
        );

        // Active threads (saturation)
        meterRegistry.gauge(
            "fhir.patient.aggregation.active.threads",
            executor,
            ThreadPoolExecutor::getActiveCount
        );

        // Pool size (capacity reference)
        meterRegistry.gauge(
            "fhir.patient.aggregation.pool.size",
            executor,
            ThreadPoolExecutor::getPoolSize
        );
    }


    private String normalize(String s) {
        if (s == null) return null;
        s = s.replace('\u00A0', ' ')
             .replace('\u2007', ' ')
             .replace('\u202F', ' ')
             .trim();
        return s.isEmpty() ? null : s;
    }

    private String resolveConfiguredCity() {
        String city = normalize(configuredCity);
        if (city == null) {
            throw new IllegalStateException("Required property 'location.city' is missing or blank");
        }
        return city;
    }
}
