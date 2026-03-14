package ca.uhn.fhir.jpa.starter.common;

import io.micrometer.core.instrument.Timer;
import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ObservationBatchProcessor {

    @Value("${hapi.fhir.news2.batch.size:1000}")
    private int batchSize;

    private final ObservationEventQueue queue;
    private final News2AggregationService news2Service;
    private final UpstreamForwarder upstreamForwarder;
    private final PerfMetricsService perfMetrics;

    public ObservationBatchProcessor(ObservationEventQueue queue,
                                     News2AggregationService news2Service,
                                     UpstreamForwarder upstreamForwarder,
                                     PerfMetricsService perfMetrics) {
        this.queue = queue;
        this.news2Service = news2Service;
        this.upstreamForwarder = upstreamForwarder;
        this.perfMetrics = perfMetrics;
    }

    @Scheduled(fixedDelayString = "${hapi.fhir.news2.batch.fixed-delay-ms:75}")
    @Transactional
    public void process() {
        if (queue.isEmpty()) return;
        int effectiveBatchSize = Math.max(100, batchSize);
        List<Observation> batch = queue.drain(effectiveBatchSize);
        if (batch.isEmpty()) return;

        Timer.Sample sample = Timer.start();
        Set<String> pids = news2Service.processObservations(batch); // DB upserts only
        sample.stop(perfMetrics.news2InterceptorTimer);
        perfMetrics.observationCreatedCounter.increment(batch.size());

        // Optional: forward upstream in a single transaction bundle for speed
        // upstreamForwarder.createObservations(batch);

        // Do not update Patient here; interceptor will update NEWS2 once per transaction.
    }
}
