package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * City-level dashboard endpoint.
 * Accessible at: GET /fhir/Patient/$dashboard-stats?city=NH
 */
@Component
public class DashboardProvider implements IResourceProvider {

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String SAMPLE_COUNT_EXTENSION_URL = "http://observation-sample-count";

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unused")
    private final DaoRegistry daoRegistry;

    public DashboardProvider(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Patient.class;
    }

    @Operation(name = "$dashboard-stats", idempotent = true, manualResponse = true)
    public void getDashboardStats(
            @OperationParam(name = "city") StringType cityParam,
            HttpServletResponse response
    ) {
        try {
            String cityFilter = cityParam != null ? normalize(cityParam.getValue()) : null;
            Map<String, Object> payload = buildDashboard(cityFilter);
            writeJson(response, payload);
        } catch (Exception e) {
            try {
                response.setStatus(500);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception ignored) {
                // no-op
            }
        }
    }

    private Map<String, Object> buildDashboard(String cityFilter) {
        PatientStats patientStats = collectPatientStats(cityFilter);
        ConditionCounts conditionCounts = collectConditionCounts(cityFilter);

        int totalScore = patientStats.totalScore;
        int totalPatients = patientStats.totalPatients;
        double avgNews2 = totalPatients == 0 ? 0.0 : (double) totalScore / totalPatients;
        long totalConditions = conditionCounts.total;

        Map<String, Map<String, VitalSignAccumulator>> cityVitalAcc = new HashMap<>();
        Map<String, Map<String, VitalSignAccumulator>> neighborhoodVitalAcc = new HashMap<>();
        Map<String, Map<String, VitalSignAccumulator>> blockVitalAcc = new HashMap<>();
        loadVitalSignAverages(cityFilter, cityVitalAcc, neighborhoodVitalAcc, blockVitalAcc);

        List<Map<String, Object>> cityStats = new ArrayList<>();
        for (CityAccumulator c : patientStats.cityAcc.values()) {
            double cAvg = c.patientCount == 0 ? 0.0 : (double) c.totalScore / c.patientCount;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("city", c.city);
            item.put("patientCount", c.patientCount);
            item.put("conditionCount", conditionCounts.byCity.getOrDefault(c.city, 0L));
            item.put("avgNews2", format1(cAvg));
            item.put("vitalSignAverages", toVitalSignAverages(cityVitalAcc.get(c.city)));
            cityStats.add(item);
        }

        List<Map<String, Object>> neighborhoodStats = new ArrayList<>();
        for (NeighborhoodAccumulator n : patientStats.neighborhoodAcc.values()) {
            double avg = n.patientCount == 0 ? 0.0 : (double) n.totalScore / n.patientCount;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("city", n.city);
            item.put("neighborhood", n.neighborhood);
            item.put("patientCount", n.patientCount);
            item.put("conditionCount", conditionCounts.byNeighborhood.getOrDefault(key(n.city, n.neighborhood, ""), 0L));
            item.put("avgNews2", format1(avg));
            item.put("vitalSignAverages", toVitalSignAverages(neighborhoodVitalAcc.get(key(n.city, n.neighborhood, ""))));
            neighborhoodStats.add(item);
        }

        List<Map<String, Object>> blockStats = new ArrayList<>();
        for (BlockAccumulator b : patientStats.blockAcc.values()) {
            double bAvg = b.patientCount == 0 ? 0.0 : (double) b.totalScore / b.patientCount;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("neighborhood", b.neighborhood);
            item.put("city", b.city);
            item.put("block", b.block);
            item.put("patientCount", b.patientCount);
            item.put("conditionCount", conditionCounts.byBlock.getOrDefault(key(b.city, b.neighborhood, b.block), 0L));
            item.put("avgNews2", format1(bAvg));
            item.put("vitalSignAverages", toVitalSignAverages(blockVitalAcc.get(key(b.city, b.neighborhood, b.block))));
            blockStats.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cityFilter", cityFilter);
        payload.put("totalPatients", totalPatients);
        payload.put("totalConditions", totalConditions);
        payload.put("avgNews2", format1(avgNews2));
        payload.put("cityStats", cityStats);
        payload.put("neighborhoodStats", neighborhoodStats);
        payload.put("blockStats", blockStats);
        return payload;
    }

    private PatientStats collectPatientStats(String cityFilter) {
        PatientStats out = new PatientStats();
        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        List<IBaseResource> patients = getAllResources(patientDao.search(patientSearch));

        for (IBaseResource resource : patients) {
            if (!(resource instanceof Patient patient)) continue;

            String city = extractLocationNested(patient, "city");
            String neighborhood = extractLocationNested(patient, "neighborhood");
            String block = extractLocationNested(patient, "block");

            if (block == null || block.isBlank()) continue;
            if (cityFilter != null && (city == null || !cityFilter.equalsIgnoreCase(city))) continue;

            int score = extractPatientNews2Score(patient);
            out.totalPatients++;
            out.totalScore += score;

            CityAccumulator c = out.cityAcc.computeIfAbsent(asString(city), k -> new CityAccumulator(city));
            c.patientCount++;
            c.totalScore += score;

            String nKey = key(city, neighborhood, "");
            NeighborhoodAccumulator n = out.neighborhoodAcc.computeIfAbsent(nKey, k -> new NeighborhoodAccumulator(city, neighborhood));
            n.patientCount++;
            n.totalScore += score;

            String bKey = key(city, neighborhood, block);
            BlockAccumulator b = out.blockAcc.computeIfAbsent(bKey, k -> new BlockAccumulator(city, neighborhood, block));
            b.patientCount++;
            b.totalScore += score;
        }

        return out;
    }

    private int extractPatientNews2Score(Patient patient) {
        if (patient == null) return 0;
        Extension ext = patient.getExtensionByUrl("http://news2-score");
        if (ext != null && ext.getValue() instanceof IntegerType it && it.getValue() != null) {
            return it.getValue();
        }
        return 0;
    }

    private void writeJson(HttpServletResponse response, Map<String, Object> payload) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"cityFilter\":\"").append(escapeJson(asString(payload.get("cityFilter")))).append("\",");
        json.append("\"totalPatients\":").append(payload.get("totalPatients")).append(",");
        json.append("\"totalConditions\":").append(payload.get("totalConditions")).append(",");
        json.append("\"avgNews2\":").append(payload.get("avgNews2")).append(",");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cityStats = (List<Map<String, Object>>) payload.get("cityStats");
        json.append("\"cityStats\":[");
        for (int i = 0; i < cityStats.size(); i++) {
            if (i > 0) json.append(",");
            Map<String, Object> c = cityStats.get(i);
            json.append("{")
                    .append("\"city\":\"").append(escapeJson(asString(c.get("city")))).append("\",")
                    .append("\"patientCount\":").append(c.get("patientCount")).append(",")
                    .append("\"conditionCount\":").append(c.get("conditionCount")).append(",")
                    .append("\"avgNews2\":").append(c.get("avgNews2")).append(",")
                    .append("\"vitalSignAverages\":");
                appendVitalSignArray(json, castVitalList(c.get("vitalSignAverages")));
                json.append("}");
        }
        json.append("],");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> neighborhoodStats = (List<Map<String, Object>>) payload.get("neighborhoodStats");
        json.append("\"neighborhoodStats\":[");
        for (int i = 0; i < neighborhoodStats.size(); i++) {
            if (i > 0) json.append(",");
            Map<String, Object> n = neighborhoodStats.get(i);
            json.append("{")
                    .append("\"city\":\"").append(escapeJson(asString(n.get("city")))).append("\",")
                    .append("\"neighborhood\":\"").append(escapeJson(asString(n.get("neighborhood")))).append("\",")
                    .append("\"patientCount\":").append(n.get("patientCount")).append(",")
                    .append("\"conditionCount\":").append(n.get("conditionCount")).append(",")
                    .append("\"avgNews2\":").append(n.get("avgNews2")).append(",")
                    .append("\"vitalSignAverages\":");
                appendVitalSignArray(json, castVitalList(n.get("vitalSignAverages")));
                json.append("}");
        }
        json.append("],");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockStats = (List<Map<String, Object>>) payload.get("blockStats");
        json.append("\"blockStats\":[");
        for (int i = 0; i < blockStats.size(); i++) {
            if (i > 0) json.append(",");
            Map<String, Object> b = blockStats.get(i);
            json.append("{")
                    .append("\"neighborhood\":\"").append(escapeJson(asString(b.get("neighborhood")))).append("\",")
                    .append("\"city\":\"").append(escapeJson(asString(b.get("city")))).append("\",")
                    .append("\"block\":\"").append(escapeJson(asString(b.get("block")))).append("\",")
                    .append("\"patientCount\":").append(b.get("patientCount")).append(",")
                    .append("\"conditionCount\":").append(b.get("conditionCount")).append(",")
                    .append("\"avgNews2\":").append(b.get("avgNews2")).append(",")
                    .append("\"vitalSignAverages\":");
                appendVitalSignArray(json, castVitalList(b.get("vitalSignAverages")));
                json.append("}");
        }
        json.append("]");

