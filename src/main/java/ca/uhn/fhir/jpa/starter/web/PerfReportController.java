package ca.uhn.fhir.jpa.starter.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ca.uhn.fhir.jpa.starter.common.PerfMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

@RestController
@RequestMapping("/perf-report")
public class PerfReportController {

    private final PerfMetricsService perf;
    private final MeterRegistry registry;

    public PerfReportController(PerfMetricsService perf, MeterRegistry registry) {
        this.perf = perf;
        this.registry = registry;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> report() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "perf-report-v2");
        root.put("generated_at_utc", Instant.now().toString());

        Map<String, Object> methodology = new LinkedHashMap<>();
        methodology.put("latency_unit", "ms");
        methodology.put("throughput_unit", "events_per_second");
        methodology.put("throughput_window_seconds", 60);
        methodology.put("percentiles", "p50,p95,p99");
        methodology.put("missing_metric_semantics", "null = not registered or not observed yet");
        root.put("methodology", methodology);

        Map<String, Object> ingest = new LinkedHashMap<>();
        ingest.put("patients_total", (long) perf.totalPatients());
        ingest.put("observations_total", (long) perf.totalObservations());
        ingest.put("conditions_total", (long) perf.totalConditions());
        ingest.put("patient_throughput_per_sec", round(perf.patientThroughput()));
        ingest.put("observation_throughput_per_sec", round(perf.observationThroughput()));
        ingest.put("observed", (perf.totalPatients() + perf.totalObservations() + perf.totalConditions()) > 0);
        root.put("ingestion", ingest);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("dashboard_stats", timerStats(perf.dashboardTimer));
        latency.put("city_blocks", timerStats(perf.cityBlockTimer));
        latency.put("block_correlations", timerStats(perf.blockCorrelationTimer));
        latency.put("spatial_autocorrelation", timerStats(perf.spatialAutocorrelationTimer));
        latency.put("patient_batch", timerStats(perf.patientBatchTimer));
        latency.put("observation_batch", timerStats(perf.observationBatchTimer));
        latency.put("news2_patient_update", namedTimerStats("fhir_news2_patient_update"));
        latency.put("news2_aggregate_upsert", namedTimerStats("fhir_news2_aggregate_upsert"));
        latency.put("news2_upstream_forward", namedTimerStats("fhir_news2_upstream_forward"));
        root.put("latency_ms", latency);

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("news2_update_success", namedCounterValue("fhir_news2_patient_update_success"));
        counters.put("news2_update_errors", namedCounterValue("fhir_news2_patient_update_errors"));
        counters.put("news2_upstream_success", namedCounterValue("fhir_news2_upstream_success"));
        counters.put("news2_upstream_errors", namedCounterValue("fhir_news2_upstream_errors"));
        counters.put("correlation_requests", namedCounterValue("fhir.analytics.correlation_requests_total"));
        counters.put("spatial_autocorrelation_requests", namedCounterValue("fhir.analytics.spatial_autocorrelation_requests_total"));
        counters.put("correlation_computations", namedCounterValue("fhir.analytics.correlation_computations_total"));
        counters.put("moran_variable_computations", namedCounterValue("fhir.analytics.moran_variable_computations_total"));
        root.put("custom_counters", counters);

        Map<String, Object> dbPool = new LinkedHashMap<>();
        dbPool.put("active_connections", gaugeValue("hikaricp.connections.active"));
        dbPool.put("idle_connections", gaugeValue("hikaricp.connections.idle"));
        dbPool.put("pending_threads", gaugeValue("hikaricp.connections.pending"));
        dbPool.put("max_connections", gaugeValue("hikaricp.connections.max"));
        dbPool.put("pool_saturation_pct", poolSaturation());
        root.put("db_pool", dbPool);

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heap_used_mb", round(gaugeValue("jvm.memory.used", "area", "heap") / 1_048_576.0));
        jvm.put("heap_max_mb", round(gaugeValue("jvm.memory.max", "area", "heap") / 1_048_576.0));
        jvm.put("heap_used_pct", heapUsedPct());
        root.put("jvm_memory", jvm);

        return root;
    }

    private Map<String, Object> timerStats(Timer timer) {
        Map<String, Object> m = new LinkedHashMap<>();
        long count = timer.count();
        m.put("registered", true);
        m.put("observed", count > 0);
        m.put("count", count);
        m.put("unit", "ms");

        if (count == 0) {
            m.put("mean_ms", null);
            m.put("p50_ms", null);
            m.put("p95_ms", null);
            m.put("p99_ms", null);
            m.put("max_ms", null);
            return m;
        }

        ValueAtPercentile[] percentiles = timer.takeSnapshot().percentileValues();
        m.put("mean_ms", round(timer.mean(TimeUnit.MILLISECONDS)));
        m.put("p50_ms", round(percentileMs(percentiles, 0.50)));
        m.put("p95_ms", round(percentileMs(percentiles, 0.95)));
        m.put("p99_ms", round(percentileMs(percentiles, 0.99)));
        m.put("max_ms", round(timer.max(TimeUnit.MILLISECONDS)));
        return m;
    }

    private Double percentileMs(ValueAtPercentile[] values, double percentile) {
        if (values == null) {
            return null;
        }
        for (ValueAtPercentile value : values) {
            if (Math.abs(value.percentile() - percentile) < 0.0001d) {
                return value.value(TimeUnit.MILLISECONDS);
            }
        }
        return null;
    }

    private Map<String, Object> namedTimerStats(String name) {
        Timer timer = registry.find(name).timer();
        if (timer == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registered", false);
            m.put("observed", false);
            m.put("count", 0);
            m.put("unit", "ms");
            m.put("mean_ms", null);
            m.put("p50_ms", null);
            m.put("p95_ms", null);
            m.put("p99_ms", null);
            m.put("max_ms", null);
            return m;
        }
        return timerStats(timer);
    }

    private Double namedCounterValue(String name) {
        Counter counter = registry.find(name).counter();
        return counter != null ? round(counter.count()) : null;
    }

    private Double gaugeValue(String name, String... tags) {
        Gauge gauge = tags.length == 0 ? registry.find(name).gauge() : registry.find(name).tags(tags).gauge();
        return gauge != null ? gauge.value() : null;
    }

    private Double poolSaturation() {
        Double active = gaugeValue("hikaricp.connections.active");
        Double max = gaugeValue("hikaricp.connections.max");
        if (active == null || max == null || max <= 0) {
            return null;
        }
        return round((active / max) * 100.0);
    }

    private Double heapUsedPct() {
        Double used = gaugeValue("jvm.memory.used", "area", "heap");
        Double max = gaugeValue("jvm.memory.max", "area", "heap");
        if (used == null || max == null || max <= 0) {
            return null;
        }
        return round((used / max) * 100.0);
    }

    private Double round(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}