package ca.uhn.fhir.jpa.starter.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.*;

@RestController
@RequestMapping("/block-details")
public class BlockNews2AggregateController {

    @PersistenceContext
    private EntityManager em;

    // Get average for a specific block
    @GetMapping(value="/{block}")
    @Transactional
    public ResponseEntity<Map<String, Object>> getBlockAverage(@PathVariable("block") String block) {
        BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, block);
        if (agg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("block", block, "average", 0.0, "patientCount", 0));
        }
        double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
        Map<String, Object> result = new HashMap<>();
        result.put("block", block);
        result.put("average", avg);
        result.put("patientCount", agg.getPatientCount());
        result.put("totalScore", agg.getTotalScore());
        return ResponseEntity.ok(result);
    }
}