        json.append("}");
        response.getWriter().write(json.toString());
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private String format1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String key(String city, String neighborhood, String block) {
        return asString(city) + "|" + asString(neighborhood) + "|" + asString(block);
    }

    private void loadVitalSignAverages(
            String cityFilter,
            Map<String, Map<String, VitalSignAccumulator>> cityAcc,
            Map<String, Map<String, VitalSignAccumulator>> neighborhoodAcc,
            Map<String, Map<String, VitalSignAccumulator>> blockAcc
    ) {
        IFhirResourceDao<Observation> observationDao = daoRegistry.getResourceDao(Observation.class);
        SearchParameterMap avgSearch = new SearchParameterMap();
        avgSearch.add("category", new TokenParam("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs-average"));
        avgSearch.setLoadSynchronous(true);
        IBundleProvider avgResults = observationDao.search(avgSearch);
        List<IBaseResource> observations = getAllResources(avgResults);

        for (IBaseResource resource : observations) {
            if (!(resource instanceof Observation obs)) continue;

            String city = extractLocationNested(obs, "city");
            String neighborhood = extractLocationNested(obs, "neighborhood");
            String block = extractLocationNested(obs, "block");

            if (cityFilter != null && (city == null || !cityFilter.equalsIgnoreCase(city))) {
                continue;
            }

            VitalSignAverage vsa = fromObservation(obs);
            if (vsa == null) continue;

            accumulate(cityAcc, asString(city), vsa);
            accumulate(neighborhoodAcc, key(city, neighborhood, ""), vsa);
            accumulate(blockAcc, key(city, neighborhood, block), vsa);
        }
    }

