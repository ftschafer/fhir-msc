package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        String where = cityFilter == null ? "" : " WHERE b.id.city = :city";

        var totalsQuery = em.createQuery(
                "SELECT COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) FROM BlockNews2Aggregate b" + where,
                Object[].class
        );
        if (cityFilter != null) totalsQuery.setParameter("city", cityFilter);
        Object[] totals = totalsQuery.getSingleResult();

        int totalScore = ((Number) totals[0]).intValue();
        int totalPatients = ((Number) totals[1]).intValue();
        double avgNews2 = totalPatients == 0 ? 0.0 : (double) totalScore / totalPatients;

        var cityQuery = em.createQuery(
                "SELECT b.id.city, COALESCE(SUM(b.totalScore),0), COALESCE(SUM(b.patientCount),0) " +
                        "FROM BlockNews2Aggregate b" + where + " GROUP BY b.id.city ORDER BY b.id.city",
                Object[].class
        );
        if (cityFilter != null) cityQuery.setParameter("city", cityFilter);
        List<Object[]> cityRows = cityQuery.getResultList();

        var blocksQuery = em.createQuery(
                "SELECT b.id.neighborhood, b.id.city, b.id.block, b.totalScore, b.patientCount " +
                        "FROM BlockNews2Aggregate b" + where + " ORDER BY b.id.city, b.id.neighborhood, b.id.block",
                Object[].class
        );
        if (cityFilter != null) blocksQuery.setParameter("city", cityFilter);
        List<Object[]> blockRows = blocksQuery.getResultList();

        List<Map<String, Object>> cityStats = new ArrayList<>();
        for (Object[] row : cityRows) {
            String city = (String) row[0];
            int cTotal = ((Number) row[1]).intValue();
            int cCount = ((Number) row[2]).intValue();
            double cAvg = cCount == 0 ? 0.0 : (double) cTotal / cCount;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("city", city);
            item.put("patientCount", cCount);
            item.put("totalScore", cTotal);
            item.put("avgNews2", format1(cAvg));
            cityStats.add(item);
        }

        List<Map<String, Object>> blockStats = new ArrayList<>();
        for (Object[] row : blockRows) {
            String neighborhood = (String) row[0];
            String city = (String) row[1];
            String block = (String) row[2];
            int bTotal = ((Number) row[3]).intValue();
            int bCount = ((Number) row[4]).intValue();
            double bAvg = bCount == 0 ? 0.0 : (double) bTotal / bCount;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("neighborhood", neighborhood);
            item.put("city", city);
            item.put("block", block);
            item.put("patientCount", bCount);
            item.put("totalScore", bTotal);
            item.put("avgNews2", format1(bAvg));
            blockStats.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cityFilter", cityFilter);
        payload.put("totalPatients", totalPatients);
        payload.put("totalScore", totalScore);
        payload.put("avgNews2", format1(avgNews2));
        payload.put("cityStats", cityStats);
        payload.put("blockStats", blockStats);
        return payload;
    }

    private void writeJson(HttpServletResponse response, Map<String, Object> payload) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"cityFilter\":\"").append(escapeJson(asString(payload.get("cityFilter")))).append("\",");
        json.append("\"totalPatients\":").append(payload.get("totalPatients")).append(",");
        json.append("\"totalScore\":").append(payload.get("totalScore")).append(",");
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
                    .append("\"totalScore\":").append(c.get("totalScore")).append(",")
                    .append("\"avgNews2\":").append(c.get("avgNews2"))
                    .append("}");
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
                    .append("\"totalScore\":").append(b.get("totalScore")).append(",")
                    .append("\"avgNews2\":").append(b.get("avgNews2"))
                    .append("}");
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
}
