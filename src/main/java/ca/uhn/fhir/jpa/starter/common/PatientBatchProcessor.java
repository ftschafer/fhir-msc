package ca.uhn.fhir.jpa.starter.common;

import io.micrometer.core.instrument.Timer;
import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PatientBatchProcessor {

    private static final int BATCH_SIZE = 200;

    private final PatientEventQueue queue;
    private final BlockNews2AggregationService blockService;
    private final PerfMetricsService perfMetrics;

    public PatientBatchProcessor(PatientEventQueue queue,
                                 BlockNews2AggregationService blockService,
                                 PerfMetricsService perfMetrics) {
        this.queue = queue;
        this.blockService = blockService;
        this.perfMetrics = perfMetrics;
    }

    @Scheduled(fixedDelay = 200)
    @Transactional
    public void process() {
        if (queue.isEmpty()) return;
        List<Patient> patients = queue.drain(BATCH_SIZE);
        if (patients.isEmpty()) return;
        Timer.Sample sample = Timer.start();
        for (Patient p : patients) {
            if (p.getIdElement() != null && p.getIdElement().hasIdPart()) {
                blockService.updateBlockForPatient(p.getIdElement().getIdPart());
            }
        }
        sample.stop(perfMetrics.patientBatchTimer);
    }
}
