package ca.uhn.fhir.jpa.starter.common;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central metrics registry for all custom FHIR services.
 * Measures latency (p50/p95/p99), throughput, and error counts automatically
 * as real data flows through the server.
 *
 * Metrics are exposed at /actuator/metrics and aggregated at /perf-report.
 */
@Service
public class PerfMetricsService {

    private final MeterRegistry registry;

    // -- Timers (record latency for every call) --
    public final Timer dashboardTimer;
    public final Timer vitalHistoryTimer;
    public final Timer blockAggregateTimer;
    public final Timer patientWriteTimer;
    public final Timer observationWriteTimer;
    public final Timer conditionWriteTimer;
    public final Timer news2InterceptorTimer;
    public final Timer neighborhoodMeasureReportAggregationTimer;

    // -- Timers for KMeans/PCA analysis --
    public final Timer kmeansAnalyzeTimer;
    public final Timer kmeansSelectModelTimer;

    // -- Counters (ingestion volume) --
    public final Counter patientCreatedCounter;
    public final Counter observationCreatedCounter;
    public final Counter patientErrorCounter;
    public final Counter observationErrorCounter;
    public final Counter conditionCreatedCounter;
    public final Counter conditionErrorCounter;
    public final Counter neighborhoodMeasureReportsForwardedCounter;
    public final Counter news2CombinedForwardSuccessCounter;
    public final Counter news2CombinedForwardFallbackCounter;
    public final Counter kmeansIterationCounter;

    // -- Gauges (track window throughput) --
    private final LongAdder recentPatients  = new LongAdder();
    private final LongAdder recentObs       = new LongAdder();
    private final AtomicLong windowStartMs  = new AtomicLong(System.currentTimeMillis());

