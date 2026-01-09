package ca.uhn.fhir.jpa.starter.common;

import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ObservationBatchProcessor {

    private static final int BATCH_SIZE = 500;

    private final ObservationEventQueue queue;
    private final News2AggregationService news2Service;
    private final UpstreamForwarder upstreamForwarder;

    public ObservationBatchProcessor(ObservationEventQueue queue,
                                     News2AggregationService news2Service,
                                     UpstreamForwarder upstreamForwarder) {
        this.queue = queue;
        this.news2Service = news2Service;
        this.upstreamForwarder = upstreamForwarder;
    }

    @Scheduled(fixedDelay = 150)
    @Transactional
    public void process() {
        if (queue.isEmpty()) return;
        List<Observation> batch = queue.drain(BATCH_SIZE);
        if (batch.isEmpty()) return;

        Set<String> pids = news2Service.processObservations(batch); // DB upserts only

        // Optional: forward upstream in a single transaction bundle for speed
        // upstreamForwarder.createObservations(batch);

        // Do not update Patient here; interceptor will update NEWS2 once per transaction.
    }
}
