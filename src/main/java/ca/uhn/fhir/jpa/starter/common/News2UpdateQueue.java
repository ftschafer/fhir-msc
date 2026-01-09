package ca.uhn.fhir.jpa.starter.common;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class News2UpdateQueue {
    static class Item { final String pid; final int total; Item(String p, int t){ pid=p; total=t; } }
    private final ConcurrentLinkedQueue<Item> q = new ConcurrentLinkedQueue<>();

    public void enqueue(String patientId, int total) { q.add(new Item(patientId, total)); }

    // Package-private poll for the service to drain
    News2UpdateQueue.Item poll() { return q.poll(); }

    public boolean isEmpty() { return q.isEmpty(); }
}
