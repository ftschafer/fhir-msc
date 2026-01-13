package ca.uhn.fhir.jpa.starter.common;

import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class PatientEventQueue {
    private final ConcurrentLinkedQueue<Patient> q = new ConcurrentLinkedQueue<>();
    public void enqueue(Patient p) { q.offer(p); }
    public List<Patient> drain(int max) {
        List<Patient> list = new ArrayList<>(Math.min(max, q.size()));
        for (int i=0;i<max;i++) {
            Patient p = q.poll();
            if (p == null) break;
            list.add(p);
        }
        return list;
    }
    public boolean isEmpty() { return q.isEmpty(); }
}
