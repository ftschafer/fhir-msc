package ca.uhn.fhir.jpa.starter.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Central Micrometer metrics registry for the city-branch analytics pipeline.
 *
 * All metrics use the fhir.city.* namespace. Public Timer fields allow callers
 * to use try/finally patterns (e.g. {@code sample.stop(perfMetrics.neighCorrelationTimer)}).
 * Counter increments are exposed via named helper methods.
 */
@Service
public class PerfMetricsService {

    // ── Correlation analytics (CityCorrelationController) ─────────────────────
    public final Timer neighCorrelationTimer;
    public final Timer neighSpatialAutocorrelationTimer;

    // ── Vital-sign aggregation (NeighborhoodVitalAggregationService) ──────────
    public final Timer neighVitalAggregationTimer;

        // ── Observation ingestion/processing ──────────────────────────────────────
        public final Timer observationProcessingTimer;

    // ── MeasureReport aggregation pipeline (scheduled) ────────────────────────
    public final Timer neighMeasureReportAggregationTimer;   // block → neighbourhood
    public final Timer cityMeasureReportAggregationTimer;    // neighbourhood → city

    // ── Dashboard provider ─────────────────────────────────────────────────────
    public final Timer dashboardRequestTimer;
    public final Timer dashboardStatsSummaryTimer;

    // ── Socioeconomic clustering (BlockSocioeconomicAnalysisService) ──────────
    public final Timer kmeansAnalyzeTimer;
    public final Timer kmeansSelectModelTimer;
    public final Counter kmeansIterationCounter;

    // ── Carried-over block-level correlation (BlockCorrelationController) ──────
    public final Timer blockCorrelationTimer;
    public final Timer spatialAutocorrelationTimer;

    // ── Counters (private – exposed via methods) ──────────────────────────────
    private final Counter dashboardCacheHits;
    private final Counter neighCorrelationRequests;
    private final Counter neighCorrelationComputations;
    private final Counter neighSpatialAutocorrelationRequests;
    private final Counter neighMoranVariableComputations;
        private final Counter observationIngested;
        private final Counter observationProcessingErrors;
    private final Counter conditionIngested;
    private final Counter blockCorrelationRequests;
    private final Counter blockCorrelationComputations;
    private final Counter spatialAutocorrelationRequests;
    private final Counter moranVariableComputations;

