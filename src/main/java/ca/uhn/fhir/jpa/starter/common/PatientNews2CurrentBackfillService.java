package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class PatientNews2CurrentBackfillService {

    private static final Logger logger = LoggerFactory.getLogger(PatientNews2CurrentBackfillService.class);
    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";

    @PersistenceContext
    private EntityManager em;

    private final DaoRegistry daoRegistry;

    @Value("${hapi.fhir.location.block:North}")
    private String defaultBlock;

    public PatientNews2CurrentBackfillService(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillAtStartup() {
        long startedAt = System.currentTimeMillis();

        try {
            IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
            SearchParameterMap searchMap = new SearchParameterMap();
            searchMap.setLoadSynchronous(true);

            List<IBaseResource> resources = patientDao.search(searchMap).getAllResources();
            int upserted = 0;

            for (IBaseResource resource : resources) {
                if (!(resource instanceof Patient patient)) {
                    continue;
                }

                Integer news2 = extractNews2(patient);
                if (news2 == null) {
                    continue;
                }

                String patientId = patient.getIdElement().getIdPart();
                if (patientId == null || patientId.isBlank()) {
                    continue;
                }

                String block = extractBlock(patient);
                if (block == null || block.isBlank()) {
                    block = defaultBlock;
                }

                em.createNativeQuery(
                                "MERGE INTO patient_news2_current (patient_id, block_id, news2, updated_at) " +
                                        "KEY(patient_id) VALUES (?, ?, ?, ?)")
                        .setParameter(1, patientId)
                        .setParameter(2, block)
                        .setParameter(3, news2)
                        .setParameter(4, Timestamp.from(Instant.now()))
                        .executeUpdate();

                upserted++;
            }

            long duration = System.currentTimeMillis() - startedAt;
            logger.info("Backfilled patient_news2_current: {} rows in {} ms", upserted, duration);

        } catch (Exception e) {
            logger.warn("Skipping patient_news2_current backfill on startup: {}", e.getMessage());
        }
    }

    private Integer extractNews2(Patient patient) {
        Extension news2Ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        if (news2Ext != null && news2Ext.getValue() instanceof IntegerType integerType) {
            return integerType.getValue();
        }
        return null;
    }

    private String extractBlock(Patient patient) {
        Extension locationExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locationExt == null) {
            return null;
        }

        for (Extension nested : locationExt.getExtension()) {
            if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() instanceof StringType stringType) {
                return stringType.getValue();
            }
        }

        return null;
    }
}
