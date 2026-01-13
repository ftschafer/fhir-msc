package ca.uhn.fhir.jpa.starter.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.*;

@RestController
@RequestMapping("/city-blocks")
public class BlockNews2AggregateController {

    private static final String REGION_VALUE = "North"; // hardcoded region

    @PersistenceContext
    private EntityManager em;

    // Get average for a specific block within a city and region (city/block from extension URL, region optional)
    @GetMapping("/block-average")
    @Transactional
    public ResponseEntity<Map<String, Object>> getBlockAverageByCity(
            @RequestParam("city") String city,
            @RequestParam("block") String block,
            @RequestParam(value = "region", required = false) String region
    ) {
                List<BlockNews2Aggregate> matches = em.createQuery(
                region == null
                        ? "SELECT b FROM BlockNews2Aggregate b WHERE b.id.city = :city AND b.id.block = :block"
                        : "SELECT b FROM BlockNews2Aggregate b WHERE b.id.region = :region AND b.id.city = :city AND b.id.block = :block",
                BlockNews2Aggregate.class
        )
        .setParameter("city", city)
        .setParameter("block", block)
        .setParameter("region", region)
        .getResultList();

        BlockNews2Aggregate agg = matches.isEmpty() ? null : matches.get(0);
        if (agg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "region", region,
                    "city", city,
                    "block", block,
                    "average", 0.0,
                    "patientCount", 0,
                    "totalScore", 0
            ));
        }
        double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("region", agg.getRegion());
        result.put("city", agg.getCity());
        result.put("block", agg.getBlock());
        result.put("average", avg);
        result.put("patientCount", agg.getPatientCount());
        result.put("totalScore", agg.getTotalScore());
        return ResponseEntity.ok(result);
    }

    // Get city-level average (aggregated across blocks) using extension URL params
    @GetMapping("/city-average")
    @Transactional
    public Map<String, Object> getCityAverage(
            @RequestParam("city") String city,
            @RequestParam(value = "region", required = false) String region
    ) {
        Object[] totals = em.createQuery(
                region == null
                        ? "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b WHERE b.id.city = :city"
                        : "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b WHERE b.id.region = :region AND b.id.city = :city",
                Object[].class
        )
        .setParameter("city", city)
        .setParameter("region", region)
        .getSingleResult();

        int totalScore = ((Number) totals[0]).intValue();
        int patientCount = ((Number) totals[1]).intValue();
        double average = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("region", region);
        result.put("city", city);
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        List<BlockNews2Aggregate> aggs = em.createQuery(
                region == null
                        ? "SELECT b FROM BlockNews2Aggregate b WHERE b.id.city = :city"
                        : "SELECT b FROM BlockNews2Aggregate b WHERE b.id.region = :region AND b.id.city = :city",
                BlockNews2Aggregate.class
        )
        .setParameter("city", city)
        .setParameter("region", region)
        .getResultList();

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avgBlock = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("block", agg.getBlock());
            entry.put("average", avgBlock);
            entry.put("patientCount", agg.getPatientCount());
            entry.put("totalScore", agg.getTotalScore());
            blocks.add(entry);
        }
        result.put("blocks", blocks);
        return result;
    }

    // Get all cities in a region and their averages
    // @GetMapping("/regions/{region}/cities")
    // @Transactional
    // public List<Map<String, Object>> getRegionCityAverages(@PathVariable("region") String region) {
    //     List<Object[]> rows = em.createQuery(
    //         "SELECT b.id.city, COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) " +
    //         "FROM BlockNews2Aggregate b WHERE b.id.region = :region GROUP BY b.id.city",
    //         Object[].class
    //     )
    //     .setParameter("region", region)
    //     .getResultList();

    //     List<Map<String, Object>> result = new ArrayList<>();
    //     for (Object[] row : rows) {
    //         String city = (String) row[0];
    //         int totalScore = ((Number) row[1]).intValue();
    //         int patientCount = ((Number) row[2]).intValue();
    //         double avg = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;
    //         Map<String, Object> entry = new LinkedHashMap<>();
    //         entry.put("region", region);
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
            @RequestParam("city") String city // add annotation to bind query param ?city=SP
    ) {
        BlockKey key = new BlockKey(REGION_VALUE, city == null ? "UNKNOWN" : city, block);
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
            entry.put("region", agg.getRegion());
            entry.put("average", avg);
            entry.put("patientCount", agg.getPatientCount());
            entry.put("totalScore", agg.getTotalScore());
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/average-details")
    @Transactional
    public Map<String, Object> getCityDetails() {
        Object[] totals = em.createQuery(
                "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b",
                Object[].class
        ).getSingleResult();

        int totalScore = ((Number) totals[0]).intValue();
        int patientCount = ((Number) totals[1]).intValue();
        double average = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        // Breakdown per city (grouped) — use embedded id fields
        List<Object[]> cityRows = em.createQuery(
                "SELECT b.id.city, COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) " +
                "FROM BlockNews2Aggregate b GROUP BY b.id.city",
                Object[].class
        ).getResultList();

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

        // Optional: include blocks
        List<BlockNews2Aggregate> aggs = em.createQuery("SELECT b FROM BlockNews2Aggregate b", BlockNews2Aggregate.class).getResultList();
        List<Map<String, Object>> blocks = new ArrayList<>();
                for (BlockNews2Aggregate agg : aggs) {
            double avgBlock = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new LinkedHashMap<>();
                        // `city` currently stores neighborhood in this design; expose as `neighborhood`
                        entry.put("neighborhood", agg.getCity());
            entry.put("region", agg.getRegion());
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
