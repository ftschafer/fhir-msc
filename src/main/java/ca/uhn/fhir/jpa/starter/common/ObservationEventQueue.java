package ca.uhn.fhir.jpa.starter.common;

import org.hl7.fhir.r4.model.Observation;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class ObservationEventQueue {
    private final ConcurrentLinkedQueue<Observation> q = new ConcurrentLinkedQueue<>();
    public void enqueue(Observation o) { q.offer(o); }
    public List<Observation> drain(int max) {
        List<Observation> list = new ArrayList<>(Math.min(max, q.size()));
        for (int i=0;i<max;i++) {
            Observation o = q.poll();
            if (o == null) break;
            list.add(o);
        }
        return list;
    }
    public boolean isEmpty() { return q.isEmpty(); }
}