    private ConditionCounts collectConditionCounts(String cityFilter) {
        ConditionCounts out = new ConditionCounts();

        IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
        SearchParameterMap patientSearch = new SearchParameterMap();
        patientSearch.setLoadSynchronous(true);
        List<IBaseResource> patients = getAllResources(patientDao.search(patientSearch));

        Map<String, LocationTuple> patientLocation = new HashMap<>();
        for (IBaseResource resource : patients) {
            if (!(resource instanceof Patient patient)) continue;
            String pid = patient.getIdElement() != null ? patient.getIdElement().getIdPart() : null;
            if (pid == null || pid.isBlank()) continue;
            String city = extractLocationNested(patient, "city");
            String neighborhood = extractLocationNested(patient, "neighborhood");
            String block = extractLocationNested(patient, "block");
            patientLocation.put(pid, new LocationTuple(city, neighborhood, block));
        }

        IFhirResourceDao<Condition> conditionDao = daoRegistry.getResourceDao(Condition.class);
        SearchParameterMap conditionSearch = new SearchParameterMap();
        conditionSearch.setLoadSynchronous(true);
        List<IBaseResource> conditions = getAllResources(conditionDao.search(conditionSearch));

        for (IBaseResource resource : conditions) {
            if (!(resource instanceof Condition condition)) continue;
            if (!isActiveCondition(condition)) continue;

            String city = extractLocationNested(condition, "city");
            String neighborhood = extractLocationNested(condition, "neighborhood");
            String block = extractLocationNested(condition, "block");

            if ((block == null || block.isBlank()) && condition.getSubject() != null && condition.getSubject().getReferenceElement() != null) {
                String pid = condition.getSubject().getReferenceElement().getIdPart();
                LocationTuple fromPatient = patientLocation.get(pid);
                if (fromPatient != null) {
                    if (city == null || city.isBlank()) city = fromPatient.city;
                    if (neighborhood == null || neighborhood.isBlank()) neighborhood = fromPatient.neighborhood;
                    if (block == null || block.isBlank()) block = fromPatient.block;
                }
            }

            if (block == null || block.isBlank()) continue;
            if (cityFilter != null && (city == null || !cityFilter.equalsIgnoreCase(city))) continue;

            out.total++;
            if (city != null && !city.isBlank()) {
                out.byCity.merge(city, 1L, Long::sum);
            }
            out.byNeighborhood.merge(key(city, neighborhood, ""), 1L, Long::sum);
            out.byBlock.merge(key(city, neighborhood, block), 1L, Long::sum);
        }

        return out;
    }

