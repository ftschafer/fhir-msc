package ca.uhn.fhir.jpa.starter.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;

@Component
public class UpstreamForwarder {
    private static final Logger ourLog = LoggerFactory.getLogger(UpstreamForwarder.class);
    private static final int PATIENT_RETRY_ATTEMPTS = 6;
    private static final long UPSTREAM_FAILURE_COOLDOWN_MS = 60_000L;

    private final AtomicLong upstreamSuppressUntilMs = new AtomicLong(0L);

    @Value("${hapi.fhir.location.block:North}")
    private String blockValue;

    @Value("${upstream.tx.chunk.observations:300}")
    private int observationTxChunkSize;

    @Value("${upstream.tx.chunk.measure-reports:150}")
    private int measureReportTxChunkSize;

    @Value("${upstream.tx.chunk.conditions:150}")
    private int conditionTxChunkSize;

    @Value("${upstream.tx.chunk.patients:200}")
    private int patientTxChunkSize;
    
    private final IGenericClient client;

    public UpstreamForwarder(
            FhirContext ctx,
            @Value("${upstream.fhir.base-url:http://18.218.25.8:8081/fhir}") String upstreamUrl,
            @Value("${upstream.client.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${upstream.client.socket-timeout-ms:10000}") int socketTimeoutMs,
            @Value("${upstream.client.connection-request-timeout-ms:2000}") int connectionRequestTimeoutMs,
            @Value("${upstream.client.pool-max-total:200}") int poolMaxTotal,
            @Value("${upstream.client.pool-max-per-route:100}") int poolMaxPerRoute
    ) {
        var clientFactory = ctx.getRestfulClientFactory();
        clientFactory.setServerValidationMode(ServerValidationModeEnum.NEVER);
        applyClientTuning(clientFactory, connectTimeoutMs, socketTimeoutMs, connectionRequestTimeoutMs, poolMaxTotal, poolMaxPerRoute);
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
    }

    private void applyClientTuning(Object clientFactory,
                                   int connectTimeoutMs,
                                   int socketTimeoutMs,
                                   int connectionRequestTimeoutMs,
                                   int poolMaxTotal,
                                   int poolMaxPerRoute) {
        invokeIntSetter(clientFactory, "setConnectTimeout", connectTimeoutMs);
        invokeIntSetter(clientFactory, "setSocketTimeout", socketTimeoutMs);
        invokeIntSetter(clientFactory, "setConnectionRequestTimeout", connectionRequestTimeoutMs);
        invokeIntSetter(clientFactory, "setPoolMaxTotal", poolMaxTotal);
        invokeIntSetter(clientFactory, "setPoolMaxPerRoute", poolMaxPerRoute);
    }

    private void invokeIntSetter(Object target, String methodName, int value) {
        try {
            Method m = target.getClass().getMethod(methodName, int.class);
            m.invoke(target, value);
        } catch (Exception ignored) {
            // Not all client factory implementations expose all tuning setters.
        }
    }