    public PerfMetricsService(MeterRegistry registry) {

        // Dashboard
        dashboardRequestTimer = Timer.builder("fhir.city.dashboard.request.duration")
                .description("Latency of full dashboard stat calculation")
                .register(registry);

        dashboardStatsSummaryTimer = Timer.builder("fhir.city.dashboard.stats_summary.duration")
                .description("Latency of statistical summary computation in dashboard")
                .register(registry);

        dashboardCacheHits = Counter.builder("fhir.city.dashboard.cache.hits.total")
                .description("Dashboard responses served from cache")
                .register(registry);

        // Correlation analytics
        neighCorrelationTimer = Timer.builder("fhir.city.correlation.duration")
                .description("Latency of city-level neighbourhood correlation endpoint")
                .register(registry);

        neighSpatialAutocorrelationTimer = Timer.builder("fhir.city.spatial_autocorrelation.duration")
                .description("Latency of city-level neighbourhood Moran's-I endpoint")
                .register(registry);

        // Vital-sign aggregation
        neighVitalAggregationTimer = Timer.builder("fhir.city.vital_aggregation.duration")
                .description("Latency of neighbourhood vital-sign aggregation pass")
                .register(registry);

        // Observation ingestion/processing
        observationProcessingTimer = Timer.builder("fhir.city.observation.processing.duration")
                .description("Latency of NEWS2 observation processing after transaction commit")
                .register(registry);

        // MeasureReport aggregation pipeline
        neighMeasureReportAggregationTimer = Timer.builder("fhir.city.neigh_report_aggregation.duration")
                .description("Latency of scheduled block→neighbourhood MeasureReport aggregation")
                .register(registry);

        cityMeasureReportAggregationTimer = Timer.builder("fhir.city.report_aggregation.duration")
                .description("Latency of scheduled neighbourhood→city MeasureReport aggregation")
                .register(registry);

        // Socioeconomic clustering
        kmeansAnalyzeTimer = Timer.builder("fhir.city.kmeans.analyze.duration")
                .description("Latency of full k-means socioeconomic analysis")
                .register(registry);

        kmeansSelectModelTimer = Timer.builder("fhir.city.kmeans.select_model.duration")
                .description("Latency of best-k model selection in k-means")
                .register(registry);

        kmeansIterationCounter = Counter.builder("fhir.city.kmeans.iterations.total")
                .description("Total k-means iterations executed")
                .register(registry);

        // Block-level correlation (carried over)
        blockCorrelationTimer = Timer.builder("fhir.city.block_correlation.duration")
                .description("Latency of block-level correlation endpoint")
                .register(registry);

        spatialAutocorrelationTimer = Timer.builder("fhir.city.block_spatial_autocorrelation.duration")
                .description("Latency of block-level Moran's-I endpoint")
                .register(registry);

        // Correlation counters
        neighCorrelationRequests = Counter.builder("fhir.city.correlation.requests.total")
                .description("City correlation endpoint invocations")
                .register(registry);

        neighCorrelationComputations = Counter.builder("fhir.city.correlation.computations.total")
                .description("Neighbourhood correlation pairs computed")
                .register(registry);

        neighSpatialAutocorrelationRequests = Counter.builder("fhir.city.spatial_autocorrelation.requests.total")
                .description("City spatial-autocorrelation endpoint invocations")
                .register(registry);

        neighMoranVariableComputations = Counter.builder("fhir.city.moran.computations.total")
                .description("Variables for which neighbourhood Moran's I was computed")
                .register(registry);

        observationIngested = Counter.builder("fhir.city.observation.ingested.total")
                .description("Observations ingested into NEWS2 pipeline")
                .register(registry);

        observationProcessingErrors = Counter.builder("fhir.city.observation.processing.errors.total")
                .description("Errors during asynchronous NEWS2 observation processing")
                .register(registry);

        // Condition ingestion
        conditionIngested = Counter.builder("fhir.city.condition.ingested.total")
                .description("Conditions ingested and forwarded upstream")
                .register(registry);

        // Block correlation counters (carried over)
        blockCorrelationRequests = Counter.builder("fhir.city.block_correlation.requests.total")
                .description("Block correlation endpoint invocations")
                .register(registry);

        blockCorrelationComputations = Counter.builder("fhir.city.block_correlation.computations.total")
                .description("Block correlation pairs computed")
                .register(registry);

        spatialAutocorrelationRequests = Counter.builder("fhir.city.block_spatial_autocorrelation.requests.total")
                .description("Block spatial-autocorrelation endpoint invocations")
                .register(registry);

        moranVariableComputations = Counter.builder("fhir.city.block_moran.computations.total")
                .description("Variables for which block Moran's I was computed")
                .register(registry);
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────────

    public void recordDashboardCacheHit()                 { dashboardCacheHits.increment(); }

    // ── City correlation helpers ──────────────────────────────────────────────

    public void recordNeighCorrelationRequest()                { neighCorrelationRequests.increment(); }
    public void recordNeighCorrelationComputations(int count)  { neighCorrelationComputations.increment(count); }
    public void recordNeighSpatialAutocorrelationRequest()     { neighSpatialAutocorrelationRequests.increment(); }
    public void recordNeighMoranVariableComputations(int count){ neighMoranVariableComputations.increment(count); }

        // ── Observation-ingestion helpers ─────────────────────────────────────────

        public void recordObservationIngested()                    { observationIngested.increment(); }
        public void recordObservationIngested(int count)           { if (count > 0) observationIngested.increment(count); }
        public void recordObservationProcessingError()             { observationProcessingErrors.increment(); }

    // ── Condition-ingestion helper ────────────────────────────────────────────

    public void recordConditionIngested()                      { conditionIngested.increment(); }

    // ── Block correlation helpers (carried over) ──────────────────────────────

    public void recordCorrelationRequest()                { blockCorrelationRequests.increment(); }
    public void recordCorrelationComputations(int count)  { blockCorrelationComputations.increment(count); }
    public void recordSpatialAutocorrelationRequest()     { spatialAutocorrelationRequests.increment(); }
    public void recordMoranVariableComputations(int count){ moranVariableComputations.increment(count); }
}