    private String extractLocationNested(Patient patient, String url) {
        if (patient == null) return null;
        Extension loc = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (loc == null) return null;
        for (Extension nested : loc.getExtension()) {
            if (nested != null && url.equals(nested.getUrl()) && nested.getValue() != null) {
                return normalize(nested.getValue().primitiveValue());
            }
        }
        return null;
    }

    private String extractLocationNested(Condition condition, String url) {
        if (condition == null) return null;
        Extension loc = condition.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (loc == null) return null;
        for (Extension nested : loc.getExtension()) {
            if (nested != null && url.equals(nested.getUrl()) && nested.getValue() != null) {
                return normalize(nested.getValue().primitiveValue());
            }
        }
        return null;
    }

    private boolean isActiveCondition(Condition condition) {
        if (condition == null || !condition.hasClinicalStatus()) return false;
        return condition.getClinicalStatus().getCoding().stream()
                .anyMatch(c -> c != null && c.getCode() != null && "active".equalsIgnoreCase(c.getCode()));
    }

    private void accumulate(Map<String, Map<String, VitalSignAccumulator>> root, String scopeKey, VitalSignAverage vsa) {
        if (scopeKey == null || scopeKey.isBlank() || vsa == null) return;
        String vitalKey = asString(vsa.vitalSign);
        Map<String, VitalSignAccumulator> byVital = root.computeIfAbsent(scopeKey, k -> new LinkedHashMap<>());
        VitalSignAccumulator acc = byVital.computeIfAbsent(vitalKey, k -> new VitalSignAccumulator());
        acc.add(vsa.averageValue, vsa.sampleCount, vsa.unit);
    }

    private String extractLocationNested(Observation obs, String url) {
        if (obs == null) return null;
        Extension loc = obs.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (loc == null) return null;
        for (Extension nested : loc.getExtension()) {
            if (nested != null && url.equals(nested.getUrl()) && nested.getValue() != null) {
                return normalize(nested.getValue().primitiveValue());
            }
        }
        return null;
    }

    private VitalSignAverage fromObservation(Observation obs) {
        if (obs == null || !(obs.getValue() instanceof Quantity q) || q.getValue() == null) return null;
        VitalSignAverage out = new VitalSignAverage();
        out.vitalSign = obs.getCode() != null && obs.getCode().hasCoding()
                ? normalize(obs.getCode().getCodingFirstRep().getDisplay())
                : null;
        if (out.vitalSign == null || out.vitalSign.isBlank()) {
            out.vitalSign = obs.getCode() != null && obs.getCode().hasCoding()
                    ? normalize(obs.getCode().getCodingFirstRep().getCode())
                    : "unknown";
        }
        out.averageValue = q.getValue().doubleValue();
        out.unit = normalize(q.getUnit());
        Extension sampleExt = obs.getExtensionByUrl(SAMPLE_COUNT_EXTENSION_URL);
        if (sampleExt != null && sampleExt.getValue() instanceof IntegerType it && it.getValue() != null) {
            out.sampleCount = Math.max(1, it.getValue());
        } else {
            out.sampleCount = 1;
        }
        return out;
    }