    public boolean createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return true;
        if (isUpstreamSuppressed("observations")) return false;
        try {
            int chunkSize = Math.max(50, observationTxChunkSize);
            for (int start = 0; start < observations.size(); start += chunkSize) {
                int end = Math.min(observations.size(), start + chunkSize);
                Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                for (Observation o : observations.subList(start, end)) {
                    Observation obsCopy = o.copy();
                    obsCopy.setId((String) null);

                    // Remove any existing location extensions and add the block extension
                    obsCopy.getExtension().removeIf(ext -> "http://patient-location".equals(ext.getUrl()));
                    Extension locationExtension = new Extension();
                    locationExtension.setUrl("http://patient-location");
                    Extension blockExtension = new Extension();
                    blockExtension.setUrl("block");
                    blockExtension.setValue(new org.hl7.fhir.r4.model.StringType(blockValue));
                    locationExtension.addExtension(blockExtension);
                    obsCopy.addExtension(locationExtension);

                    Bundle.BundleEntryComponent e = tx.addEntry().setResource(obsCopy);

                    // Prefer stable identifier when available to avoid duplicate aggregates
                    String conditionalUrl;
                    if (o.hasIdentifier() && o.getIdentifierFirstRep().hasSystem() && o.getIdentifierFirstRep().hasValue()) {
                        conditionalUrl = "Observation?identifier=" + o.getIdentifierFirstRep().getSystem() + "|" + o.getIdentifierFirstRep().getValue();
                    } else {
                        // Build conditional criteria to update existing observations instead of creating duplicates
                        StringBuilder criteria = new StringBuilder();
                        if (o.hasSubject() && o.getSubject().hasReference()) {
                            criteria.append("subject=").append(o.getSubject().getReference());
                        }
                        if (o.hasCode() && o.getCode().hasCoding() && o.getCode().getCodingFirstRep().hasCode()) {
                            if (criteria.length() > 0) criteria.append("&");
                            criteria.append("code=").append(o.getCode().getCodingFirstRep().getCode());
                        }
                        if (o.hasEffectiveDateTimeType()) {
                            if (criteria.length() > 0) criteria.append("&");
                            criteria.append("date=").append(o.getEffectiveDateTimeType().getValueAsString());
                        }
                        conditionalUrl = "Observation?" + (criteria.length() > 0 ? criteria.toString() : "identifier=temp");
                    }

                    // Use PUT with conditional URL to update existing observations or create new ones
                    e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                            .setUrl(conditionalUrl);
                }
                client.transaction().withBundle(tx).execute();
            }
            return true;
        } catch (BaseServerResponseException e) {
            maybeSuppressUpstream(e, "observations");
            ourLog.error("ERROR forwarding observations: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            maybeSuppressOnConnectivityFailure(e, "observations");
            ourLog.error("ERROR forwarding observations", e);
            return false;
        }
    }

    /**
     * Sends one Patient and one Observation in a single upstream transaction.
     * If this returns false, caller can safely fallback to the existing separate calls.
     */
    public boolean upsertPatientWithObservation(Patient patient, Observation observation) {
        if (patient == null || observation == null) {
            return false;
        }
        if (isUpstreamSuppressed("patients") || isUpstreamSuppressed("observations")) {
            return false;
        }

        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);

            Bundle.BundleEntryComponent patientEntry = tx.addEntry().setResource(patient);
            applyPatientUpsertRequest(patientEntry, patient);

            Observation obsCopy = observation.copy();
            obsCopy.setId((String) null);
            obsCopy.getExtension().removeIf(ext -> "http://patient-location".equals(ext.getUrl()));
            Extension locationExtension = new Extension();
            locationExtension.setUrl("http://patient-location");
            Extension blockExtension = new Extension();
            blockExtension.setUrl("block");
            blockExtension.setValue(new org.hl7.fhir.r4.model.StringType(blockValue));
            locationExtension.addExtension(blockExtension);
            obsCopy.addExtension(locationExtension);

            Bundle.BundleEntryComponent obsEntry = tx.addEntry().setResource(obsCopy);
            String conditionalUrl;
            if (observation.hasIdentifier()
                    && observation.getIdentifierFirstRep().hasSystem()
                    && observation.getIdentifierFirstRep().hasValue()) {
                conditionalUrl = "Observation?identifier="
                        + observation.getIdentifierFirstRep().getSystem()
                        + "|"
                        + observation.getIdentifierFirstRep().getValue();
            } else {
                StringBuilder criteria = new StringBuilder();
                if (observation.hasSubject() && observation.getSubject().hasReference()) {
                    criteria.append("subject=").append(observation.getSubject().getReference());
                }
                if (observation.hasCode() && observation.getCode().hasCoding() && observation.getCode().getCodingFirstRep().hasCode()) {
                    if (criteria.length() > 0) criteria.append("&");
                    criteria.append("code=").append(observation.getCode().getCodingFirstRep().getCode());
                }
                if (observation.hasEffectiveDateTimeType()) {
                    if (criteria.length() > 0) criteria.append("&");
                    criteria.append("date=").append(observation.getEffectiveDateTimeType().getValueAsString());
                }
                conditionalUrl = "Observation?" + (criteria.length() > 0 ? criteria.toString() : "identifier=temp");
            }
            obsEntry.getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl(conditionalUrl);

