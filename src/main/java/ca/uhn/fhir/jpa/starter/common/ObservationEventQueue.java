package ca.uhn.fhir.jpa.starter.common;

import org.hl7.fhir.r4.model.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ObservationEventQueue {
    private final ConcurrentLinkedQueue<Observation> q = new ConcurrentLinkedQueue<>();
    private final AtomicInteger currentSize = new AtomicInteger(0);

    @Value("${aggregation.observation.queue-max-size:20000}")
    private int maxQueueSize;

    public void enqueue(Observation o) {
        if (o == null) {
            return;
        }

        int cap = Math.max(1000, maxQueueSize);
        while (currentSize.get() >= cap) {
            Observation dropped = q.poll();
            if (dropped == null) {
                break;
            }
            currentSize.decrementAndGet();
        }

        q.offer(o);
        currentSize.incrementAndGet();
    }

    public List<Observation> drain(int max) {
        List<Observation> list = new ArrayList<>(Math.min(max, currentSize.get()));
        for (int i=0;i<max;i++) {
            Observation o = q.poll();
            if (o == null) break;
            list.add(o);
            currentSize.decrementAndGet();
        }
        return list;
    }
    public boolean isEmpty() { return q.isEmpty(); }
}
