package ca.uhn.fhir.jpa.starter.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.*;

@RestController
@RequestMapping("/city-details")
public class BlockNews2AggregateController {

    static final String CITY_VALUE = "NH";

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/block-average")
    @Transactional
    public ResponseEntity<List<Map<String, Object>>> getBlockAverageByNeighborhood(
            @RequestParam(value = "neighborhood") String neighborhood
    ) {
        List<BlockNews2Aggregate> aggs = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b WHERE b.id.neighborhood = :neighborhood",
                BlockNews2Aggregate.class
        )
        .setParameter("neighborhood", neighborhood)
        .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("neighborhood", neighborhood);
            entry.put("city", agg.getCity());
            entry.put("block", agg.getBlock());
            entry.put("average", avg);
            entry.put("patientCount", agg.getPatientCount());
            entry.put("totalScore", agg.getTotalScore());
            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    // Get city-level average (aggregated across blocks) using extension URL params
    // @GetMapping("/city-average")
    // @Transactional
    // public Map<String, Object> getCityAverage(
    //         @RequestParam(value = "neighborhood", required = false) String neighborhood
    // ) {
    //     Object[] totals = em.createQuery(
    //             neighborhood == null
    //                     ? "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b WHERE b.id.city = :city"
    //                     : "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b WHERE b.id.neighborhood = :neighborhood AND b.id.city = :city",
    //             Object[].class
    //     )
    //     .setParameter("city", CITY_VALUE)
    //     .setParameter("neighborhood", neighborhood)
    //     .getSingleResult();

    //     int totalScore = ((Number) totals[0]).intValue();
    //     int patientCount = ((Number) totals[1]).intValue();
    //     double average = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

    //     Map<String, Object> result = new LinkedHashMap<>();
    //     result.put("neighborhood", neighborhood);
    //     result.put("city", CITY_VALUE);
    //     result.put("totalScore", totalScore);
    //     result.put("patientCount", patientCount);
    //     result.put("average", average);

    //     List<BlockNews2Aggregate> aggs = em.createQuery(
    //             neighborhood == null
    //                     ? "SELECT b FROM BlockNews2Aggregate b WHERE b.id.city = :city"
    //                     : "SELECT b FROM BlockNews2Aggregate b WHERE b.id.neighborhood = :neighborhood AND b.id.city = :city",
    //             BlockNews2Aggregate.class
    //     )
    //     .setParameter("city", CITY_VALUE)
    //     .setParameter("neighborhood", neighborhood)
    //     .getResultList();

    //     List<Map<String, Object>> blocks = new ArrayList<>();
    //     for (BlockNews2Aggregate agg : aggs) {
    //         double avgBlock = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
    //         Map<String, Object> entry = new LinkedHashMap<>();
    //         entry.put("block", agg.getBlock());
    //         entry.put("average", avgBlock);
    //         entry.put("patientCount", agg.getPatientCount());
    //         entry.put("totalScore", agg.getTotalScore());
    //         blocks.add(entry);
    //     }
    //     result.put("blocks", blocks);
    //     return result;
    // }

    // Get all neighborhoods in a city and their averages
    // @GetMapping("/cities/{city}/neighborhoods")
    // @Transactional
    // public List<Map<String, Object>> getNeighborhoodCityAverages(@PathVariable("neighborhood") String neighborhood) {
    //     List<Object[]> rows = em.createQuery(
    //         "SELECT b.id.city, COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) " +
    //         "FROM BlockNews2Aggregate b WHERE b.id.neighborhood = :neighborhood GROUP BY b.id.city",
    //         Object[].class
    //     )
    //     .setParameter("neighborhood", neighborhood)
    //     .getResultList();

    //     List<Map<String, Object>> result = new ArrayList<>();
    //     for (Object[] row : rows) {
    //         String city = (String) row[0];
    //         int totalScore = ((Number) row[1]).intValue();
    //         int patientCount = ((Number) row[2]).intValue();
    //         double avg = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;
    //         Map<String, Object> entry = new LinkedHashMap<>();
    //         entry.put("neighborhood", neighborhood);
    //         entry.put("city", city);
    //         entry.put("average", avg);
    //         entry.put("patientCount", patientCount);
    //         entry.put("totalScore", totalScore);
    //         result.add(entry);
    //     }
    //     return result;
    // }

