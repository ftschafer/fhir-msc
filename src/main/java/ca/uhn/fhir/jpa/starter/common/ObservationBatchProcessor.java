package ca.uhn.fhir.jpa.starter.common;

import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ObservationBatchProcessor {

    private static final int BATCH_SIZE = 200;

    private final ObservationEventQueue queue;
    private final BlockNews2AggregationService blockService;
    private final NeighborhoodVitalAggregationService neighborhoodVitalAggregationService;

    public ObservationBatchProcessor(ObservationEventQueue queue,
                                     BlockNews2AggregationService blockService,
                                     NeighborhoodVitalAggregationService neighborhoodVitalAggregationService) {
        this.queue = queue;
        this.blockService = blockService;
        this.neighborhoodVitalAggregationService = neighborhoodVitalAggregationService;
    }

    @Scheduled(fixedDelay = 200) // adjust for throughput/latency
    @Transactional
    public void process() {
        if (queue.isEmpty()) return;
        List<Observation> obs = queue.drain(BATCH_SIZE);
        if (obs.isEmpty()) return;

        // Forward neighborhood-level averages to upstream when average observations are present.
        // This path covers internally processed observations that may not carry HTTP request context.
        neighborhoodVitalAggregationService.processBlockAverages(obs);

        obs.stream()
            .map(Observation::getSubject)
            .filter(java.util.Objects::nonNull)
            .map(ref -> ref.getReference())
            .filter(java.util.Objects::nonNull)
            .filter(ref -> ref.startsWith("Patient/"))
            .map(ref -> ref.substring("Patient/".length()))
            .filter(id -> !id.isBlank())
            .collect(Collectors.toSet())
            .forEach(blockService::updateBlockForPatient);
    }
}
