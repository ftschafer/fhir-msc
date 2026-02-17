package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;

@Component
public class ConditionUpstreamForwardingInterceptor {
    private static final Logger ourLog = LoggerFactory.getLogger(ConditionUpstreamForwardingInterceptor.class);

    private static final String INTERNAL_REQUEST_HEADER = "X-Upstream-Internal-Request";
    private static final String OBSERVATION_PREFIX = "Observation/";
    private static final String PATIENT_PREFIX = "Patient/";
    private static final String AVG_CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";
    private static final String AVG_CATEGORY_CODE = "vital-signs-average";

    private final DaoRegistry daoRegistry;
    private final UpstreamForwarder upstreamForwarder;

    public ConditionUpstreamForwardingInterceptor(DaoRegistry daoRegistry, UpstreamForwarder upstreamForwarder) {
        this.daoRegistry = daoRegistry;
        this.upstreamForwarder = upstreamForwarder;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void created(IBaseResource resource, RequestDetails requestDetails) {
        if (requestDetails == null || isInternalRequest(requestDetails)) return;
        if (resource instanceof Condition condition) {
            enqueueAfterCommit(condition);
        }
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void updated(IBaseResource oldResource, IBaseResource newResource, RequestDetails requestDetails) {
        if (requestDetails == null || isInternalRequest(requestDetails)) return;
        if (newResource instanceof Condition condition) {
            enqueueAfterCommit(condition);
        }
    }

    private void enqueueAfterCommit(Condition condition) {
        Condition copy = (Condition) condition.copy();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forwardConditionWithLinkedData(copy);
                }
            });
        } else {
            forwardConditionWithLinkedData(copy);
        }
    }

    private void forwardConditionWithLinkedData(Condition condition) {
        if (upstreamForwarder == null || condition == null) {
            return;
        }

        Set<String> linkedObservationIds = extractLinkedObservationIds(condition);
        ourLog.info("Condition/{} linked observation refs extracted: {}",
                normalizeId(condition.getIdElement() != null ? condition.getIdElement().getIdPart() : null),
                linkedObservationIds);
        Map<String, Observation> observationsById = new LinkedHashMap<>();

        // Forward only observations linked from this condition.
        observationsById.putAll(readObservationsByIds(linkedObservationIds));

        // Keep condition + linked observations consistent by forwarding in one coordinated call.
        ourLog.debug("Trigger upstream forwarding for Condition/{} with {} linked observations",
            normalizeId(condition.getIdElement() != null ? condition.getIdElement().getIdPart() : null),
            observationsById.size());
        upstreamForwarder.upsertConditionsWithObservations(
                List.of(condition),
                observationsById.isEmpty() ? null : new ArrayList<>(observationsById.values()));
    }

    private IFhirResourceDao<Observation> observationDao() {
        return daoRegistry.getResourceDao(Observation.class);
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    private Patient readPatientById(String patientId) {
        if (patientId == null || patientId.isBlank()) {
            return null;
        }

        IFhirResourceDao<Patient> dao = patientDao();
        try {
            return dao.read(dao.getContext().getVersion().newIdType("Patient", patientId), null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Observation> readObservationsByIds(Collection<String> ids) {
        Map<String, Observation> byId = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return byId;
        }

        IFhirResourceDao<Observation> dao = observationDao();
        for (String obsId : ids) {
            if (obsId == null || obsId.isBlank()) continue;
            try {
                Observation o = dao.read(dao.getContext().getVersion().newIdType("Observation", obsId), null);
                if (o == null) continue;
                String normalized = normalizeId(o.getIdElement() != null ? o.getIdElement().getIdPart() : null);
                if (normalized != null) {
                    byId.put(normalized, o);
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
        return byId;
    }

    private Map<String, Observation> loadAverageObservationsForPatient(String patientId) {
        Map<String, Observation> byId = new LinkedHashMap<>();
        if (patientId == null || patientId.isBlank()) {
            return byId;
        }

        IFhirResourceDao<Observation> dao = observationDao();
        SearchParameterMap search = new SearchParameterMap();
        search.add("subject", new ReferenceParam(PATIENT_PREFIX + patientId));
        search.add("category", new TokenParam(AVG_CATEGORY_SYSTEM, AVG_CATEGORY_CODE));
        search.setLoadSynchronous(true);

        try {
            IBundleProvider results = dao.search(search);
            for (IBaseResource resource : getAllResources(results)) {
                if (resource instanceof Observation observation) {
                    String id = normalizeId(observation.getIdElement() != null ? observation.getIdElement().getIdPart() : null);
                    if (id != null) {
                        byId.put(id, observation);
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort
        }

        return byId;
    }

    private List<IBaseResource> getAllResources(IBundleProvider provider) {
        if (provider == null) {
            return List.of();
        }
        Integer sizeObj = provider.size();
        int size = sizeObj != null ? sizeObj : Integer.MAX_VALUE;
        if (size <= 0) {
            return List.of();
        }
        return provider.getResources(0, size);
    }

    private Set<String> extractLinkedObservationIds(Condition condition) {
        Set<String> ids = new LinkedHashSet<>();

        if (condition.hasEvidence()) {
            condition.getEvidence().forEach(ev -> {
                if (ev != null && ev.hasDetail()) {
                    ev.getDetail().forEach(ref -> addObservationId(ids, ref));
                }
            });
        }

        if (condition.hasStage()) {
            condition.getStage().forEach(stage -> {
                if (stage != null && stage.hasAssessment()) {
                    stage.getAssessment().forEach(ref -> addObservationId(ids, ref));
                }
            });
        }

        return ids;
    }

    private void addObservationId(Set<String> ids, Reference ref) {
        if (ref == null || !ref.hasReference()) return;
        String id = extractObservationId(ref.getReference());
        if (id != null) {
            ids.add(id);
        }
    }

    private String extractObservationId(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }

        String ref = reference.trim();
        int historyMarker = ref.indexOf("/_history/");
        if (historyMarker > 0) {
            ref = ref.substring(0, historyMarker);
        }

        // Accepted reference formats:
        // - Observation/{id}
        // - http(s)://.../Observation/{id}
        // - {base}/fhir/Observation/{id}
        if (ref.startsWith(OBSERVATION_PREFIX)) {
            String id = ref.substring(OBSERVATION_PREFIX.length());
            int slashIdx = id.indexOf('/');
            if (slashIdx >= 0) {
                id = id.substring(0, slashIdx);
            }
            return normalizeId(id);
        }

        int pathObservationIdx = ref.indexOf("/" + OBSERVATION_PREFIX);
        if (pathObservationIdx >= 0) {
            String id = ref.substring(pathObservationIdx + ("/" + OBSERVATION_PREFIX).length());
            int slashIdx = id.indexOf('/');
            if (slashIdx >= 0) {
                id = id.substring(0, slashIdx);
            }
            return normalizeId(id);
        }

        return null;
    }

    private String extractPatientId(Condition condition) {
        if (condition == null || !condition.hasSubject() || !condition.getSubject().hasReference()) {
            return null;
        }

        String ref = condition.getSubject().getReference();
        if (ref == null || ref.isBlank()) {
            return null;
        }

        int historyMarker = ref.indexOf("/_history/");
        if (historyMarker > 0) {
            ref = ref.substring(0, historyMarker);
        }

        int patientIdx = ref.indexOf(PATIENT_PREFIX);
        if (patientIdx >= 0) {
            String id = ref.substring(patientIdx + PATIENT_PREFIX.length());
            int slashIdx = id.indexOf('/');
            if (slashIdx >= 0) {
                id = id.substring(0, slashIdx);
            }
            return normalizeId(id);
        }

        return null;
    }

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }

    private String normalizeId(String idPart) {
        if (idPart == null) return null;
        String id = idPart.trim();
        if (id.isEmpty()) return null;
        int historyMarker = id.indexOf("/_history/");
        if (historyMarker > 0) {
            id = id.substring(0, historyMarker);
        }
        return id.isBlank() ? null : id;
    }
}
