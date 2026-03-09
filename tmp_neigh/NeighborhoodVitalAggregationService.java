package ca.uhn.fhir.jpa.starter.common;

import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Aggregates block-level vital sign averages into neighborhood-level averages
 * and forwards them upstream
 */
@Service
public class NeighborhoodVitalAggregationService {

    private static final Logger logger = LoggerFactory.getLogger(NeighborhoodVitalAggregationService.class);
    private static final String STATISTICS_CODE_URL = "http://hl7.org/fhir/StructureDefinition/observation-statisticsCode";
    private static final String SAMPLE_COUNT_URL = "http://observation-sample-count";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";
    private static final String NEIGHBORHOOD_URL = "neighborhood";
    private static final String NEWS2_IDENTIFIER_SYSTEM = "urn:aggregate:news2";
    private static final String BLOCK_AVERAGE_SUFFIX = "|block-average";
    private static final String NEIGHBORHOOD_AVERAGE_SUFFIX = "|neighborhood-average";
    private static final String NEWS2_EXTENSION_URL = "http://news2-score";

    @Value("${location.neighborhood:center}")
    private String neighborhoodValue;

    private final UpstreamForwarder upstreamForwarder;

    public NeighborhoodVitalAggregationService(UpstreamForwarder upstreamForwarder) {
        this.upstreamForwarder = upstreamForwarder;
    }

    /**
     * Process block-level vital sign averages and aggregate them by neighborhood
     */
    @Transactional
    public void processBlockAverages(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;

        List<Observation> incomingNews2BlockAverages = observations.stream()
                .filter(this::isNews2BlockAverage)
                .toList();

        if (!incomingNews2BlockAverages.isEmpty()) {
            forwardNews2NeighborhoodAverages(incomingNews2BlockAverages);
        }

        // Filter only observations marked as statistics=average
        List<Observation> validAverages = observations.stream()
                .filter(this::isStatisticsAverage)
                .toList();

        if (validAverages.isEmpty()) return;

        logger.debug("Processing {} block-level vital sign averages", validAverages.size());

        // Group by code only (each block observation already represents aggregated data for that block)
        Map<String, List<Observation>> byCode = new HashMap<>();

        for (Observation obs : validAverages) {
            String code = obs.hasCode() && obs.getCode().hasCoding() 
                    ? obs.getCode().getCodingFirstRep().getCode() : null;

            if (code == null) continue;

            byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(obs);
        }

        List<Observation> neighborhoodAverages = new ArrayList<>();

        // Calculate neighborhood averages - one per vital sign code
        for (Map.Entry<String, List<Observation>> entry : byCode.entrySet()) {
            String code = entry.getKey();
            List<Observation> blockObs = entry.getValue();
            if (blockObs.isEmpty()) continue;

            Observation neighborhoodAvg = calculateNeighborhoodAverage(code, blockObs);
            if (neighborhoodAvg != null) {
                neighborhoodAverages.add(neighborhoodAvg);
            }
        }

        if (!neighborhoodAverages.isEmpty()) {
            logger.info("Forwarding {} neighborhood-level vital sign averages upstream", neighborhoodAverages.size());
            upstreamForwarder.createObservations(neighborhoodAverages);
        }
    }

    private void forwardNews2NeighborhoodAverages(List<Observation> incomingNews2BlockAverages) {
        Map<String, List<Observation>> byNeighborhood = new HashMap<>();
        for (Observation observation : incomingNews2BlockAverages) {
            String neighborhood = resolveNeighborhood(observation);
            byNeighborhood.computeIfAbsent(neighborhood, k -> new ArrayList<>()).add(observation);
        }

        List<Observation> toForward = new ArrayList<>();

        for (Map.Entry<String, List<Observation>> entry : byNeighborhood.entrySet()) {
            String neighborhood = entry.getKey();
            List<Observation> blockObservations = entry.getValue();

            Observation neighborhoodAverage = buildNeighborhoodNews2Average(neighborhood, blockObservations);
            if (neighborhoodAverage != null) {
                toForward.add(neighborhoodAverage);
            }

            for (Observation blockObservation : blockObservations) {
                Observation copy = blockObservation.copy();
                ensureLocationExtensions(copy, extractBlock(copy), neighborhood);
                toForward.add(copy);
            }
        }

        if (!toForward.isEmpty()) {
            logger.info("Forwarding {} NEWS2 aggregate observation(s) upstream (neighborhood + block)", toForward.size());
            upstreamForwarder.createObservations(toForward);
        }
    }

