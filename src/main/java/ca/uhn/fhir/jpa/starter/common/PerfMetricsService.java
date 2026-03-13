package ca.uhn.fhir.jpa.starter.common;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class PerfMetricsService {

    private final MeterRegistry registry;

    public final Timer dashboardTimer;
    public final Timer cityBlockTimer;
    public final Timer blockCorrelationTimer;
    public final Timer spatialAutocorrelationTimer;
    public final Timer patientBatchTimer;
    public final Timer observationBatchTimer;

    public final Counter patientIngestCounter;
    public final Counter observationIngestCounter;
    public final Counter conditionIngestCounter;
    public final Counter correlationRequestCounter;
    public final Counter spatialAutocorrelationRequestCounter;
    public final Counter correlationComputationCounter;
    public final Counter moranVariableComputationCounter;

    private final LongAdder recentPatients = new LongAdder();
    private final LongAdder recentObservations = new LongAdder();
    private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());

    public PerfMetricsService(MeterRegistry registry) {
        this.registry = registry;

        dashboardTimer = Timer.builder("fhir.custom.dashboard_stats")
                .description("Latency of Patient/$dashboard-stats")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        cityBlockTimer = Timer.builder("fhir.custom.city_blocks")
                .description("Latency of /city-blocks endpoints")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        blockCorrelationTimer = Timer.builder("fhir.custom.block_correlations")
            .description("Latency of /analytics/block-correlations")
            .publishPercentiles(0.50, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        spatialAutocorrelationTimer = Timer.builder("fhir.custom.spatial_autocorrelation")
            .description("Latency of /analytics/spatial-autocorrelation")
            .publishPercentiles(0.50, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        patientBatchTimer = Timer.builder("fhir.custom.patient_batch")
                .description("Latency of patient batch processor")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        observationBatchTimer = Timer.builder("fhir.custom.observation_batch")
                .description("Latency of observation batch processor")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        patientIngestCounter = Counter.builder("fhir.ingest.patients_total")
                .description("Total patient writes seen by interceptor")
                .register(registry);

        observationIngestCounter = Counter.builder("fhir.ingest.observations_total")
                .description("Total observation writes seen by interceptor")
                .register(registry);

        conditionIngestCounter = Counter.builder("fhir.ingest.conditions_total")
                .description("Total condition writes seen by interceptor")
                .register(registry);

        correlationRequestCounter = Counter.builder("fhir.analytics.correlation_requests_total")
            .description("Total block correlation requests")
            .register(registry);

        spatialAutocorrelationRequestCounter = Counter.builder("fhir.analytics.spatial_autocorrelation_requests_total")
            .description("Total spatial autocorrelation requests")
            .register(registry);

        correlationComputationCounter = Counter.builder("fhir.analytics.correlation_computations_total")
            .description("Total pairwise correlation computations executed")
            .register(registry);

        moranVariableComputationCounter = Counter.builder("fhir.analytics.moran_variable_computations_total")
            .description("Total Moran variable computations executed")
            .register(registry);

        Gauge.builder("fhir.ingest.patient_throughput_per_sec", this, metrics -> metrics.patientThroughput())
                .description("Patients per second over rolling window")
                .register(registry);

        Gauge.builder("fhir.ingest.observation_throughput_per_sec", this, metrics -> metrics.observationThroughput())
                .description("Observations per second over rolling window")
                .register(registry);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public void recordPatientIngested() {
        patientIngestCounter.increment();
        recentPatients.increment();
        maybeResetWindow();
    }

    public void recordObservationIngested() {
        observationIngestCounter.increment();
        recentObservations.increment();
        maybeResetWindow();
    }

    public void recordConditionIngested() {
        conditionIngestCounter.increment();
    }

    public void recordCorrelationRequest() {
        correlationRequestCounter.increment();
    }

    public void recordSpatialAutocorrelationRequest() {
        spatialAutocorrelationRequestCounter.increment();
    }

    public void recordCorrelationComputations(int count) {
        if (count > 0) {
            correlationComputationCounter.increment(count);
        }
    }

    public void recordMoranVariableComputations(int count) {
        if (count > 0) {
            moranVariableComputationCounter.increment(count);
        }
    }

    public double totalPatients() {
        return patientIngestCounter.count();
    }

    public double totalObservations() {
        return observationIngestCounter.count();
    }

    public double totalConditions() {
        return conditionIngestCounter.count();
    }

    public double patientThroughput() {
        long elapsedMs = System.currentTimeMillis() - windowStartMs.get();
        if (elapsedMs < 100) {
            return 0;
        }
        return recentPatients.doubleValue() / (elapsedMs / 1000.0);
    }

    public double observationThroughput() {
        long elapsedMs = System.currentTimeMillis() - windowStartMs.get();
        if (elapsedMs < 100) {
            return 0;
        }
        return recentObservations.doubleValue() / (elapsedMs / 1000.0);
    }

    private void maybeResetWindow() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs.get() > 60_000) {
            recentPatients.reset();
            recentObservations.reset();
            windowStartMs.set(now);
        }
    }
}