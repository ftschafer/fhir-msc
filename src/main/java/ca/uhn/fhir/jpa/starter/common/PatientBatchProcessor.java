package ca.uhn.fhir.jpa.starter.common;

import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PatientBatchProcessor {

    @Value("${aggregation.patient.batch-size:300}")
    private int batchSize;

    @Value("${aggregation.patient.max-drains-per-run:30}")
    private int maxDrainsPerRun;

    private final PatientEventQueue queue;
    private final CityNews2AggregationService cityService;

    public PatientBatchProcessor(PatientEventQueue queue,
                                 CityNews2AggregationService cityService) {
        this.queue = queue;
        this.cityService = cityService;
    }

    @Scheduled(fixedDelayString = "${aggregation.patient.fixed-delay-ms:50}")
    @Transactional
    public void process() {
        int effectiveBatchSize = Math.max(50, batchSize);
        int maxDrains = Math.max(1, maxDrainsPerRun);
        int drains = 0;
        while (!queue.isEmpty() && drains < maxDrains) {
            List<Patient> patients = queue.drain(effectiveBatchSize);
            if (patients.isEmpty()) return;
            for (Patient p : patients) {
                if (p.getIdElement() != null && p.getIdElement().hasIdPart()) {
                    cityService.updateCityForPatient(p.getIdElement().getIdPart());
                }
            }
            drains++;
        }
    }
}
