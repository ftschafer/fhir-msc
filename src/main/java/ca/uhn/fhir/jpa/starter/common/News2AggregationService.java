package ca.uhn.fhir.jpa.starter.common;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class News2AggregationService {
    private static final Logger logger = LoggerFactory.getLogger(News2AggregationService.class);
    private static final String NEWS2_EXTENSION_URL = "http://example.org/fhir/StructureDefinition/news2-score";
    private static final Set<String> LOINC_CODES = Set.of("8867-4","9279-1","8310-5","59408-5","8480-6");

    @PersistenceContext
    private EntityManager em;

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;

    public News2AggregationService(DaoRegistry daoRegistry, FhirContext fhirContext) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
    }

    private IFhirResourceDao<Patient> patientDao() { return daoRegistry.getResourceDao(Patient.class); }

    // Call this once per received bundle. observations param = observations from the bundle (new ones).
    @Transactional
    public void processBundleObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;

        // 1) keep only newest observation per patient+code from this bundle
        Map<String, Map<String, Observation>> newest = new HashMap<>();
        for (Observation o : observations) {
            if (o.getSubject() == null || o.getSubject().getReference() == null) continue;
            String ref = o.getSubject().getReference();
            if (!ref.startsWith("Patient/")) continue;
            String pid = ref.substring("Patient/".length());

            if (o.getCode() == null || o.getCode().getCoding().isEmpty()) continue;
            String code = o.getCode().getCodingFirstRep().getCode();
            if (!LOINC_CODES.contains(code)) continue;

            Instant obsInstant = extractObservationInstant(o);
            newest.computeIfAbsent(pid, p->new HashMap<>());
            Map<String, Observation> byCode = newest.get(pid);
            Observation cur = byCode.get(code);
            if (cur == null || obsInstant.isAfter(extractObservationInstant(cur))) {
                byCode.put(code, o);
            }
        }

        if (newest.isEmpty()) return;

        // 2) upsert aggregation rows only for items in newest map (cheap)
        Set<String> affectedPatients = new HashSet<>();
        for (var e : newest.entrySet()) {
            String pid = e.getKey();
            for (var ce : e.getValue().entrySet()) {
                String code = ce.getKey();
                Observation o = ce.getValue();
                int score = extractNews2Score(o);
                Instant obsAt = extractObservationInstant(o);
                upsertAggregate(pid, code, score, obsAt);
                affectedPatients.add(pid);
            }
        }

        // 3) compute totals for affected patients in single grouped query
        List<Object[]> rows = em.createQuery(
                "SELECT a.id.patientId, SUM(a.score) FROM News2Aggregate a WHERE a.id.patientId IN :p GROUP BY a.id.patientId", Object[].class)
            .setParameter("p", affectedPatients)
            .getResultList();

        Map<String, Integer> totals = new HashMap<>();
        for (Object[] r : rows) {
            String pid = (String) r[0];
            Number n = (Number) r[1];
            totals.put(pid, n == null ? 0 : n.intValue());
        }

        // 4) update Patient extension only if changed
        for (String pid : affectedPatients) {
            int total = totals.getOrDefault(pid, 0);
            try {
                Patient patient = patientDao().read(fhirContext.getVersion().newIdType("Patient", pid), null);
                Extension ext = patient.getExtensionByUrl(NEWS2_EXTENSION_URL);
                Integer old = ext != null && ext.getValue() instanceof IntegerType ? ((IntegerType)ext.getValue()).getValue() : null;
                if (!Objects.equals(old, total)) {
                    if (ext == null) {
                        patient.addExtension(new Extension(NEWS2_EXTENSION_URL, new IntegerType(total)));
                    } else {
                        ext.setValue(new IntegerType(total));
                    }
                    patientDao().update(patient);
                }
            } catch (Exception ex) {
                logger.warn("Failed updating patient {} NEWS2", pid, ex);
            }
        }
    }

    private Instant extractObservationInstant(Observation o) {
        if (o.getEffective() instanceof DateTimeType) {
            Date d = ((DateTimeType) o.getEffective()).getValue();
            return d == null ? Instant.EPOCH : d.toInstant();
        }
        if (o.getIssued() != null) return o.getIssued().toInstant();
        return Instant.EPOCH;
    }

    private int extractNews2Score(Observation o) {
        // similar to previous: extension first, then integer, then quantity
        if (o.hasExtension()) {
            for (Extension ext : o.getExtension()) {
                if (NEWS2_EXTENSION_URL.equals(ext.getUrl()) && ext.getValue() instanceof IntegerType) {
                    return ((IntegerType) ext.getValue()).getValue();
                }
            }
        }
        if (o.hasValueIntegerType()) return o.getValueIntegerType().getValue();
        if (o.hasValueQuantity() && o.getValueQuantity().getValue() != null) {
            return o.getValueQuantity().getValue().intValue();
        }
        return 0;
    }

    private void upsertAggregate(String patientId, String code, int value, Instant obsAt) {
        // Find existing row
        News2Aggregate.Id id = new News2Aggregate.Id(patientId, code);
        News2Aggregate a = em.find(News2Aggregate.class, id);
        if (a == null) {
            a = new News2Aggregate(patientId, code, value, obsAt);
            em.persist(a);
            return;
        }
        // Only replace if incoming observation is newer
        Instant existing = a.getObservedAt();
        if (existing == null || obsAt.isAfter(existing)) {
            a.setValue(value);
            a.setObservedAt(obsAt);
            em.merge(a);
        }
    }
}
