package ca.uhn.fhir.jpa.starter.common;

import java.math.BigDecimal;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;

@Component
public class News2Interceptor {
    private final Logger ourLog = LoggerFactory.getLogger(News2Interceptor.class);

    @Value("${hapi.fhir.location.block:North}")
    private String blockValue;

    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    
    // This hook runs for every Observation created in storage (not just HTTP)
    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
    public void addNews2ScoreOnCreate(IBaseResource resource, RequestDetails requestDetails, TransactionDetails transactionDetails) {
        if (resource instanceof Observation) {
            fixObservationExtensions((Observation) resource);
        }
    }
    
    // This hook runs for every Observation updated in storage
    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
    public void addNews2ScoreOnUpdate(IBaseResource oldResource, IBaseResource newResource, RequestDetails requestDetails, TransactionDetails transactionDetails) {
        if (newResource instanceof Observation) {
            fixObservationExtensions((Observation) newResource);
        }
    }
    
    private void fixObservationExtensions(Observation obs) {
        int score = computeNews2ForSingleObservation(obs);

        // Update or add NEWS2 extension
        Extension existing = obs.getExtensionByUrl(NEWS2_EXTENSION_URL);
        if (existing != null) {
            existing.setValue(new IntegerType(score));
        } else {
            obs.addExtension(new Extension(NEWS2_EXTENSION_URL, new IntegerType(score)));
        }
        
        // Add hardcoded location extension (always ensure it has the correct block value)
        obs.getExtension().removeIf(ext -> LOCATION_EXTENSION_URL.equals(ext.getUrl()));
        Extension locationExt = new Extension(LOCATION_EXTENSION_URL);
        Extension blockExt = new Extension();
        blockExt.setUrl("block");
        blockExt.setValue(new StringType(blockValue));
        locationExt.addExtension(blockExt);
        obs.addExtension(locationExt);
        
        // Ensure vital-signs category is set (required for CDSS to pick up observations)
        if (!hasVitalSignsCategory(obs)) {
            CodeableConcept category = new CodeableConcept();
            category.addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("vital-signs")
                .setDisplay("Vital Signs");
            obs.addCategory(category);
            ourLog.debug("Added vital-signs category to observation {}", obs.getId());
        }
    }
    
    private boolean hasVitalSignsCategory(Observation obs) {
        return obs.getCategory().stream()
            .anyMatch(cat -> cat.getCoding().stream()
                .anyMatch(coding -> 
                    "http://terminology.hl7.org/CodeSystem/observation-category".equals(coding.getSystem()) &&
                    "vital-signs".equals(coding.getCode())
                )
            );
    }

    private int computeNews2ForSingleObservation(Observation obs) {
        String code = obs.getCode().getCodingFirstRep().getCode();
        switch (code) {
            case "9279-1": return computeRespiratoryRate(obs);
            case "59408-5": return computeOxygenSaturation(obs);
            case "8867-4": return computeHeartRate(obs);
            case "8480-6": return computeSysBloodPressure(obs);
            case "8310-5": return computeTemp(obs);
            default: return 0;
        }
    }

    private int computeRespiratoryRate(Observation obs) {
        BigDecimal heartRate = obs.getValueQuantity().getValue();
        if (heartRate == null) return 0;

        if (heartRate.compareTo(BigDecimal.valueOf(8)) <= 0) {
            return 3;
        } else if (heartRate.compareTo(BigDecimal.valueOf(9)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(11)) <= 0) {
            return 1;
        } else if (heartRate.compareTo(BigDecimal.valueOf(12)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(20)) <= 0) {
            return 0;
        } else if (heartRate.compareTo(BigDecimal.valueOf(21)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(24)) <= 0) {
            return 2;
        } else if (heartRate.compareTo(BigDecimal.valueOf(25)) >= 0) {
            return 3;
        } else {
            return 0;
        }
    }

    private int computeHeartRate(Observation obs) {
        BigDecimal heartRate = obs.getValueQuantity().getValue();
        if (heartRate == null) return 0;

        if (heartRate.compareTo(BigDecimal.valueOf(40)) <= 0) {
            return 3;
        } else if (heartRate.compareTo(BigDecimal.valueOf(41)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(50)) <= 0) {
            return 1;
        } else if (heartRate.compareTo(BigDecimal.valueOf(51)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(90)) <= 0) {
            return 0;
        } else if (heartRate.compareTo(BigDecimal.valueOf(91)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(110)) <= 0) {
            return 1;
        } else if (heartRate.compareTo(BigDecimal.valueOf(111)) >= 0 && heartRate.compareTo(BigDecimal.valueOf(130)) <= 0) {
            return 2;
        } else if (heartRate.compareTo(BigDecimal.valueOf(131)) >= 0) {
            return 3;
        } else {
            return 0;
        }
    }

    private int computeTemp(Observation obs) {
        BigDecimal temp = obs.getValueQuantity().getValue();
        if (temp == null) return 0;

        if (temp.compareTo(BigDecimal.valueOf(35)) <= 0) {
            return 3;
        } else if (temp.compareTo(BigDecimal.valueOf(35.1)) >= 0 && temp.compareTo(BigDecimal.valueOf(36)) <= 0) {
            return 1;
        } else if (temp.compareTo(BigDecimal.valueOf(36.1)) > 0 && temp.compareTo(BigDecimal.valueOf(38)) <= 0) {
            return 0;
        } else if (temp.compareTo(BigDecimal.valueOf(38.1)) > 0 && temp.compareTo(BigDecimal.valueOf(39)) <= 0) {
            return 1;
        } else if (temp.compareTo(BigDecimal.valueOf(39.1)) > 0) {
            return 2;
        } else {
            return 0;
        }
    }

    private int computeOxygenSaturation(Observation obs) {
        BigDecimal spo2 = obs.getValueQuantity().getValue();
        if (spo2 == null) return 0;

        if (spo2.compareTo(BigDecimal.valueOf(91)) <= 0) {
            return 3;
        } else if (spo2.compareTo(BigDecimal.valueOf(92)) >= 0 && spo2.compareTo(BigDecimal.valueOf(93)) <= 0) {
            return 2;
        } else if (spo2.compareTo(BigDecimal.valueOf(94)) >= 0 && spo2.compareTo(BigDecimal.valueOf(95)) <= 0) {
            return 1;
        } else if (spo2.compareTo(BigDecimal.valueOf(96)) >= 0) {
            return 0;
        } else {
            return 0;
        }
    }

    private int computeSysBloodPressure(Observation obs) {
        BigDecimal sysBP = obs.getValueQuantity().getValue();
        if (sysBP == null) return 0;

        if (sysBP.compareTo(BigDecimal.valueOf(90)) <= 0) {
            return 3;
        } else if (sysBP.compareTo(BigDecimal.valueOf(91)) >= 0 && sysBP.compareTo(BigDecimal.valueOf(100)) <= 0) {
            return 2;
        } else if (sysBP.compareTo(BigDecimal.valueOf(101)) >= 0 && sysBP.compareTo(BigDecimal.valueOf(110)) <= 0) {
            return 1;
        } else if (sysBP.compareTo(BigDecimal.valueOf(111)) >= 0 && sysBP.compareTo(BigDecimal.valueOf(219)) <= 0) {
            return 0;
        } else if (sysBP.compareTo(BigDecimal.valueOf(220)) >= 0) {
            return 3;
        } else {
            return 0;
        }
    }

}
