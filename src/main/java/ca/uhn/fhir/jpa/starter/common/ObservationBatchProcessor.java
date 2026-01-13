package ca.uhn.fhir.jpa.starter.common;

import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

@Component
public class ObservationBatchProcessor {

    private static final int BATCH_SIZE = 200;

    private final ObservationEventQueue queue;
    private final News2AggregationService news2Service;
    private final CityNews2AggregationService cityService;

    public ObservationBatchProcessor(ObservationEventQueue queue,
                                     News2AggregationService news2Service,
                                     CityNews2AggregationService cityService) {
        this.queue = queue;
        this.news2Service = news2Service;
        this.cityService = cityService;
    }

    @Scheduled(fixedDelay = 200) // adjust for throughput/latency
    @Transactional
    public void process() {
        if (queue.isEmpty()) return;
        List<Observation> obs = queue.drain(BATCH_SIZE);
        if (obs.isEmpty()) return;
        Set<String> patients = news2Service.processBundleObservationsReturningPatients(obs);
        patients.forEach(cityService::updateCityForPatient);
    }
}
