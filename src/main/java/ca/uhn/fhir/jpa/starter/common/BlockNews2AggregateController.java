package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@RestController
@RequestMapping("/city-details")
public class BlockNews2AggregateController {

    @Value("${location.city}")
    private String configuredCity;

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/block-average")
    @Transactional
    public ResponseEntity<List<Map<String, Object>>> getBlockAverageByNeighborhood(
            @RequestParam(value = "neighborhood") String neighborhood
    ) {
        String city = resolveConfiguredCity();
        List<BlockNews2Aggregate> aggs = em.createQuery(
            "SELECT b FROM BlockNews2Aggregate b WHERE b.id.neighborhood = :neighborhood AND b.id.city = :city",
                BlockNews2Aggregate.class
        )
        .setParameter("neighborhood", neighborhood)
        .setParameter("city", city)
        .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("neighborhood", neighborhood);
            entry.put("city", city);
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
            @RequestParam("neighborhood") String neighborhood // add annotation to bind query param ?neighborhood=SP
    ) {
        String fixedCity = resolveConfiguredCity();
        BlockKey key = new BlockKey(neighborhood, fixedCity, block);
        BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, key);
        if (agg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("block", block, "city", fixedCity, "average", 0.0, "patientCount", 0));
        }
        double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
        Map<String, Object> result = new HashMap<>();
        result.put("block", block);
        result.put("city", fixedCity);
        result.put("average", avg);
        result.put("patientCount", agg.getPatientCount());
        result.put("totalScore", agg.getTotalScore());
        return ResponseEntity.ok(result);
    }

    @GetMapping
    @Transactional
    public List<Map<String, Object>> getAllBlockAverages() {
        String city = resolveConfiguredCity();
        List<BlockNews2Aggregate> aggs = em.createQuery(
                "SELECT b FROM BlockNews2Aggregate b WHERE b.id.city = :city",
                BlockNews2Aggregate.class)
            .setParameter("city", city)
            .getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avg = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new HashMap<>();
            entry.put("block", agg.getBlock());
            entry.put("city", city);
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
        String city = resolveConfiguredCity();
        String where = (neighborhood == null || neighborhood.isBlank())
            ? " WHERE b.id.city = :city"
            : " WHERE b.id.city = :city AND b.id.neighborhood = :neighborhood";

        var totalsQuery = em.createQuery(
                "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b" + where,
                Object[].class
        );
        totalsQuery.setParameter("city", city);
        if (neighborhood != null && !neighborhood.isBlank()) {
            totalsQuery.setParameter("neighborhood", neighborhood);
        }
        Object[] totals = totalsQuery.getSingleResult();

        int totalScore = ((Number) totals[0]).intValue();
        int patientCount = ((Number) totals[1]).intValue();
        double average = patientCount == 0 ? 0.0 : (double) totalScore / patientCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("neighborhood", neighborhood);
        result.put("city", city);
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        List<Map<String, Object>> cities = new ArrayList<>();
        Map<String, Object> cityEntry = new LinkedHashMap<>();
        cityEntry.put("city", city);
        cityEntry.put("average", average);
        cityEntry.put("patientCount", patientCount);
        cityEntry.put("totalScore", totalScore);
        cities.add(cityEntry);
        result.put("cities", cities);

        // Blocks list (optional)
        var blocksQuery = em.createQuery("SELECT b FROM BlockNews2Aggregate b" + where, BlockNews2Aggregate.class);
        blocksQuery.setParameter("city", city);
        if (neighborhood != null && !neighborhood.isBlank()) {
            blocksQuery.setParameter("neighborhood", neighborhood);
        }
        List<BlockNews2Aggregate> aggs = blocksQuery.getResultList();

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (BlockNews2Aggregate agg : aggs) {
            double avgBlock = agg.getPatientCount() == 0 ? 0.0 : (double) agg.getTotalScore() / agg.getPatientCount();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("neighborhood", agg.getNeighborhood());
            entry.put("city", city);
            entry.put("block", agg.getBlock());
            entry.put("average", avgBlock);
            entry.put("patientCount", agg.getPatientCount());
            entry.put("totalScore", agg.getTotalScore());
            blocks.add(entry);
        }
        result.put("blocks", blocks);

        return result;
    }

    private String resolveConfiguredCity() {
        if (configuredCity == null || configuredCity.isBlank()) {
            throw new IllegalStateException("Required property 'location.city' is missing or blank");
        }
        return configuredCity.trim();
    }
}