            client.transaction().withBundle(tx).execute();
            return true;
        } catch (BaseServerResponseException e) {
            maybeSuppressUpstream(e, "patient+observations");
            ourLog.warn("Combined patient+observation forward failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            maybeSuppressOnConnectivityFailure(e, "patient+observations");
            ourLog.warn("Combined patient+observation forward failed: {}", e.getMessage());
            return false;
        }
    }

    public void upsertMeasureReports(List<MeasureReport> measureReports) {
        if (measureReports == null || measureReports.isEmpty()) return;
        if (isUpstreamSuppressed("measure-reports")) return;
        try {
            int chunkSize = Math.max(25, measureReportTxChunkSize);
            for (int start = 0; start < measureReports.size(); start += chunkSize) {
                int end = Math.min(measureReports.size(), start + chunkSize);
                Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                for (MeasureReport report : measureReports.subList(start, end)) {
                    MeasureReport reportCopy = report.copy();

                    reportCopy.getExtension().removeIf(ext -> "http://patient-location".equals(ext.getUrl()));
                    Extension locationExtension = new Extension();
                    locationExtension.setUrl("http://patient-location");
                    Extension blockExtension = new Extension();
                    blockExtension.setUrl("block");
                    blockExtension.setValue(new org.hl7.fhir.r4.model.StringType(blockValue));
                    locationExtension.addExtension(blockExtension);
                    reportCopy.addExtension(locationExtension);

                    Bundle.BundleEntryComponent entry = tx.addEntry().setResource(reportCopy);
                    String reportId = reportCopy.getIdElement().getIdPart();

                    if (reportId != null && !reportId.isBlank()) {
                        entry.getRequest()
                                .setMethod(Bundle.HTTPVerb.PUT)
                                .setUrl("MeasureReport/" + reportId);
                    } else if (reportCopy.hasIdentifier()
                            && reportCopy.getIdentifierFirstRep().hasSystem()
                            && reportCopy.getIdentifierFirstRep().hasValue()) {
                        entry.getRequest()
                                .setMethod(Bundle.HTTPVerb.PUT)
                                .setUrl("MeasureReport?identifier="
                                        + reportCopy.getIdentifierFirstRep().getSystem()
                                        + "|"
                                        + reportCopy.getIdentifierFirstRep().getValue());
                    } else {
                        entry.getRequest()
                                .setMethod(Bundle.HTTPVerb.POST)
                                .setUrl("MeasureReport");
                    }
                }
                client.transaction().withBundle(tx).execute();
            }
        } catch (BaseServerResponseException e) {
            maybeSuppressUpstream(e, "measure-reports");
            ourLog.error("ERROR forwarding measure reports: {}", e.getMessage());
        } catch (Exception e) {
            maybeSuppressOnConnectivityFailure(e, "measure-reports");
            ourLog.warn("Skipping measure report forward: {}", e.getMessage());
        }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        if (isUpstreamSuppressed("patients")) return;
        int total = patients.size();
        int successCount = 0;

        for (Patient patient : patients) {
            if (upsertPatientWithRetries(patient)) {
                successCount++;
            }
        }

        if (successCount < total) {
            ourLog.warn("Forwarded {}/{} patients upstream (some failed due to concurrent updates).", successCount, total);
        } else {
            ourLog.info("Forwarded {}/{} patients upstream.", successCount, total);
        }
    }

    private boolean upsertPatientWithRetries(Patient patient) {
        for (int attempt = 1; attempt <= PATIENT_RETRY_ATTEMPTS; attempt++) {
            try {
                upsertSinglePatient(patient, false);
                return true;
            } catch (BaseServerResponseException e) {
                if (isVersionConflict(e) && attempt < PATIENT_RETRY_ATTEMPTS) {
                    LockSupport.parkNanos(75L * attempt * 1_000_000L);
                    continue;
                }

                // Final fallback: ID-based upsert avoids conditional collisions
                String patientId = patient.getIdElement().getIdPart();
                if (patientId != null && !patientId.isBlank()) {
                    try {
                        upsertSinglePatient(patient, true);
                        return true;
                    } catch (Exception fallbackException) {
                        ourLog.warn("Failed forwarding patient {} after {} attempt(s): {}",
                                patientId,
                                attempt,
                                fallbackException.getMessage());
                        return false;
                    }
                }

                ourLog.warn("Failed forwarding patient after {} attempt(s): {}", attempt, e.getMessage());
                return false;
            } catch (Exception e) {
                String patientId = patient.getIdElement().getIdPart();
                ourLog.warn("Failed forwarding patient {} after {} attempt(s): {}",
                        patientId,
                        attempt,
                        e.getMessage());
                return false;
            }
        }

        return false;
    }

    private void upsertSinglePatient(Patient patient, boolean forceIdBased) {
        Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
        Bundle.BundleEntryComponent entry = tx.addEntry().setResource(patient);

        if (forceIdBased) {
            String id = patient.getIdElement().getIdPart();
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient/" + id);
        } else {
            applyPatientUpsertRequest(entry, patient);
        }

        client.transaction().withBundle(tx).execute();
    }

    private void applyPatientUpsertRequest(Bundle.BundleEntryComponent entry, Patient p) {
        String id = p.getIdElement().getIdPart();

        if (p.hasIdentifier() && p.getIdentifierFirstRep().hasSystem() && p.getIdentifierFirstRep().hasValue()) {
            String system = p.getIdentifierFirstRep().getSystem();
            String value = p.getIdentifierFirstRep().getValue();
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient?identifier=" + system + "|" + value);
        } else {
            entry.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl(id != null ? "Patient/" + id : "Patient");
        }
    }

    private boolean isVersionConflict(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof ResourceVersionConflictException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Forward Condition resources to upstream server
     * Used for auto-detected clinical conditions from CDSS
     */
    public void upsertConditions(List<Condition> conditions) {
        upsertConditionsWithObservations(conditions, null);
    }

    /**
     * Forward Condition resources along with their referenced Observations to upstream server
     * Ensures all referenced Observations and Patients exist before creating Conditions
     */
    public void upsertConditionsWithObservations(List<Condition> conditions, List<Observation> observations) {
        if (conditions == null || conditions.isEmpty()) return;
        if (isUpstreamSuppressed("conditions")) return;
        try {
            Map<String, String> oldToNewObservationIds = new HashMap<>();
            
            // Step 0: Collect all referenced patients from conditions and observations
            Map<String, Patient> referencedPatients = new HashMap<>();
            
            // Get patients from conditions
            for (Condition c : conditions) {
                if (c.hasSubject() && c.getSubject().hasReference()) {
                    String patientRef = c.getSubject().getReference();
                    if (patientRef.startsWith("Patient/")) {
                        String patientId = patientRef.substring("Patient/".length());
                        if (!referencedPatients.containsKey(patientId)) {
                            referencedPatients.put(patientId, null); // Mark for later resolution
                        }
                    }
                }
            }
            
            // Get patients from observations
            if (observations != null) {
                for (Observation obs : observations) {
                    if (obs.hasSubject() && obs.getSubject().hasReference()) {
                        String patientRef = obs.getSubject().getReference();
                        if (patientRef.startsWith("Patient/")) {
                            String patientId = patientRef.substring("Patient/".length());
                            if (!referencedPatients.containsKey(patientId)) {
                                referencedPatients.put(patientId, null);
                            }
                        }
                    }
                }
            }
            
            ourLog.debug("Found {} unique patient references to ensure exist upstream", referencedPatients.size());
            
            // Step 1: Ensure all referenced patients exist upstream first
            if (!referencedPatients.isEmpty()) {
                int patientChunk = Math.max(50, patientTxChunkSize);
                List<String> patientIds = new ArrayList<>(referencedPatients.keySet());
                try {
                    for (int start = 0; start < patientIds.size(); start += patientChunk) {
                        int end = Math.min(patientIds.size(), start + patientChunk);
                        Bundle patientTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                        for (String patientId : patientIds.subList(start, end)) {
                            Bundle.BundleEntryComponent patientEntry = patientTx.addEntry();
                            // Create-only placeholder: do NOT overwrite existing upstream patients
                            patientEntry.setFullUrl("urn:uuid:patient-" + patientId);
                            patientEntry.getRequest()
                                    .setMethod(Bundle.HTTPVerb.POST)
                                    .setUrl("Patient")
                                    .setIfNoneExist("_id=" + patientId);
                            // Minimal patient placeholder only if missing upstream
                            Patient placeholderPatient = new Patient();
                            placeholderPatient.setId(patientId);
                            placeholderPatient.setActive(true);
                            patientEntry.setResource(placeholderPatient);
                        }
                        client.transaction().withBundle(patientTx).execute();
                    }
                    ourLog.debug("Ensured {} patients exist upstream", referencedPatients.size());
                } catch (Exception e) {
                    ourLog.warn("Some patients may not exist upstream: {}", e.getMessage());
                    // Continue anyway - the main transaction will fail with a clearer error if patients don't exist
                }
            }
            
            // Step 2: Post all Observations and get their new IDs
            if (observations != null && !observations.isEmpty()) {
                int obsChunk = Math.max(50, observationTxChunkSize);
                for (int start = 0; start < observations.size(); start += obsChunk) {
                    int end = Math.min(observations.size(), start + obsChunk);
                    List<Observation> obsSlice = observations.subList(start, end);

                    Bundle obsTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                    for (Observation obs : obsSlice) {
                        Observation obsCopy = obs.copy();
                        obsCopy.setId((String) null);

                        // Remove any existing location extensions and add the hardcoded one
                        obsCopy.getExtension().removeIf(ext -> "http://patient-location".equals(ext.getUrl()));

                        Extension locationExtension = new Extension();
                        locationExtension.setUrl("http://patient-location");
                        Extension blockExtension = new Extension();
                        blockExtension.setUrl("block");
                        blockExtension.setValue(new org.hl7.fhir.r4.model.StringType(blockValue));
                        locationExtension.addExtension(blockExtension);
                        obsCopy.addExtension(locationExtension);

                        Bundle.BundleEntryComponent e = obsTx.addEntry().setResource(obsCopy);

                        // Use conditional create to avoid duplicates and numeric ID issues
                        // Build search criteria based on patient, code, and effective date
                        StringBuilder criteria = new StringBuilder();
                        if (obs.hasSubject() && obs.getSubject().hasReference()) {
                            criteria.append("subject=").append(obs.getSubject().getReference());
                        }
                        if (obs.hasCode() && obs.getCode().hasCoding() && obs.getCode().getCodingFirstRep().hasCode()) {
                            if (criteria.length() > 0) criteria.append("&");
                            criteria.append("code=").append(obs.getCode().getCodingFirstRep().getCode());
                        }
                        if (obs.hasEffectiveDateTimeType()) {
                            if (criteria.length() > 0) criteria.append("&");
                            criteria.append("date=").append(obs.getEffectiveDateTimeType().getValueAsString());
                        }

                        // Use conditional UPDATE (PUT) to update existing observations or create new ones
                        e.getRequest()
                                .setMethod(Bundle.HTTPVerb.PUT)
                                .setUrl("Observation?" + (criteria.length() > 0 ? criteria.toString() : "identifier=temp"));
                    }

                    // Execute Observation transaction and map old IDs to new IDs
                    Bundle obsResponse = client.transaction().withBundle(obsTx).execute();
                    for (int i = 0; i < obsSlice.size(); i++) {
                        String oldId = obsSlice.get(i).getIdElement().getIdPart();
                        if (obsResponse.getEntry().size() > i && obsResponse.getEntry().get(i).hasResponse()) {
                            String location = obsResponse.getEntry().get(i).getResponse().getLocation();
                            if (location != null) {
                                // Extract new ID from location header (e.g., "Observation/123/_history/1")
                                String newId = extractIdFromLocation(location);
                                if (newId != null) {
                                    oldToNewObservationIds.put(oldId, newId);
                                }
                            }
                        }
                    }
                }
                ourLog.info("Posted {} observations and mapped {} IDs for condition forwarding",
                    observations.size(),
                    oldToNewObservationIds.size());
            }
            
            // Step 3: Update Condition references and post Conditions
            int condChunk = Math.max(50, conditionTxChunkSize);
            for (int start = 0; start < conditions.size(); start += condChunk) {
                int end = Math.min(conditions.size(), start + condChunk);
                Bundle conditionTx = new Bundle().setType(Bundle.BundleType.TRANSACTION);

                for (Condition c : conditions.subList(start, end)) {
                    // Create a copy and preserve deterministic ID for stable upstream upsert
                    Condition conditionCopy = c.copy();

                    // Update Observation references in evidence
                    if (conditionCopy.hasEvidence()) {
                        for (Condition.ConditionEvidenceComponent evidence : conditionCopy.getEvidence()) {
                            for (Reference ref : evidence.getDetail()) {
                                if (ref.getReference() != null && ref.getReference().startsWith("Observation/")) {
                                    String oldObsId = ref.getReference().substring("Observation/".length());
                                    String newObsId = oldToNewObservationIds.get(oldObsId);
                                    if (newObsId != null) {
                                        ref.setReference("Observation/" + newObsId);
                                    }
                                }
                            }
                        }
                    }

                    Bundle.BundleEntryComponent e = conditionTx.addEntry().setResource(conditionCopy);

                    String conditionId = conditionCopy.getIdElement().getIdPart();
                    if (conditionId != null && !conditionId.isBlank()) {
                        // Stable ID-based upsert
                        e.getRequest()
                                .setMethod(Bundle.HTTPVerb.PUT)
                                .setUrl("Condition/" + conditionId);
                    } else {
                        // Fallback conditional upsert
                        String patientRef = c.getSubject().getReference();
                        String code = c.getCode().getCodingFirstRep().getCode();
                        e.getRequest()
                                .setMethod(Bundle.HTTPVerb.PUT)
                                .setUrl("Condition?patient=" + patientRef + "&code=" + code + "&clinical-status=active");
                    }
                }

                client.transaction().withBundle(conditionTx).execute();
            }
            
        } catch (BaseServerResponseException e) {
            maybeSuppressUpstream(e, "conditions");
            ourLog.error("ERROR forwarding conditions: {}", e.getMessage());
            throw new RuntimeException("Failed to forward conditions", e);
        } catch (Exception e) {
            ourLog.error("ERROR forwarding conditions", e);
            throw new RuntimeException("Failed to forward conditions", e);
        }
    }

    private boolean isUpstreamSuppressed(String operation) {
        long until = upstreamSuppressUntilMs.get();
        long now = System.currentTimeMillis();
        if (until > now) {
            long remainingSec = (until - now) / 1000;
            ourLog.debug("Skipping upstream {} forwarding for {}s due to previous upstream schema error", operation, remainingSec);
            return true;
        }
        return false;
    }

    private void maybeSuppressUpstream(BaseServerResponseException e, String operation) {
        String msg = e.getMessage();
        if (msg == null) {
            return;
        }

        boolean schemaMissing = msg.contains("HFJ_SPIDX_TOKEN")
                || msg.contains("SQLGrammarException")
                || msg.contains("database is empty")
                || msg.contains("Table \"");

        if (schemaMissing) {
            long until = System.currentTimeMillis() + UPSTREAM_FAILURE_COOLDOWN_MS;
            upstreamSuppressUntilMs.set(until);
            ourLog.error("Upstream appears uninitialized (missing HAPI schema). Suppressing {} forwarding for {}s.",
                    operation,
                    UPSTREAM_FAILURE_COOLDOWN_MS / 1000);
        }
    }

    private void maybeSuppressOnConnectivityFailure(Exception e, String operation) {
        String msg = e.getMessage();
        if (msg == null) {
            return;
        }

        boolean connectivityIssue = msg.contains("HAPI-1357")
                || msg.contains("Failed to retrieve the server metadata statement")
                || msg.contains("Connection refused")
                || msg.contains("connect timed out")
                || msg.contains("Read timed out");

        if (connectivityIssue) {
            long until = System.currentTimeMillis() + UPSTREAM_FAILURE_COOLDOWN_MS;
            upstreamSuppressUntilMs.set(until);
            ourLog.warn("Upstream unavailable. Suppressing {} forwarding for {}s.",
                    operation,
                    UPSTREAM_FAILURE_COOLDOWN_MS / 1000);
        }
    }
    
    /**
     * Extract resource ID from location header
     * Example: "Observation/123/_history/1" -> "123"
     */
    private String extractIdFromLocation(String location) {
        if (location == null) return null;
        // Location format: "ResourceType/id/_history/version" or "ResourceType/id"
        String[] parts = location.split("/");
        if (parts.length >= 2) {
            return parts[1]; // Return the ID part
        }
        return null;
    }
}