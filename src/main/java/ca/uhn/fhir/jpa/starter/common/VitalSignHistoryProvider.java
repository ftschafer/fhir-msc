package ca.uhn.fhir.jpa.starter.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.starter.cdss.VitalSignHistoryService;
import ca.uhn.fhir.jpa.starter.cdss.VitalSignHistoryService.TimeSeriesPoint;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Provides historical vital sign data for charting.
 * Accessible at: GET [base]/Observation/$vital-history?block=North&days=30
 */
@Component
public class VitalSignHistoryProvider implements IResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(VitalSignHistoryProvider.class);

    @Autowired
    private VitalSignHistoryService historyService;

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Observation.class;
    }

    @Operation(name = "$vital-history", idempotent = true, manualResponse = true)
    public void getVitalSignHistory(
            @OperationParam(name = "block") StringType blockParam,
            @OperationParam(name = "days") StringType daysParam,
            HttpServletRequest request, 
            HttpServletResponse response) {
        
        try {
            String block = blockParam != null ? blockParam.getValue() : "Block-Default";
            int days = daysParam != null ? parseInt(daysParam.getValue(), 30) : 30;
            
            logger.info("$vital-history called: block={}, days={}", block, days);
            
            // Limit to reasonable range
            if (days < 1) days = 1;
            if (days > 30) days = 30;
            
            Map<String, List<TimeSeriesPoint>> timeSeriesData = historyService.getTimeSeriesData(block, days);
            logger.info("Retrieved history for {} vital signs, total data points: {}", 
                       timeSeriesData.size(), 
                       timeSeriesData.values().stream().mapToInt(List::size).sum());
            
            writeJsonResponse(response, timeSeriesData);
            
        } catch (Exception e) {
            try {
                response.setStatus(500);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception ignored) {}
        }
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void writeJsonResponse(HttpServletResponse response, Map<String, List<TimeSeriesPoint>> data) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        StringBuilder json = new StringBuilder();
        json.append("{");
        
        // Write vital sign codes as keys
        boolean first = true;
        for (Map.Entry<String, List<TimeSeriesPoint>> entry : data.entrySet()) {
            if (!first) json.append(",");
            first = false;
            
            String vitalSignCode = entry.getKey();
            String vitalSignName = historyService.getVitalSignName(vitalSignCode);
            List<TimeSeriesPoint> timeSeriesData = entry.getValue();
            
            json.append("\"").append(vitalSignCode).append("\":{");
            json.append("\"name\":\"").append(escapeJson(vitalSignName)).append("\",");
            json.append("\"data\":[");
            
            for (int i = 0; i < timeSeriesData.size(); i++) {
                if (i > 0) json.append(",");
                TimeSeriesPoint tsp = timeSeriesData.get(i);
                json.append("{");
                json.append("\"timestamp\":\"").append(tsp.timestamp).append("\",");
                json.append("\"value\":").append(String.format(Locale.US, "%.2f", tsp.value)).append(",");
                json.append("\"samples\":").append(tsp.samples);
                json.append("}");
            }
            
            json.append("]");
            json.append("}");
        }
        
        json.append("}");
        
        response.getWriter().write(json.toString());
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
}
