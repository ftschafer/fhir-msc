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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class CityNews2AggregationService {

    private static final Logger ourLog = LoggerFactory.getLogger(CityNews2AggregationService.class);

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
    private String locationCity;

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

        city = normalize(city);
        neighborhood = normalize(neighborhood);
        block = normalize(block);

        if (block == null) {
            ourLog.debug("Skipping patient {} aggregation because block is missing", patientId);
            return;
        }

        Extension news2Ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        int newScore = (news2Ext != null && news2Ext.getValue() instanceof IntegerType)
                ? ((IntegerType) news2Ext.getValue()).getValue()
                : 0;

        String oldBlock = pb == null ? null : pb.getBlock();
        Integer oldScore = pb == null ? null : pb.getLastScore();
        boolean moved = pb != null && !Objects.equals(oldBlock, block);

        // Domain invariant: patients do not move between locations.
        // If a different block arrives, keep the original block to avoid cross-location drift.
        if (moved && oldBlock != null) {
            ourLog.warn("Ignoring location move for patient {} ({} -> {}), keeping original block", patientId, oldBlock, block);
            block = oldBlock;
            moved = false;
        }

        if (pb == null) {
            pb = new PatientBlock(patientId, block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.persist(pb);
            adjustBlock(city, neighborhood, block, newScore, 1);
        } else if (moved) {
            if (oldScore != null && oldBlock != null) {
                adjustBlock(city, neighborhood, oldBlock, -oldScore, -1);
            }
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

            // Forward every successfully processed patient upstream.
            // If city was just added, forwarding is already scheduled in ensureCityIfMissing().
            if (!patientMutated) {
                scheduleUpstreamForwardAfterCommit((Patient) patient.copy());
            }
        
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

        boolean hasCity = locExt.getExtension().stream()
                .anyMatch(e -> CITY_URL.equals(e.getUrl()) && e.getValue() != null);

        if (!hasCity) {
            locExt.addExtension(new Extension()
                    .setUrl(CITY_URL)
                    .setValue(new StringType(locationCity)));

            patientDao().update(patient);

            scheduleUpstreamForwardAfterCommit((Patient) patient.copy());
            return true;
        }
        return false;
    }

    private void scheduleUpstreamForwardAfterCommit(Patient patientCopy) {
        if (patientCopy == null || upstreamForwarder == null) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Runnable task = () -> {
                            Timer.Sample asyncSample = Timer.start(meterRegistry);
                            try {
                                upstreamForwarder.upsertPatients(List.of(patientCopy));
                            } finally {
                                asyncSample.stop(meterRegistry.timer(METRIC_UPSTREAM));
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
    }

    private void adjustBlock(String city, String neighborhood, String block,
                             int scoreDelta, int patientDelta) {

        if (block == null || block.isBlank()) {
            ourLog.debug("Skipping adjustBlock because block is missing (city={}, neighborhood={})", city, neighborhood);
            return;
        }

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
}
