package ca.uhn.fhir.jpa.starter.common;

import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PatientEventQueue {
    private final ConcurrentLinkedQueue<Patient> q = new ConcurrentLinkedQueue<>();
    private final AtomicInteger currentSize = new AtomicInteger(0);

    @Value("${aggregation.patient.queue-max-size:15000}")
    private int maxQueueSize;

    public void enqueue(Patient p) {
        if (p == null) {
            return;
        }

        int cap = Math.max(1000, maxQueueSize);
        while (currentSize.get() >= cap) {
            Patient dropped = q.poll();
            if (dropped == null) {
                break;
            }
            currentSize.decrementAndGet();
        }

        q.offer(p);
        currentSize.incrementAndGet();
    }

    public List<Patient> drain(int max) {
        List<Patient> list = new ArrayList<>(Math.min(max, currentSize.get()));
        for (int i=0;i<max;i++) {
            Patient p = q.poll();
            if (p == null) break;
            list.add(p);
            currentSize.decrementAndGet();
        }
        return list;
    }
    public boolean isEmpty() { return q.isEmpty(); }
}
