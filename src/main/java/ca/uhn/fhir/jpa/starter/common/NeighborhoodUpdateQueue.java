package ca.uhn.fhir.jpa.starter.common;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.ArrayList;

@Component
public class NeighborhoodUpdateQueue {
    private final ConcurrentLinkedQueue<String> q = new ConcurrentLinkedQueue<>();
    public void enqueue(String patientId) { q.offer(patientId); }
    public List<String> drain(int max) {
        List<String> out = new ArrayList<>(Math.min(max, q.size()));
        for (int i = 0; i < max; i++) {
            String id = q.poll();
            if (id == null) break;
            out.add(id);
        }
        return out;
    }
    public boolean isEmpty() { return q.isEmpty(); }
}