    private Observation buildNeighborhoodNews2Average(String neighborhood, List<Observation> blockObservations) {
        if (blockObservations == null || blockObservations.isEmpty()) {
            return null;
        }

        Observation template = blockObservations.get(0);

        BigDecimal weightedSum = BigDecimal.ZERO;
        int totalSamples = 0;

        for (Observation observation : blockObservations) {
            BigDecimal value = extractValue(observation);
            if (value == null) {
                continue;
            }

            int sampleCount = extractSampleCount(observation);
            int weight = sampleCount > 0 ? sampleCount : 1;
            weightedSum = weightedSum.add(value.multiply(BigDecimal.valueOf(weight)));
            totalSamples += weight;
        }

        if (totalSamples <= 0) {
            return null;
        }

        BigDecimal average = weightedSum.divide(BigDecimal.valueOf(totalSamples), 12, RoundingMode.HALF_UP);
        logger.info("Computed NEWS2 neighborhood average: neighborhood={} average={} samples={} blocks={}",
            neighborhood,
            average,
            totalSamples,
            blockObservations.size());

        Observation neighborhoodObs = new Observation();
        neighborhoodObs.setStatus(Observation.ObservationStatus.FINAL);
        neighborhoodObs.getIdentifierFirstRep()
                .setSystem(NEWS2_IDENTIFIER_SYSTEM)
                .setValue(neighborhood + NEIGHBORHOOD_AVERAGE_SUFFIX);
        neighborhoodObs.setCode(template.getCode().copy());
        neighborhoodObs.setCategory(template.getCategory());

        Quantity quantity = new Quantity();
        quantity.setValue(average);
        if (template.hasValueQuantity()) {
            quantity.setUnit(template.getValueQuantity().getUnit());
            quantity.setSystem(template.getValueQuantity().getSystem());
            quantity.setCode(template.getValueQuantity().getCode());
        }
        neighborhoodObs.setValue(quantity);

        int declaredSamples = blockObservations.stream().mapToInt(this::extractSampleCount).sum();
        neighborhoodObs.addExtension(new Extension(SAMPLE_COUNT_URL, new IntegerType(declaredSamples > 0 ? declaredSamples : totalSamples)));

        Date mostRecent = blockObservations.stream()
                .filter(Observation::hasEffectiveDateTimeType)
                .map(obs -> obs.getEffectiveDateTimeType().getValue())
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(new Date());
        neighborhoodObs.setEffective(new DateTimeType(mostRecent));

        ensureLocationExtensions(neighborhoodObs, null, neighborhood);
        return neighborhoodObs;
    }

