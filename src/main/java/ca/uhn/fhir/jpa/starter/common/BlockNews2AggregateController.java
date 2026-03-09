package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
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

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String SAMPLE_COUNT_EXTENSION_URL = "http://observation-sample-count";
    private static final String NEWS2_AGG_IDENTIFIER_SYSTEM = "urn:aggregate:news2";
    private static final String NEWS2_AGG_IDENTIFIER_SUFFIX = "|neighborhood-average";
    private static final String NEWS2_AGG_CODE_SYSTEM = "http://loinc.org";
    private static final String NEWS2_AGG_CODE = "news2-avg";

    @Value("${location.city}")
    private String configuredCity;

    private final DaoRegistry daoRegistry;

    @PersistenceContext
    private EntityManager em;

    public BlockNews2AggregateController(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

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

    @GetMapping("/city-average")
    @Transactional
    public Map<String, Object> getCityAverage(
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "neighborhood", required = false) String neighborhood
    ) {
        String cityValue = (city == null || city.isBlank()) ? resolveConfiguredCity() : city;
        String where = (neighborhood == null || neighborhood.isBlank())
            ? " WHERE b.id.city = :city"
            : " WHERE b.id.neighborhood = :neighborhood AND b.id.city = :city";
        News2Accumulator fromNeighborhoodAverages = aggregateCityNews2FromNeighborhoodObservations(cityValue, neighborhood);

        double totalScore;
        int patientCount;
        double average;

        if (fromNeighborhoodAverages.hasSamples()) {
            totalScore = fromNeighborhoodAverages.weightedValueSum;
            patientCount = fromNeighborhoodAverages.totalSamples;
            average = fromNeighborhoodAverages.average();
        } else {
            totalScore = 0.0;
            patientCount = 0;
            average = 0.0;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("neighborhood", neighborhood);
        result.put("city", cityValue);
        result.put("totalScore", totalScore);
        result.put("patientCount", patientCount);
        result.put("average", average);

        var blocksQuery = em.createQuery("SELECT b FROM BlockNews2Aggregate b" + where, BlockNews2Aggregate.class)
                .setParameter("city", cityValue);
        if (neighborhood != null && !neighborhood.isBlank()) {
            blocksQuery.setParameter("neighborhood", neighborhood);
        }
        List<BlockNews2Aggregate> aggs = blocksQuery.getResultList();

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

    @SuppressWarnings("deprecation")
    private News2Accumulator aggregateCityNews2FromNeighborhoodObservations(String city, String neighborhoodFilter) {
        News2Accumulator acc = new News2Accumulator();

        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap search = new SearchParameterMap();
        TokenParam identifierSystem = new TokenParam();
        identifierSystem.setSystem(NEWS2_AGG_IDENTIFIER_SYSTEM);
        search.add("identifier", identifierSystem);
        search.setLoadSynchronous(true);

        List<IBaseResource> resources = getAllResources(observationDao.search(search));
        for (IBaseResource resource : resources) {
            if (!(resource instanceof Observation obs)) continue;
            if (!isNeighborhoodNews2Aggregate(obs)) continue;
            if (!(obs.getValue() instanceof Quantity q) || q.getValue() == null) continue;

            String obsCity = extractLocationNested(obs, "city");
            if (obsCity == null || obsCity.isBlank()) {
                obsCity = resolveConfiguredCity();
            }
            if (city != null && !city.equalsIgnoreCase(obsCity)) continue;

            String obsNeighborhood = extractLocationNested(obs, "neighborhood");
            if (neighborhoodFilter != null && !neighborhoodFilter.isBlank()
                    && (obsNeighborhood == null || !neighborhoodFilter.equalsIgnoreCase(obsNeighborhood))) {
                continue;
            }

            int sampleCount = extractSampleCount(obs);
            double averageValue = q.getValue().doubleValue();
            acc.add(averageValue, sampleCount);
        }
        return acc;
    }

    private boolean isNeighborhoodNews2Aggregate(Observation obs) {
        if (obs == null) return false;

        boolean codeMatches = obs.getCode() != null && obs.getCode().getCoding().stream().anyMatch(c ->
                c != null
                        && NEWS2_AGG_CODE.equals(c.getCode())
                        && (c.getSystem() == null || NEWS2_AGG_CODE_SYSTEM.equals(c.getSystem()))
        );
        if (!obs.hasIdentifier()) return false;

        for (Identifier id : obs.getIdentifier()) {
            if (id == null || id.getSystem() == null) continue;
            if (!NEWS2_AGG_IDENTIFIER_SYSTEM.equals(id.getSystem())) continue;

            String value = id.getValue();
            if (value == null || value.isBlank()) {
                return true;
            }

            String normalizedValue = value.toLowerCase(Locale.ROOT);
            if (normalizedValue.endsWith(NEWS2_AGG_IDENTIFIER_SUFFIX)
                    || normalizedValue.contains("neighborhood-average")
                    || normalizedValue.contains("neighbourhood-average")) {
                return true;
            }

            if (codeMatches) {
                return true;
            }
        }
        return false;
    }

    private String extractLocationNested(Observation obs, String url) {
        if (obs == null) return null;
        Extension loc = obs.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (loc == null) return null;
        for (Extension nested : loc.getExtension()) {
            if (nested != null && url.equals(nested.getUrl()) && nested.getValue() != null) {
                String value = nested.getValue().primitiveValue();
                if (value != null) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) return trimmed;
                }
            }
        }
        return null;
    }

    private int extractSampleCount(Observation obs) {
        Extension sampleExt = obs.getExtensionByUrl(SAMPLE_COUNT_EXTENSION_URL);
        if (sampleExt != null && sampleExt.getValue() instanceof IntegerType it && it.getValue() != null) {
            return Math.max(1, it.getValue());
        }
        return 1;
    }

    private List<IBaseResource> getAllResources(IBundleProvider provider) {
        if (provider == null) return List.of();
        Integer size = provider.size();
        if (size == null || size < 0) return provider.getAllResources();

        List<IBaseResource> all = new ArrayList<>(size);
        final int pageSize = 500;
        for (int from = 0; from < size; from += pageSize) {
            int to = Math.min(from + pageSize, size);
            List<IBaseResource> page = provider.getResources(from, to);
            if (page.isEmpty()) break;
            all.addAll(page);
        }
        return all;
    }

    private static class News2Accumulator {
        private double weightedValueSum;
        private int totalSamples;

        private void add(double averageValue, int sampleCount) {
            int weight = sampleCount > 0 ? sampleCount : 1;
            weightedValueSum += averageValue * weight;
            totalSamples += weight;
        }

        private boolean hasSamples() {
            return totalSamples > 0;
        }

        private double average() {
            return totalSamples == 0 ? 0.0 : weightedValueSum / totalSamples;
        }
    }

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
