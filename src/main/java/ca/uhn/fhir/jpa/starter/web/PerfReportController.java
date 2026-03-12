package ca.uhn.fhir.jpa.starter.web;

import ca.uhn.fhir.jpa.starter.common.PerfMetricsService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Exposes a single human-readable JSON performance report at GET /perf-report.
 *
 * Call it any time while sending real data to get live metrics:
 *   GET http://localhost:8071/perf-report
 *
 * Reports:
 *  - Ingestion throughput (patients/sec, observations/sec)
 *  - Latency p50/p95/p99 for every custom service
 *  - Error rates
 *  - DB connection pool saturation
 *  - JVM memory usage
 */
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

        // --- Ingestion ---
        Map<String, Object> ingest = new LinkedHashMap<>();
        ingest.put("patients_total",              (long) perf.totalPatients());
        ingest.put("observations_total",           (long) perf.totalObservations());
        ingest.put("conditions_total",             (long) perf.totalConditions());
        ingest.put("patient_errors",               (long) perf.totalPatientErrors());
        ingest.put("observation_errors",           (long) perf.totalObsErrors());
        ingest.put("patient_throughput_per_sec",   round(perf.patientThroughput()));
        ingest.put("observation_throughput_per_sec", round(perf.obsThroughput()));
        double totalWrites = perf.totalPatients() + perf.totalObservations();
        double totalErrors = perf.totalPatientErrors() + perf.totalObsErrors();
        ingest.put("error_rate_pct", totalWrites > 0 ? round(100.0 * totalErrors / totalWrites) : 0.0);
        root.put("ingestion", ingest);

        // --- Service latencies ---
        root.put("latency_ms", Map.of(
            "dashboard_stats",    timerStats(perf.dashboardTimer),
            "vital_history",      timerStats(perf.vitalHistoryTimer),
            "block_aggregate",    timerStats(perf.blockAggregateTimer),
            "news2_interceptor",  timerStats(perf.news2InterceptorTimer)
        ));

        // --- NEWS2 Aggregation Service (metrics recorded inside News2AggregationService) ---
        Map<String, Object> news2 = new LinkedHashMap<>();
        news2.put("batch_processing",   namedTimerStats("fhir_news2_processing_seconds"));
        news2.put("db_update",          namedTimerStats("fhir_news2_db_update_seconds"));
        news2.put("upstream_call",      namedTimerStats("fhir_news2_upstream_seconds"));
        news2.put("patients_processed", namedCounterValue("fhir_news2_patients_processed_total"));
        news2.put("updates_changed",    namedCounterValueTagged("fhir_news2_patient_update_total", "result", "changed"));
        news2.put("updates_unchanged",  namedCounterValueTagged("fhir_news2_patient_update_total", "result", "unchanged"));
        root.put("news2_aggregation", news2);

        // --- KMeans / Socioeconomic Analysis ---
        Map<String, Object> kmeans = new LinkedHashMap<>();
        kmeans.put("analyze_total",      timerStats(perf.kmeansAnalyzeTimer));
        kmeans.put("model_selection",    timerStats(perf.kmeansSelectModelTimer));
        kmeans.put("iterations_total",   (long) perf.totalKmeansIterations());
        root.put("kmeans_analysis", kmeans);

        // --- DB Connection Pool (HikariCP via Actuator metrics) ---
        root.put("db_pool", Map.of(
            "active_connections",  gaugeValue("hikaricp.connections.active"),
            "idle_connections",    gaugeValue("hikaricp.connections.idle"),
            "pending_threads",     gaugeValue("hikaricp.connections.pending"),
            "max_connections",     gaugeValue("hikaricp.connections.max"),
            "pool_saturation_pct", poolSaturation()
        ));

        // --- JVM Memory ---
        root.put("jvm_memory", Map.of(
            "heap_used_mb",   round(gaugeValue("jvm.memory.used",   "area", "heap")   / 1_048_576.0),
            "heap_max_mb",    round(gaugeValue("jvm.memory.max",    "area", "heap")   / 1_048_576.0),
            "heap_used_pct",  heapUsedPct(),
            "nonheap_used_mb",round(gaugeValue("jvm.memory.used",   "area", "nonheap") / 1_048_576.0)
        ));

        // --- HTTP server-level (Spring MVC / FHIR servlet timings) ---
        root.put("http_server", Map.of(
            "fhir_patient_create_p95_ms", httpP95("PUT", "/fhir/Patient/{id}"),
            "fhir_observation_create_p95_ms", httpP95("POST", "/fhir/Observation")
        ));

        return root;
    }

    private Map<String, Object> timerStats(Timer timer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count",    timer.count());
        m.put("mean_ms",  round(timer.mean(TimeUnit.MILLISECONDS)));
        m.put("p50_ms",   round(timer.percentile(0.50, TimeUnit.MILLISECONDS)));
        m.put("p95_ms",   round(timer.percentile(0.95, TimeUnit.MILLISECONDS)));
        m.put("p99_ms",   round(timer.percentile(0.99, TimeUnit.MILLISECONDS)));
        m.put("max_ms",   round(timer.max(TimeUnit.MILLISECONDS)));
        return m;
    }

    private double gaugeValue(String name, String... tags) {
        try {
            if (tags.length == 0) {
                Gauge g = registry.find(name).gauge();
                return g != null ? g.value() : -1;
            }
            Gauge g = registry.find(name).tags(tags).gauge();
            return g != null ? g.value() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private double poolSaturation() {
        double active = gaugeValue("hikaricp.connections.active");
        double max    = gaugeValue("hikaricp.connections.max");
        if (max <= 0 || active < 0) return -1;
        return round(100.0 * active / max);
    }

    private double heapUsedPct() {
        double used = gaugeValue("jvm.memory.used", "area", "heap");
        double max  = gaugeValue("jvm.memory.max",  "area", "heap");
        if (max <= 0 || used < 0) return -1;
        return round(100.0 * used / max);
    }

    private double httpP95(String method, String uri) {
        try {
            Timer t = registry.find("http.server.requests")
                    .tag("method", method)
                    .tag("uri", uri)
                    .timer();
            return t != null ? round(t.percentile(0.95, TimeUnit.MILLISECONDS)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Look up a named Timer registered directly in the MeterRegistry (e.g. from News2AggregationService) */
    private Map<String, Object> namedTimerStats(String name) {
        try {
            Timer t = registry.find(name).timer();
            if (t == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("count", 0);
                m.put("mean_ms", -1);
                m.put("p50_ms", -1);
                m.put("p95_ms", -1);
                m.put("p99_ms", -1);
                m.put("max_ms", -1);
                return m;
            }
            return timerStats(t);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            return m;
        }
    }

    /** Look up a named Counter registered directly in the MeterRegistry */
    private double namedCounterValue(String name) {
        try {
            io.micrometer.core.instrument.Counter c = registry.find(name).counter();
            return c != null ? round(c.count()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Look up a named Counter with a tag pair */
    private double namedCounterValueTagged(String name, String tagKey, String tagValue) {
        try {
            io.micrometer.core.instrument.Counter c = registry.find(name).tag(tagKey, tagValue).counter();
            return c != null ? round(c.count()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
