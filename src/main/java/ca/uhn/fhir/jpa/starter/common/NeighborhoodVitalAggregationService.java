package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    private static final String AVG_CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";
    private static final String AVG_CATEGORY_CODE = "vital-signs-average";
    private static final String STATISTICS_CODE_URL = "http://hl7.org/fhir/StructureDefinition/observation-statisticsCode";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";

    @Value("${location.neighborhood:center}")
    private String neighborhoodValue;

    @PersistenceContext
    private EntityManager em;

    private final DaoRegistry daoRegistry;
    private final UpstreamForwarder upstreamForwarder;

    public NeighborhoodVitalAggregationService(DaoRegistry daoRegistry, UpstreamForwarder upstreamForwarder) {
        this.daoRegistry = daoRegistry;
        this.upstreamForwarder = upstreamForwarder;
    }

    private IFhirResourceDao<Observation> observationDao() {
        return daoRegistry.getResourceDao(Observation.class);
    }

    /**
     * Process block-level vital sign averages and aggregate them by neighborhood
     */
    @Transactional
    public void processBlockAverages(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;

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

    private boolean isStatisticsAverage(Observation observation) {
        if (!observation.hasExtension()) return false;
        
        // Check for observation-statisticsCode extension with statistics=average
        for (Extension ext : observation.getExtension()) {
            if (STATISTICS_CODE_URL.equals(ext.getUrl())) {
                // Handle valueCoding
                if (ext.getValue() instanceof Coding) {
                    Coding coding = (Coding) ext.getValue();
                    if ("average".equals(coding.getCode())) {
                        return true;
                    }
                }
                // Handle valueCodeableConcept (fallback)
                else if (ext.getValue() instanceof CodeableConcept) {
                    CodeableConcept cc = (CodeableConcept) ext.getValue();
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

    private boolean isVitalSignAverage(Observation observation) {
        if (!observation.hasCategory()) return false;

        for (CodeableConcept category : observation.getCategory()) {
            for (Coding coding : category.getCoding()) {
                if (AVG_CATEGORY_SYSTEM.equals(coding.getSystem()) && 
                    AVG_CATEGORY_CODE.equals(coding.getCode())) {
                    return true;
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
        List<BigDecimal> values = new ArrayList<>();
        for (Observation obs : blockAverages) {
            BigDecimal value = extractValue(obs);
            if (value != null) {
                values.add(value);
            }
        }

        if (values.isEmpty()) return null;

        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);

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
            return BigDecimal.valueOf(obs.getValueIntegerType().getValue());
        }
        return null;
    }
}
