package ca.uhn.fhir.jpa.starter.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Central Micrometer metrics registry for the city-branch analytics pipeline.
 *
 * Public Timer fields are stored so callers can use try/finally patterns
 * (e.g. {@code sample.stop(perfMetrics.neighCorrelationTimer)}).
 * Counter increments are exposed via named helper methods.
 */
@Service
public class PerfMetricsService {

    // ── Block-level analytics (BlockCorrelationController) ────────────────────
    public final Timer blockCorrelationTimer;
    public final Timer spatialAutocorrelationTimer;

    // ── K-means socioeconomic (BlockSocioeconomicAnalysisService) ─────────────
    public final Timer kmeansAnalyzeTimer;
    public final Timer kmeansSelectModelTimer;
    public final Counter kmeansIterationCounter;

    // ── City/neighbourhood analytics (CityCorrelationController) ─────────────
    public final Timer neighCorrelationTimer;
    public final Timer neighSpatialAutocorrelationTimer;

    // ── Neighbourhood vital-sign aggregation ──────────────────────────────────
    public final Timer neighVitalAggregationTimer;

    // ── Neighbourhood MeasureReport aggregation (scheduled) ───────────────────
    public final Timer neighMeasureReportAggregationTimer;

    // ── Counters (private – exposed via methods) ──────────────────────────────
    private final Counter blockCorrelationRequests;
    private final Counter blockCorrelationComputations;
    private final Counter spatialAutocorrelationRequests;
    private final Counter moranVariableComputations;
    private final Counter conditionIngested;
    private final Counter neighCorrelationRequests;
    private final Counter neighCorrelationComputations;
    private final Counter neighSpatialAutocorrelationRequests;
    private final Counter neighMoranVariableComputations;

    public PerfMetricsService(MeterRegistry registry) {

        // Block-level timers
        blockCorrelationTimer = Timer.builder("fhir.block.correlation.duration")
                .description("Latency of block-level correlation endpoint")
                .register(registry);

        spatialAutocorrelationTimer = Timer.builder("fhir.block.spatial_autocorrelation.duration")
                .description("Latency of block-level Moran's-I endpoint")
                .register(registry);

        // K-means timers / counter
        kmeansAnalyzeTimer = Timer.builder("fhir.block.kmeans.analyze.duration")
                .description("Latency of full k-means socioeconomic analysis")
                .register(registry);

        kmeansSelectModelTimer = Timer.builder("fhir.block.kmeans.select_model.duration")
                .description("Latency of best-k model selection in k-means")
                .register(registry);

        kmeansIterationCounter = Counter.builder("fhir.block.kmeans.iterations.total")
                .description("Total k-means iterations executed")
                .register(registry);

        // City/neighbourhood timers
        neighCorrelationTimer = Timer.builder("fhir.neigh.correlation.duration")
                .description("Latency of neighbourhood-level correlation endpoint")
                .register(registry);

        neighSpatialAutocorrelationTimer = Timer.builder("fhir.neigh.spatial_autocorrelation.duration")
                .description("Latency of neighbourhood-level Moran's-I endpoint")
                .register(registry);

        neighVitalAggregationTimer = Timer.builder("fhir.neigh.vital_aggregation.duration")
                .description("Latency of neighbourhood vital-sign aggregation pass")
                .register(registry);

        neighMeasureReportAggregationTimer = Timer.builder("fhir.neigh.measure_report_aggregation.duration")
                .description("Latency of scheduled neighbourhood MeasureReport aggregation")
                .register(registry);

        // Block-level counters
        blockCorrelationRequests = Counter.builder("fhir.block.correlation.requests.total")
                .description("Block correlation endpoint invocations")
                .register(registry);

        blockCorrelationComputations = Counter.builder("fhir.block.correlation.computations.total")
                .description("Individual block correlation pairs computed")
                .register(registry);

        spatialAutocorrelationRequests = Counter.builder("fhir.block.spatial_autocorrelation.requests.total")
                .description("Block spatial-autocorrelation endpoint invocations")
                .register(registry);

        moranVariableComputations = Counter.builder("fhir.block.moran.variable_computations.total")
                .description("Variables for which block Moran's I was computed")
                .register(registry);

        // Generic condition counter
        conditionIngested = Counter.builder("fhir.condition.ingested.total")
                .description("Conditions ingested and forwarded upstream")
                .register(registry);

        // City/neighbourhood counters
        neighCorrelationRequests = Counter.builder("fhir.neigh.correlation.requests.total")
                .description("Neighbourhood correlation endpoint invocations")
                .register(registry);

        neighCorrelationComputations = Counter.builder("fhir.neigh.correlation.computations.total")
                .description("Individual neighbourhood correlation pairs computed")
                .register(registry);

        neighSpatialAutocorrelationRequests = Counter.builder("fhir.neigh.spatial_autocorrelation.requests.total")
                .description("Neighbourhood spatial-autocorrelation endpoint invocations")
                .register(registry);

        neighMoranVariableComputations = Counter.builder("fhir.neigh.moran.variable_computations.total")
                .description("Variables for which neighbourhood Moran's I was computed")
                .register(registry);
    }

    // ── Block-level helpers ───────────────────────────────────────────────────

    public void recordCorrelationRequest()                { blockCorrelationRequests.increment(); }
    public void recordCorrelationComputations(int count)  { blockCorrelationComputations.increment(count); }
    public void recordSpatialAutocorrelationRequest()     { spatialAutocorrelationRequests.increment(); }
    public void recordMoranVariableComputations(int count){ moranVariableComputations.increment(count); }

    // ── Condition-ingestion helper ────────────────────────────────────────────

    public void recordConditionIngested()                 { conditionIngested.increment(); }

    // ── City/neighbourhood helpers ────────────────────────────────────────────

    public void recordNeighCorrelationRequest()                { neighCorrelationRequests.increment(); }
    public void recordNeighCorrelationComputations(int count)  { neighCorrelationComputations.increment(count); }
    public void recordNeighSpatialAutocorrelationRequest()     { neighSpatialAutocorrelationRequests.increment(); }
    public void recordNeighMoranVariableComputations(int count){ neighMoranVariableComputations.increment(count); }
}
