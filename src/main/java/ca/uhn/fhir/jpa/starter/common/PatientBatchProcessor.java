package ca.uhn.fhir.jpa.starter.common;

import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PatientBatchProcessor {

    private static final int BATCH_SIZE = 200;
    private static final int MAX_DRAINS_PER_RUN = 20;

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
        int drains = 0;
        while (!queue.isEmpty() && drains < MAX_DRAINS_PER_RUN) {
            List<Patient> patients = queue.drain(BATCH_SIZE);
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
