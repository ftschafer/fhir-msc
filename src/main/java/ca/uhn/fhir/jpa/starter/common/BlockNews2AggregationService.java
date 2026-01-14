package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BlockNews2AggregationService {

    private static final String NEWS2_EXTENSION_URL = "http://news2-score";
    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String NEIGH_URL = "neighborhood";
    private static final String NEIGH_VALUE = "center";
    private static final String BLOCK_URL = "block"; // define region URL

    @PersistenceContext
    private EntityManager em;

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;
    private final UpstreamForwarder upstreamForwarder;
    private final ThreadPoolTaskExecutor aggExecutor;

    public BlockNews2AggregationService(DaoRegistry daoRegistry, FhirContext fhirContext, UpstreamForwarder upstreamForwarder, ThreadPoolTaskExecutor aggExecutor) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
        this.upstreamForwarder = upstreamForwarder;
        this.aggExecutor = aggExecutor;
    }

    private IFhirResourceDao<Patient> patientDao() { return daoRegistry.getResourceDao(Patient.class); }

    @Transactional
    public void updateBlockForPatient(String patientId) {
        Patient patient;
        try {
            patient = patientDao().read(fhirContext.getVersion().newIdType("Patient", patientId), null);
        } catch (Exception e) {
            return;
        }

        Date patientLastUpdated = Optional.ofNullable(patient.getMeta())
                .map(Meta::getLastUpdated)
                .orElse(null);

        PatientBlock pb = em.find(PatientBlock.class, patientId);

        // Always ensure neighborhood exists; update patient only if missing
        boolean patientMutated = ensureNeighborhoodIfMissing(patient);
        if (patientMutated) {
            try {
                patient = patientDao().read(patient.getIdElement().withVersion(null), null);
                patientLastUpdated = Optional.ofNullable(patient.getMeta())
                        .map(Meta::getLastUpdated)
                        .orElse(patientLastUpdated);
            } catch (Exception ignored) { }
        }

        // Process only if newer meta.lastUpdated than what we handled before
        if (pb != null && pb.getLastUpdated() != null && patientLastUpdated != null
                && !patientLastUpdated.after(pb.getLastUpdated())) {
            return;
        }

        // Extract neighborhood and region from location extension
        String neighborhood = null;
        String region = null;
        Extension locExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locExt != null) {
            for (Extension nested : locExt.getExtension()) {
                if (NEIGH_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                    neighborhood = nested.getValue().primitiveValue();
                } else if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                    region = nested.getValue().primitiveValue();
                }
            }
        }
        neighborhood = normalize(neighborhood);
        region = normalize(region);

        // NEWS2 score
        Extension news2Ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
        int newScore = (news2Ext != null && news2Ext.getValue() instanceof IntegerType)
                ? ((IntegerType) news2Ext.getValue()).getValue()
                : 0;

        // We don’t use Patient block; keep UNKNOWN
        String block = "UNKNOWN";
        String oldBlock = pb == null ? null : pb.getBlock();
        Integer oldScore = pb == null ? null : pb.getLastScore();
        boolean moved = pb != null && !Objects.equals(oldBlock, block);

        if (pb == null) {
            pb = new PatientBlock(patientId, block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.persist(pb);
            adjustBlock(region, block, neighborhood, newScore, 1);
        } else if (moved) {
            if (oldScore != null) adjustBlock(region, oldBlock, null, -oldScore, -1);
            pb.update(block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.merge(pb);
            adjustBlock(region, block, neighborhood, newScore, 1);
        } else {
            int delta = (oldScore == null ? newScore : newScore - oldScore);
            pb.update(block, newScore);
            pb.setLastUpdated(patientLastUpdated);
            em.merge(pb);
            adjustBlock(region, block, neighborhood, delta, 0);
        }
        em.flush();
    }

    // Ensure neighborhood exists; returns true if patient was updated
    private boolean ensureNeighborhoodIfMissing(Patient patient) {
        Extension locExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locExt == null) {
            locExt = new Extension(LOCATION_EXTENSION_URL);
            patient.addExtension(locExt);
        }

        boolean hasNeighborhood = locExt.getExtension().stream()
            .anyMatch(e -> NEIGH_URL.equals(e.getUrl()) && e.getValue() != null);

        if (!hasNeighborhood) {
            locExt.addExtension(new Extension()
                .setUrl(NEIGH_URL)
                .setValue(new StringType(NEIGH_VALUE)));
            patientDao().update(patient);
            
            // Forward updated patient upstream AFTER DB commit, asynchronously.
            Patient copy = (Patient) patient.copy();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        forwardPatientAsync(copy);
                    }
                });
            } else {
                forwardPatientAsync(copy);
            }
            return true;
        }
        return false;
    }

    private void forwardPatientAsync(Patient patient) {
        try {
            if (upstreamForwarder != null) {
                upstreamForwarder.upsertPatients(java.util.List.of(patient));
            }
        } catch (Exception e) {
            // best-effort: swallow
        }
    }

    // Use region from Patient location extension in aggregation key
    private void adjustBlock(String region, String block, String neighborhood, int scoreDelta, int patientDelta) {
        String city = normalize(neighborhood) == null ? "UNKNOWN" : normalize(neighborhood);
        String reg = normalize(region) == null ? "UNKNOWN" : normalize(region);

        BlockKey key = new BlockKey(reg, city, block);
        BlockNews2Aggregate agg = em.find(BlockNews2Aggregate.class, key);
        if (agg == null) {
            agg = new BlockNews2Aggregate(reg, city, block);
            agg.applyDelta(scoreDelta, patientDelta);
            em.persist(agg);
        } else {
            agg.applyDelta(scoreDelta, patientDelta);
            em.merge(agg);
        }
    }

    private String normalize(String s) {
        if (s == null) return null;
        s = s.replace('\u00A0', ' ')
             .replace('\u2007', ' ')
             .replace('\u202F', ' ')
             .trim();
        return s.isEmpty() ? null : s;
    }
}