    private boolean isNews2BlockAverage(Observation observation) {
        if (observation == null || !observation.hasIdentifier()) {
            return false;
        }

        for (Identifier identifier : observation.getIdentifier()) {
            if (NEWS2_IDENTIFIER_SYSTEM.equals(identifier.getSystem())
                    && identifier.getValue() != null
                    && identifier.getValue().endsWith(BLOCK_AVERAGE_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    private String extractBlock(Observation observation) {
        if (observation == null) {
            return null;
        }

        Extension location = observation.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (location != null) {
            for (Extension nested : location.getExtension()) {
                if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                    String value = normalize(nested.getValue().primitiveValue());
                    if (value != null) {
                        return value;
                    }
                }
            }
        }

        for (Identifier identifier : observation.getIdentifier()) {
            if (NEWS2_IDENTIFIER_SYSTEM.equals(identifier.getSystem()) && identifier.getValue() != null) {
                String v = identifier.getValue();
                if (v.endsWith(BLOCK_AVERAGE_SUFFIX)) {
                    return normalize(v.substring(0, v.length() - BLOCK_AVERAGE_SUFFIX.length()));
                }
            }
        }
        return null;
    }

    private String resolveNeighborhood(Observation observation) {
        if (observation != null) {
            Extension location = observation.getExtensionByUrl(LOCATION_EXTENSION_URL);
            if (location != null) {
                for (Extension nested : location.getExtension()) {
                    if (NEIGHBORHOOD_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                        String value = normalize(nested.getValue().primitiveValue());
                        if (value != null) {
                            return value;
                        }
                    }
                }
            }
        }
        return normalize(neighborhoodValue) == null ? "center" : normalize(neighborhoodValue);
    }

    private void ensureLocationExtensions(Observation observation, String block, String neighborhood) {
        Extension location = observation.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (location == null) {
            location = new Extension(LOCATION_EXTENSION_URL);
            observation.addExtension(location);
        }

        String normalizedNeighborhood = normalize(neighborhood);
        String normalizedBlock = normalize(block);

        if (normalizedBlock != null) {
            Extension blockExt = location.getExtension().stream()
                    .filter(ext -> BLOCK_URL.equals(ext.getUrl()))
                    .findFirst()
                    .orElse(null);
            if (blockExt == null) {
                location.addExtension(new Extension().setUrl(BLOCK_URL).setValue(new StringType(normalizedBlock)));
            } else if (blockExt.getValue() == null || blockExt.getValue().primitiveValue() == null || blockExt.getValue().primitiveValue().isBlank()) {
                blockExt.setValue(new StringType(normalizedBlock));
            }
        }

        if (normalizedNeighborhood != null) {
            Extension neighborhoodExt = location.getExtension().stream()
                    .filter(ext -> NEIGHBORHOOD_URL.equals(ext.getUrl()))
                    .findFirst()
                    .orElse(null);
            if (neighborhoodExt == null) {
                location.addExtension(new Extension().setUrl(NEIGHBORHOOD_URL).setValue(new StringType(normalizedNeighborhood)));
            } else if (neighborhoodExt.getValue() == null || neighborhoodExt.getValue().primitiveValue() == null || neighborhoodExt.getValue().primitiveValue().isBlank()) {
                neighborhoodExt.setValue(new StringType(normalizedNeighborhood));
            }
        }
    }

    private boolean isStatisticsAverage(Observation observation) {
        if (!observation.hasExtension()) return false;
        
        // Check for observation-statisticsCode extension with statistics=average
        for (Extension ext : observation.getExtension()) {
            if (STATISTICS_CODE_URL.equals(ext.getUrl())) {
                // Handle valueCoding
                if (ext.getValue() instanceof Coding coding) {
                    if ("average".equals(coding.getCode())) {
                        return true;
                    }
                }
                // Handle valueCodeableConcept (fallback)
                else if (ext.getValue() instanceof CodeableConcept cc) {
                    for (Coding coding : cc.getCoding()) {
                        if ("average".equals(coding.getCode())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Calculate neighborhood average from multiple block averages
     * Each block observation represents the average for that block across all patients
     * This creates one neighborhood-level observation per vital sign code
     */
    private Observation calculateNeighborhoodAverage(String code, List<Observation> blockAverages) {
        if (blockAverages.isEmpty()) return null;

        Observation template = blockAverages.get(0);
        
        // Extract values and calculate average across all blocks
        BigDecimal avg = calculateNeighborhoodValue(blockAverages);
        if (avg == null) return null;

        // Create neighborhood-level observation (not tied to a specific patient)
        Observation neighborhoodObs = new Observation();
        
        // Create deterministic ID based on neighborhood and code
        String neighborhoodId = "neighborhood-" + neighborhoodValue + "-" + code + "-avg";
        neighborhoodObs.setId(neighborhoodId);

        neighborhoodObs.setStatus(Observation.ObservationStatus.FINAL);
        
        // No subject - this is a neighborhood-level aggregate, not patient-specific
        // If upstream requires a subject, use a generic reference
        // neighborhoodObs.setSubject(new Reference("Organization/" + neighborhoodValue));
        
        neighborhoodObs.setCode(template.getCode().copy());
        neighborhoodObs.setCategory(template.getCategory());

        // Preserve statistics marker and provide aggregated sample count when available
        if (template.hasExtension()) {
            for (Extension ext : template.getExtension()) {
                if (STATISTICS_CODE_URL.equals(ext.getUrl())) {
                    neighborhoodObs.addExtension(ext.copy());
                }
            }
        }
        int totalSampleCount = blockAverages.stream().mapToInt(this::extractSampleCount).sum();
        if (totalSampleCount > 0) {
            neighborhoodObs.addExtension(new Extension()
                    .setUrl(SAMPLE_COUNT_URL)
                    .setValue(new IntegerType(totalSampleCount)));
        }

        // Set averaged value
        Quantity quantity = new Quantity();
        quantity.setValue(avg);
        if (template.hasValueQuantity()) {
            quantity.setUnit(template.getValueQuantity().getUnit());
            quantity.setSystem(template.getValueQuantity().getSystem());
            quantity.setCode(template.getValueQuantity().getCode());
        }
        neighborhoodObs.setValue(quantity);

        // Use most recent effective date from all blocks
        Date mostRecent = blockAverages.stream()
                .filter(Observation::hasEffectiveDateTimeType)
                .map(obs -> obs.getEffectiveDateTimeType().getValue())
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(new Date());
        neighborhoodObs.setEffective(new DateTimeType(mostRecent));

        return neighborhoodObs;
    }

    private BigDecimal extractValue(Observation obs) {
        if (obs.hasValueQuantity() && obs.getValueQuantity().hasValue()) {
            return obs.getValueQuantity().getValue();
        }
        if (obs.hasValueIntegerType()) {
            Integer value = obs.getValueIntegerType().getValue();
            return value == null ? null : BigDecimal.valueOf(value);
        }
        if (obs.getValue() instanceof DecimalType decimalType && decimalType.getValue() != null) {
            return decimalType.getValue();
        }
        Extension news2Ext = obs.getExtensionByUrl(NEWS2_EXTENSION_URL);
        if (news2Ext != null && news2Ext.getValue() != null) {
            if (news2Ext.getValue() instanceof DecimalType decimalType && decimalType.getValue() != null) {
                return decimalType.getValue();
            }
            if (news2Ext.getValue() instanceof IntegerType integerType && integerType.getValue() != null) {
                return BigDecimal.valueOf(integerType.getValue());
            }
            if (news2Ext.getValue() instanceof StringType stringType && stringType.getValue() != null) {
                try {
                    return new BigDecimal(stringType.getValue().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private BigDecimal calculateNeighborhoodValue(List<Observation> blockAverages) {
        if (blockAverages == null || blockAverages.isEmpty()) {
            return null;
        }

        if (blockAverages.size() == 1) {
            // Single block: keep exact value from previous layer (no re-rounding)
            return extractValue(blockAverages.get(0));
        }

        BigDecimal weightedSum = BigDecimal.ZERO;
        int totalWeight = 0;
        int countWithoutWeight = 0;
        BigDecimal sumWithoutWeight = BigDecimal.ZERO;

        for (Observation obs : blockAverages) {
            BigDecimal value = extractValue(obs);
            if (value == null) {
                continue;
            }

            int sampleCount = extractSampleCount(obs);
            if (sampleCount > 0) {
                weightedSum = weightedSum.add(value.multiply(BigDecimal.valueOf(sampleCount)));
                totalWeight += sampleCount;
            } else {
                sumWithoutWeight = sumWithoutWeight.add(value);
                countWithoutWeight++;
            }
        }

        if (totalWeight > 0) {
            return weightedSum.divide(BigDecimal.valueOf(totalWeight), 12, RoundingMode.HALF_UP);
        }

        if (countWithoutWeight > 0) {
            return sumWithoutWeight.divide(BigDecimal.valueOf(countWithoutWeight), 12, RoundingMode.HALF_UP);
        }

        return null;
    }

    private int extractSampleCount(Observation obs) {
        if (obs == null || !obs.hasExtension()) {
            return 0;
        }

        for (Extension ext : obs.getExtension()) {
            if (SAMPLE_COUNT_URL.equals(ext.getUrl()) && ext.getValue() instanceof IntegerType integerType
                    && integerType.getValue() != null) {
                return Math.max(integerType.getValue(), 0);
            }
        }

        return 0;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