    private List<VitalSignAverage> toVitalSignAverages(Map<String, VitalSignAccumulator> byVital) {
        List<VitalSignAverage> out = new ArrayList<>();
        if (byVital == null || byVital.isEmpty()) return out;
        for (Map.Entry<String, VitalSignAccumulator> e : byVital.entrySet()) {
            VitalSignAverage avg = e.getValue().toAverage(e.getKey());
            if (avg != null) out.add(avg);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<VitalSignAverage> castVitalList(Object value) {
        if (value == null) return List.of();
        return (List<VitalSignAverage>) value;
    }

    private void appendVitalSignArray(StringBuilder json, List<VitalSignAverage> list) {
        json.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            VitalSignAverage v = list.get(i);
            json.append("{")
                    .append("\"vitalSign\":\"").append(escapeJson(asString(v.vitalSign))).append("\",")
                    .append("\"averageValue\":").append(String.format(Locale.US, "%.2f", v.averageValue)).append(",")
                    .append("\"unit\":\"").append(escapeJson(asString(v.unit))).append("\",")
                    .append("\"sampleCount\":").append(v.sampleCount)
                    .append("}");
        }
        json.append("]");
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

    private static class NeighborhoodAccumulator {
        private final String city;
        private final String neighborhood;
        private int totalScore;
        private int patientCount;
        private long conditionCount;

        private NeighborhoodAccumulator(String city, String neighborhood) {
            this.city = city;
            this.neighborhood = neighborhood;
        }
    }

    private static class VitalSignAverage {
        private String vitalSign;
        private double averageValue;
        private String unit;
        private int sampleCount;
    }

    private static class VitalSignAccumulator {
        private double weightedValueSum;
        private int totalSamples;
        private String unit;

        private void add(double averageValue, int sampleCount, String unitValue) {
            int weight = sampleCount > 0 ? sampleCount : 1;
            weightedValueSum += averageValue * weight;
            totalSamples += weight;
            if ((unit == null || unit.isBlank()) && unitValue != null && !unitValue.isBlank()) {
                unit = unitValue;
            }
        }

        private VitalSignAverage toAverage(String vitalSignName) {
            if (totalSamples <= 0) return null;
            VitalSignAverage out = new VitalSignAverage();
            out.vitalSign = vitalSignName;
            out.averageValue = weightedValueSum / totalSamples;
            out.unit = unit == null ? "" : unit;
            out.sampleCount = totalSamples;
            return out;
        }
    }

    private static class LocationTuple {
        private final String city;
        private final String neighborhood;
        private final String block;

        private LocationTuple(String city, String neighborhood, String block) {
            this.city = city;
            this.neighborhood = neighborhood;
            this.block = block;
        }
    }

    private static class ConditionCounts {
        private long total;
        private final Map<String, Long> byCity = new HashMap<>();
        private final Map<String, Long> byNeighborhood = new HashMap<>();
        private final Map<String, Long> byBlock = new HashMap<>();
    }

    private static class PatientStats {
        private int totalPatients;
        private int totalScore;
        private final Map<String, CityAccumulator> cityAcc = new LinkedHashMap<>();
        private final Map<String, NeighborhoodAccumulator> neighborhoodAcc = new LinkedHashMap<>();
        private final Map<String, BlockAccumulator> blockAcc = new LinkedHashMap<>();
    }

    private static class CityAccumulator {
        private final String city;
        private int patientCount;
        private int totalScore;

        private CityAccumulator(String city) {
            this.city = city;
        }
    }

    private static class BlockAccumulator {
        private final String city;
        private final String neighborhood;
        private final String block;
        private int patientCount;
        private int totalScore;

        private BlockAccumulator(String city, String neighborhood, String block) {
            this.city = city;
            this.neighborhood = neighborhood;
            this.block = block;
        }
    }
}
