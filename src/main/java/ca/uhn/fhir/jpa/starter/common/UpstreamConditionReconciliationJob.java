package ca.uhn.fhir.jpa.starter.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

/**
 * Ensures eventual consistency for condition forwarding when hook-based forwarding is missed.
 */
@Component
public class UpstreamConditionReconciliationJob {

    private static final Logger ourLog = LoggerFactory.getLogger(UpstreamConditionReconciliationJob.class);
    private static final String OBSERVATION_PREFIX = "Observation/";

    private final DaoRegistry daoRegistry;
    private final UpstreamForwarder upstreamForwarder;

    @Value("${upstream.reconcile.conditions.enabled:true}")
    private boolean enabled;

    @Value("${upstream.reconcile.conditions.chunk-size:200}")
    private int reconcileChunkSize;

    public UpstreamConditionReconciliationJob(DaoRegistry daoRegistry, UpstreamForwarder upstreamForwarder) {
        this.daoRegistry = daoRegistry;
        this.upstreamForwarder = upstreamForwarder;
    }

    @Scheduled(initialDelayString = "${upstream.reconcile.conditions.initial-delay-ms:15000}", fixedDelayString = "${upstream.reconcile.conditions.fixed-delay-ms:15000}")
    public void reconcileConditions() {
        if (!enabled) {
            return;
        }

        try {
            IFhirResourceDao<Condition> dao = daoRegistry.getResourceDao(Condition.class);
            SearchParameterMap search = new SearchParameterMap();
            search.setLoadSynchronous(true);

            IBundleProvider results = dao.search(search);
            if (results == null || results.size() == null || results.size() <= 0) {
                return;
            }

            int total = results.size().intValue();
            int chunkSize = Math.max(50, reconcileChunkSize);
            int totalConditionsForwarded = 0;
            int totalObservationsForwarded = 0;

            for (int start = 0; start < total; start += chunkSize) {
                int end = Math.min(total, start + chunkSize);
                List<IBaseResource> page = results.getResources(start, end);
                List<Condition> conditions = new ArrayList<>();
                for (IBaseResource resource : page) {
                    if (resource instanceof Condition condition) {
                        conditions.add(condition);
                    }
                }

                if (conditions.isEmpty()) {
                    continue;
                }

                Map<String, Observation> linkedObservations = new LinkedHashMap<>();
                for (Condition condition : conditions) {
                    Set<String> ids = extractLinkedObservationIds(condition);
                    linkedObservations.putAll(readObservationsByIds(ids));
                }

                upstreamForwarder.upsertConditionsWithObservations(
                        conditions,
                        linkedObservations.isEmpty() ? null : new ArrayList<>(linkedObservations.values()));

                totalConditionsForwarded += conditions.size();
                totalObservationsForwarded += linkedObservations.size();
            }

            if (totalConditionsForwarded > 0) {
                ourLog.info("Condition reconciliation forwarded {} condition(s) and {} linked observation(s) upstream in chunked mode",
                        totalConditionsForwarded,
                        totalObservationsForwarded);
            }

        } catch (RuntimeException e) {
            ourLog.warn("Condition reconciliation skipped due to error: {}", e.getMessage());
        }
    }

    private Map<String, Observation> readObservationsByIds(Set<String> ids) {
        Map<String, Observation> byId = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return byId;
        }

        IFhirResourceDao<Observation> dao = daoRegistry.getResourceDao(Observation.class);
        for (String obsId : ids) {
            if (obsId == null || obsId.isBlank()) {
                continue;
            }
            try {
                Observation observation = dao.read(dao.getContext().getVersion().newIdType("Observation", obsId), null);
                if (observation == null) {
                    continue;
                }
                String id = normalizeId(observation.getIdElement() != null ? observation.getIdElement().getIdPart() : null);
                if (id != null) {
                    byId.put(id, observation);
                }
            } catch (RuntimeException ignored) {
                // best effort
            }
        }

        return byId;
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
        if (ref == null || !ref.hasReference()) {
            return;
        }

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

    private String normalizeId(String idPart) {
        if (idPart == null) {
            return null;
        }
        String id = idPart.trim();
        if (id.isEmpty()) {
            return null;
        }
        int historyMarker = id.indexOf("/_history/");
        if (historyMarker > 0) {
            id = id.substring(0, historyMarker);
        }
        return id.isBlank() ? null : id;
    }
}