    // Existing endpoints retained
    @GetMapping("/{block}")
    @Transactional
    public ResponseEntity<Map<String, Object>> getBlockAverage(
            @PathVariable("block") String block,
            @RequestParam("city") String city,
            @RequestParam("neighborhood") String neighborhood // add annotation to bind query param ?neighborhood=SP
    ) {
        BlockKey key = new BlockKey(neighborhood, city == null ? "UNKNOWN" : city, block);
        BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, key);
        if (agg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("block", block, "city", city, "average", 0.0, "patientCount", 0));
        }
        double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
        Map<String, Object> result = new HashMap<>();
        result.put("block", block);
        result.put("city", city);
        result.put("average", avg);
        result.put("patientCount", agg.getPatientCount());
        result.put("totalScore", agg.getTotalScore());
        return ResponseEntity.ok(result);
    }

    @GetMapping
    @Transactional
    public List<Map<String, Object>> getAllBlockAverages() {
        List<BlockNews2Aggregate> aggs = em.createQuery("SELECT b FROM BlockNews2Aggregate b", BlockNews2Aggregate.class).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new HashMap<>();
            entry.put("block", agg.getBlock());
            entry.put("city", agg.getCity());
            entry.put("neighborhood", agg.getNeighborhood());
            entry.put("average", avg);
            entry.put("patientCount", agg.getPatientCount());
            entry.put("totalScore", agg.getTotalScore());
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/average-details")
    @Transactional
    public Map<String, Object> getCityDetails(@RequestParam(value = "neighborhood", required = false) String neighborhood) {
        String where = (neighborhood == null || neighborhood.isBlank()) ? "" : " WHERE b.id.neighborhood = :neighborhood";

        var totalsQuery = em.createQuery(
                "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b" + where,
                Object[].class
        );
        if (!where.isEmpty()) totalsQuery.setParameter("neighborhood", neighborhood);
        Object[] totals = totalsQuery.getSingleResult();

        int totalScore = ((Number) totals[0]).intValue();
        int patientCount = ((Number) totals[1]).intValue();
        double average = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("neighborhood", neighborhood);
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        // Breakdown per city (grouped)
        var cityQuery = em.createQuery(
                "SELECT b.id.city, COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b"
                        + where + " GROUP BY b.id.city",
                Object[].class
        );
        if (!where.isEmpty()) cityQuery.setParameter("neighborhood", neighborhood);
        List<Object[]> cityRows = cityQuery.getResultList();

        List<Map<String, Object>> cities = new ArrayList<>();
        for (Object[] row : cityRows) {
            String city = (String) row[0];
            int cTotal = ((Number) row[1]).intValue();
            int cCount = ((Number) row[2]).intValue();
            double cAvg = cCount == 0 ? 0.0 : (double) cTotal / cCount;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("city", city);
            entry.put("average", cAvg);
            entry.put("patientCount", cCount);
            entry.put("totalScore", cTotal);
            cities.add(entry);
        }
        result.put("cities", cities);

        // Blocks list (optional)
        var blocksQuery = em.createQuery("SELECT b FROM BlockNews2Aggregate b" + where, BlockNews2Aggregate.class);
        if (!where.isEmpty()) blocksQuery.setParameter("neighborhood", neighborhood);
        List<BlockNews2Aggregate> aggs = blocksQuery.getResultList();

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avgBlock = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("neighborhood", agg.getNeighborhood());
            entry.put("city", agg.getCity());
            entry.put("block", agg.getBlock());
            entry.put("average", avgBlock);
            entry.put("patientCount", agg.getPatientCount());
            entry.put("totalScore", agg.getTotalScore());
            blocks.add(entry);
        }
        result.put("blocks", blocks);

        return result;
    }
}
