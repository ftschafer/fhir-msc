package ca.uhn.fhir.jpa.starter.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.*;

/**
 * REST endpoints for NEWS2 block aggregates.
 * <p>
 * Data model (see {@link BlockNews2AggregationService}):
 * <ul>
 *   <li>{@code region} – always empty string (unused)</li>
 *   <li>{@code city}   – DB column that stores the <b>neighborhood</b> value</li>
 *   <li>{@code block}  – the block identifier</li>
 * </ul>
 */
@RestController
@RequestMapping("/city-blocks")
public class BlockNews2AggregateController {

    private static final String REGION = "";   // service always stores ""

    @PersistenceContext
    private EntityManager em;

    // ── Single block ────────────────────────────────────────────────────────

    @GetMapping("/block-average")
    @Transactional
    public ResponseEntity<Map<String, Object>> getBlockAverage(
            @RequestParam("neighborhood") String neighborhood,
            @RequestParam("block") String block
    ) {
        BlockKey key = new BlockKey(REGION, neighborhood, block);
        BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, key);
        if (agg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "neighborhood", neighborhood,
                    "block", block,
                    "average", 0.0,
                    "patientCount", 0,
                    "totalScore", 0
            ));
        }
        return ResponseEntity.ok(toMap(agg));
    }

    @GetMapping("/{block}")
    @Transactional
    public ResponseEntity<Map<String, Object>> getBlockAverageByPath(
            @PathVariable("block") String block,
            @RequestParam("neighborhood") String neighborhood
    ) {
        BlockKey key = new BlockKey(REGION, neighborhood, block);
        BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, key);
        if (agg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("block", block, "neighborhood", neighborhood,
                                 "average", 0.0, "patientCount", 0));
        }
        return ResponseEntity.ok(toMap(agg));
    }

    // ── Neighborhood aggregate ──────────────────────────────────────────────

    @GetMapping("/neighborhood-average")
    @Transactional
    public Map<String, Object> getNeighborhoodAverage(
            @RequestParam("neighborhood") String neighborhood
    ) {
        Object[] totals = em.createQuery(
                "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) " +
                "FROM BlockNews2Aggregate b WHERE b.id.city = :neigh",
                Object[].class
        ).setParameter("neigh", neighborhood).getSingleResult();

        int totalScore   = ((Number) totals[0]).intValue();
        int patientCount = ((Number) totals[1]).intValue();
        double average   = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("neighborhood", neighborhood);
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        List<BlockNews2Aggregate> aggs = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b WHERE b.id.city = :neigh",
                BlockNews2Aggregate.class
        ).setParameter("neigh", neighborhood).getResultList();

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            blocks.add(toMap(agg));
        }
        result.put("blocks", blocks);
        return result;
    }

    // ── All blocks ──────────────────────────────────────────────────────────

    @GetMapping
    @Transactional
    public List<Map<String, Object>> getAllBlockAverages() {
        List<BlockNews2Aggregate> aggs = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b", BlockNews2Aggregate.class
        ).getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            result.add(toMap(agg));
        }
        return result;
    }

    // ── Summary across all neighborhoods ────────────────────────────────────

    @GetMapping("/average-details")
    @Transactional
    public Map<String, Object> getAverageDetails() {
        Object[] totals = em.createQuery(
                "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b",
                Object[].class
        ).getSingleResult();

        int totalScore   = ((Number) totals[0]).intValue();
        int patientCount = ((Number) totals[1]).intValue();
        double average   = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        // Breakdown per neighborhood
        List<Object[]> neighRows = em.createQuery(
                "SELECT b.id.city, COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) " +
                "FROM BlockNews2Aggregate b GROUP BY b.id.city",
                Object[].class
        ).getResultList();

        List<Map<String, Object>> neighborhoods = new ArrayList<>();
        for (Object[] row : neighRows) {
            String neigh = (String) row[0];
            int nTotal   = ((Number) row[1]).intValue();
            int nCount   = ((Number) row[2]).intValue();
            double nAvg  = nCount == 0 ? 0.0 : (double) nTotal / nCount;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("neighborhood", neigh);
            entry.put("average", nAvg);
            entry.put("patientCount", nCount);
            entry.put("totalScore", nTotal);
            neighborhoods.add(entry);
        }
        result.put("neighborhoods", neighborhoods);

        // All blocks
        List<BlockNews2Aggregate> aggs = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b", BlockNews2Aggregate.class
        ).getResultList();

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            blocks.add(toMap(agg));
        }
        result.put("blocks", blocks);
        return result;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> toMap(BlockNews2Aggregate agg) {
        double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block", agg.getBlock());
        m.put("neighborhood", agg.getCity());   // DB "city" column stores neighborhood
        m.put("average", avg);
        m.put("patientCount", agg.getPatientCount());
        m.put("totalScore", agg.getTotalScore());
        return m;
    }
}
