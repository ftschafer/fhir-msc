package ca.uhn.fhir.jpa.starter.cdss;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service to maintain a 30-day rolling history of vital sign averages.
 * Stores historical data points in memory for charting purposes.
 */
@Service
public class VitalSignHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(VitalSignHistoryService.class);

    // In-memory storage: blockName -> vitalSignCode -> list of historical data points
    private final Map<String, Map<String, List<HistoricalDataPoint>>> historyStore = new ConcurrentHashMap<>();
    
    // Maximum days to keep in history
    private static final int MAX_HISTORY_DAYS = 30;
    
    // Vital sign code names for display
    private static final Map<String, String> VITAL_SIGN_NAMES = new HashMap<String, String>() {{
        put("8867-4", "Heart rate");
        put("9279-1", "Respiratory rate");
        put("8480-6", "Systolic blood pressure");
        put("8462-4", "Diastolic blood pressure");
        put("8310-5", "Body temperature");
        put("59408-5", "Oxygen saturation");
    }};

    /**
     * Add a new data point to the history
     */
    public void addDataPoint(String blockName, String vitalSignCode, double averageValue, int sampleCount) {
        logger.info("Adding data point: block={}, code={}, value={}, samples={}", 
                   blockName, vitalSignCode, averageValue, sampleCount);
        
        // Get or create block map
        Map<String, List<HistoricalDataPoint>> blockHistory = historyStore.computeIfAbsent(
            blockName, 
            k -> new ConcurrentHashMap<>()
        );
        
        // Get or create vital sign history list
        List<HistoricalDataPoint> history = blockHistory.computeIfAbsent(
            vitalSignCode, 
            k -> Collections.synchronizedList(new ArrayList<>())
        );
        
        // Create new data point
        HistoricalDataPoint dataPoint = new HistoricalDataPoint();
        dataPoint.timestamp = Instant.now();
        dataPoint.averageValue = averageValue;
        dataPoint.sampleCount = sampleCount;
        
        // Add to list (synchronized)
        synchronized (history) {
            history.add(dataPoint);
            
            // Clean up old data points (older than 30 days)
            Instant cutoffTime = Instant.now().minus(MAX_HISTORY_DAYS, ChronoUnit.DAYS);
            int removed = 0;
            Iterator<HistoricalDataPoint> it = history.iterator();
            while (it.hasNext()) {
                if (it.next().timestamp.isBefore(cutoffTime)) {
                    it.remove();
                    removed++;
                }
            }
            
            logger.info("History updated: block={}, code={}, total points={}, removed={}", 
                       blockName, vitalSignCode, history.size(), removed);
        }
    }

    /**
     * Get historical data for a specific block and vital sign
     */
    public List<HistoricalDataPoint> getHistory(String blockName, String vitalSignCode) {
        Map<String, List<HistoricalDataPoint>> blockHistory = historyStore.get(blockName);
        if (blockHistory == null) {
            return Collections.emptyList();
        }
        
        List<HistoricalDataPoint> history = blockHistory.get(vitalSignCode);
        if (history == null) {
            return Collections.emptyList();
        }
        
        // Return a copy to avoid concurrent modification issues
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /**
     * Get all historical data for a block (all vital signs)
     */
    public Map<String, List<HistoricalDataPoint>> getAllHistory(String blockName) {
        Map<String, List<HistoricalDataPoint>> blockHistory = historyStore.get(blockName);
        if (blockHistory == null) {
            return Collections.emptyMap();
        }
        
        // Return a deep copy
        Map<String, List<HistoricalDataPoint>> result = new HashMap<>();
        blockHistory.forEach((code, history) -> {
            synchronized (history) {
                result.put(code, new ArrayList<>(history));
            }
        });
        
        return result;
    }

    /**
     * Get all historical data points for charting (not aggregated)
     * Returns all data points within the specified time window
     */
    public Map<String, List<TimeSeriesPoint>> getTimeSeriesData(String blockName, int days) {
        Map<String, List<HistoricalDataPoint>> allHistory = getAllHistory(blockName);
        Map<String, List<TimeSeriesPoint>> result = new HashMap<>();
        
        Instant cutoffTime = Instant.now().minus(days, ChronoUnit.DAYS);
        
        for (Map.Entry<String, List<HistoricalDataPoint>> entry : allHistory.entrySet()) {
            String vitalSignCode = entry.getKey();
            List<HistoricalDataPoint> dataPoints = entry.getValue();
            
            // Filter by days and convert to time series points
            List<TimeSeriesPoint> timeSeriesPoints = dataPoints.stream()
                .filter(dp -> dp.timestamp.isAfter(cutoffTime))
                .map(dp -> {
                    TimeSeriesPoint tsp = new TimeSeriesPoint();
                    tsp.timestamp = dp.timestamp.toString();
                    tsp.value = dp.averageValue;
                    tsp.samples = dp.sampleCount;
                    return tsp;
                })
                .sorted(Comparator.comparing(tsp -> tsp.timestamp))
                .collect(Collectors.toList());
            
            result.put(vitalSignCode, timeSeriesPoints);
        }
        
        return result;
    }

    /**
     * Get historical data aggregated by day for charting
     * Returns data grouped by date with one average per day
     */
    public Map<String, List<DailyAverage>> getDailyAverages(String blockName, int days) {
        Map<String, List<HistoricalDataPoint>> allHistory = getAllHistory(blockName);
        Map<String, List<DailyAverage>> result = new HashMap<>();
        
        Instant cutoffTime = Instant.now().minus(days, ChronoUnit.DAYS);
        
        for (Map.Entry<String, List<HistoricalDataPoint>> entry : allHistory.entrySet()) {
            String vitalSignCode = entry.getKey();
            List<HistoricalDataPoint> dataPoints = entry.getValue();
            
            // Filter by days and group by date
            Map<String, List<HistoricalDataPoint>> byDate = dataPoints.stream()
                .filter(dp -> dp.timestamp.isAfter(cutoffTime))
                .collect(Collectors.groupingBy(dp -> {
                    // Group by date (YYYY-MM-DD)
                    return dp.timestamp.truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10);
                }));
            
            // Calculate daily averages
            List<DailyAverage> dailyAverages = byDate.entrySet().stream()
                .map(dateEntry -> {
                    String date = dateEntry.getKey();
                    List<HistoricalDataPoint> dayPoints = dateEntry.getValue();
                    
                    double avgValue = dayPoints.stream()
                        .mapToDouble(dp -> dp.averageValue)
                        .average()
                        .orElse(0.0);
                    
                    int totalSamples = dayPoints.stream()
                        .mapToInt(dp -> dp.sampleCount)
                        .sum();
                    
                    DailyAverage daily = new DailyAverage();
                    daily.date = date;
                    daily.averageValue = avgValue;
                    daily.sampleCount = totalSamples;
                    return daily;
                })
                .sorted(Comparator.comparing(da -> da.date))
                .collect(Collectors.toList());
            
            result.put(vitalSignCode, dailyAverages);
        }
        
        return result;
    }

    /**
     * Get vital sign name from code
     */
    public String getVitalSignName(String code) {
        return VITAL_SIGN_NAMES.getOrDefault(code, code);
    }

    /**
     * Clear all history (useful for testing)
     */
    public void clearHistory() {
        historyStore.clear();
    }

    /**
     * Clear history for a specific block
     */
    public void clearBlockHistory(String blockName) {
        historyStore.remove(blockName);
    }

    /**
     * Get all blocks with stored history
     */
    public Set<String> getAvailableBlocks() {
        return new HashSet<>(historyStore.keySet());
    }

    // Data classes
    public static class HistoricalDataPoint {
        public Instant timestamp;
        public double averageValue;
        public int sampleCount;
    }

    public static class DailyAverage {
        public String date;
        public double averageValue;
        public int sampleCount;
    }

    public static class TimeSeriesPoint {
        public String timestamp;
        public double value;
        public int samples;
    }
}