    public PerfMetricsService(MeterRegistry registry) {
        this.registry = registry;

        // Percentile histograms capture p50, p95, p99 server-side
        dashboardTimer = Timer.builder("fhir.custom.dashboard_stats")
                .description("Latency of $dashboard-stats operation")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        vitalHistoryTimer = Timer.builder("fhir.custom.vital_history")
                .description("Latency of $vital-history operation")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        blockAggregateTimer = Timer.builder("fhir.custom.block_aggregate")
                .description("Latency of /block-details/{block} DB query")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        patientWriteTimer = Timer.builder("fhir.ingest.patient_write")
                .description("End-to-end latency of Patient create/update including interceptor chain")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        observationWriteTimer = Timer.builder("fhir.ingest.observation_write")
                .description("End-to-end latency of Observation create/update including interceptor chain")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        conditionWriteTimer = Timer.builder("fhir.ingest.condition_write")
            .description("End-to-end latency of Condition create/update including interceptor chain")
            .publishPercentiles(0.50, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        news2InterceptorTimer = Timer.builder("fhir.interceptor.news2_aggregation")
                .description("Latency of NEWS2 aggregation interceptor per event")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        neighborhoodMeasureReportAggregationTimer = Timer.builder("fhir.custom.neighborhood_measure_report_aggregation")
            .description("Latency of neighborhood MeasureReport aggregation job")
            .publishPercentiles(0.50, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        kmeansAnalyzeTimer = Timer.builder("fhir.custom.kmeans_analyze")
                .description("Total latency of BlockSocioeconomicAnalysisService.analyze()")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        kmeansSelectModelTimer = Timer.builder("fhir.custom.kmeans_select_model")
                .description("Latency of KMeans model selection loop (all k x runs iterations)")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        patientCreatedCounter     = Counter.builder("fhir.ingest.patients_total").description("Total patients ingested").register(registry);
        observationCreatedCounter = Counter.builder("fhir.ingest.observations_total").description("Total observations ingested").register(registry);
        patientErrorCounter       = Counter.builder("fhir.ingest.patient_errors").description("Patient write errors").register(registry);
        observationErrorCounter   = Counter.builder("fhir.ingest.observation_errors").description("Observation write errors").register(registry);
        conditionCreatedCounter   = Counter.builder("fhir.ingest.conditions_total").description("Total conditions ingested").register(registry);
        conditionErrorCounter     = Counter.builder("fhir.ingest.condition_errors").description("Condition write errors").register(registry);
        neighborhoodMeasureReportsForwardedCounter = Counter.builder("fhir.upstream.neighborhood_measure_reports_forwarded_total")
            .description("Total neighborhood MeasureReports forwarded upstream")
            .register(registry);
        news2CombinedForwardSuccessCounter = Counter.builder("fhir.upstream.news2_combined_forward_success_total")
            .description("Total NEWS2 combined patient+observation upstream forwards that succeeded")
            .register(registry);
        news2CombinedForwardFallbackCounter = Counter.builder("fhir.upstream.news2_combined_forward_fallback_total")
            .description("Total NEWS2 combined forwards that fell back to separate patient and observation calls")
            .register(registry);
        kmeansIterationCounter    = Counter.builder("fhir.custom.kmeans_iterations_total").description("Total KMeans (k x run) iterations executed").register(registry);

        // Throughput gauges (patients/sec and obs/sec in rolling 60s window)
        Gauge.builder("fhir.ingest.patient_throughput_per_sec", this, s -> s.currentPatientThroughput())
                .description("Patients ingested per second (rolling window)")
                .register(registry);

        Gauge.builder("fhir.ingest.observation_throughput_per_sec", this, s -> s.currentObsThroughput())
                .description("Observations ingested per second (rolling window)")
                .register(registry);
    }

    /** Call on every successful patient write to track throughput */
    public void recordPatientIngested() {
        patientCreatedCounter.increment();
        recentPatients.increment();
        maybeResetWindow();
    }

    /** Call on every successful observation write to track throughput */
    public void recordObservationIngested() {
        observationCreatedCounter.increment();
        recentObs.increment();
        maybeResetWindow();
    }

    /** Call on every successful condition write */
    public void recordConditionIngested() {
        conditionCreatedCounter.increment();
    }

    /** Call on condition write failure */
    public void recordConditionError() {
        conditionErrorCounter.increment();
    }

    /** Call when neighborhood MeasureReports are forwarded upstream */
    public void recordNeighborhoodMeasureReportsForwarded(int count) {
        if (count > 0) {
            neighborhoodMeasureReportsForwardedCounter.increment(count);
        }
    }

    public void recordNews2CombinedForwardSuccess() {
        news2CombinedForwardSuccessCounter.increment();
    }

    public void recordNews2CombinedForwardFallback() {
        news2CombinedForwardFallbackCounter.increment();
    }

    private double currentPatientThroughput() {
        long elapsedMs = System.currentTimeMillis() - windowStartMs.get();
        if (elapsedMs < 100) return 0;
        return recentPatients.doubleValue() / (elapsedMs / 1000.0);
    }

    private double currentObsThroughput() {
        long elapsedMs = System.currentTimeMillis() - windowStartMs.get();
        if (elapsedMs < 100) return 0;
        return recentObs.doubleValue() / (elapsedMs / 1000.0);
    }

    /** Reset rolling window every 60 seconds */
    private void maybeResetWindow() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs.get() > 60_000) {
            recentPatients.reset();
            recentObs.reset();
            windowStartMs.set(now);
        }
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    /** Snapshot of total counts regardless of window */
    public double totalPatients()     { return patientCreatedCounter.count(); }
    public double totalObservations() { return observationCreatedCounter.count(); }
    public double totalConditions()   { return conditionCreatedCounter.count(); }
    public double totalPatientErrors(){ return patientErrorCounter.count(); }
    public double totalObsErrors()    { return observationErrorCounter.count(); }
    public double totalConditionErrors() { return conditionErrorCounter.count(); }
    public double totalNeighborhoodMeasureReportsForwarded() { return neighborhoodMeasureReportsForwardedCounter.count(); }
    public double totalNews2CombinedForwardSuccess() { return news2CombinedForwardSuccessCounter.count(); }
    public double totalNews2CombinedForwardFallback() { return news2CombinedForwardFallbackCounter.count(); }
    public double totalKmeansIterations() { return kmeansIterationCounter.count(); }
    public double patientThroughput() { return currentPatientThroughput(); }
    public double obsThroughput()     { return currentObsThroughput(); }

    /** Returns Duration snapshot for a timer: [p50, p95, p99] in milliseconds */
    public double[] percentiles(Timer timer) {
        return new double[]{
            timer.percentile(0.50, java.util.concurrent.TimeUnit.MILLISECONDS),
            timer.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS),
            timer.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS)
        };
    }
}
