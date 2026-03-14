package ca.uhn.fhir.jpa.starter.common;

import java.util.*;
import java.util.concurrent.*;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;

import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.instance.model.api.IBaseResource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Timer;

/**
 * Collects newly created Observations during a transaction, ensures each collected Observation
 * carries the NEWS2 extension when possible, and hands the bundle to News2AggregationService
 * after the transaction is processed.
 */
@Component
public class News2AggregationInterceptorDB {

    private static final Logger logger = LoggerFactory.getLogger(News2AggregationInterceptorDB.class);

    private static final String TX_OBS_KEY = News2AggregationInterceptorDB.class.getName() + ".OBS_SET";
    private static final String INTERNAL_REQUEST_HEADER = "X-Internal-Request";

    // Use the same extension URL as PatientTotalNews2ScoreInterceptor
    private static final String NEWS2_EXTENSION_URL = "http://news2-score";

    private final News2AggregationService aggregationService;
    private final PerfMetricsService perfMetrics;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Autowired
    public News2AggregationInterceptorDB(News2AggregationService aggregationService, PerfMetricsService perfMetrics) {
        this.aggregationService = aggregationService;
        this.perfMetrics = perfMetrics;
    }

    // Collect created Observation instances (make copies so they are safe to use after commit).
    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onObservationCreated(IBaseResource resource, RequestDetails requestDetails, TransactionDetails tx) {
        if (isInternalRequest(requestDetails)) return;
        if (!(resource instanceof Observation)) return;
        Observation obs = (Observation) resource;

        // copy to detach from transient state and safely mutate
        Observation copy = (Observation) obs.copy();

        // If the observation does not already have the NEWS2 extension, try to add it from common value fields
        boolean hasNews2Ext = copy.getExtension().stream().anyMatch(ext -> NEWS2_EXTENSION_URL.equals(ext.getUrl()));
        if (!hasNews2Ext) {
            Integer v = null;
            if (copy.hasValueIntegerType()) {
                v = copy.getValueIntegerType().getValue();
            } else if (copy.hasValueQuantity()) {
                Quantity q = copy.getValueQuantity();
                if (q != null && q.getValue() != null) {
                    try { v = q.getValue().intValue(); } catch (Exception ignored) {}
                }
            }
            if (v != null) {
                copy.addExtension(new Extension(NEWS2_EXTENSION_URL, new IntegerType(v)));
            }
        }

        @SuppressWarnings("unchecked")
        Set<Observation> set = (Set<Observation>) tx.getUserData(TX_OBS_KEY);
        if (set == null) {
            set = Collections.synchronizedSet(new HashSet<>());
            tx.putUserData(TX_OBS_KEY, set);
        }
        set.add(copy);
        perfMetrics.recordObservationIngested();
        logger.debug("Collected observation for NEWS2 aggregation (patientRef={})", obs.getSubject() == null ? "?" : obs.getSubject().getReference());
    }

    // After transaction processed, hand collected observations to the aggregation service asynchronously.
    @Hook(Pointcut.STORAGE_TRANSACTION_PROCESSED)
    public void onTransactionProcessed(RequestDetails requestDetails, TransactionDetails tx) {
        if (isInternalRequest(requestDetails)) return;
        @SuppressWarnings("unchecked")
        Set<Observation> set = (Set<Observation>) tx.getUserData(TX_OBS_KEY);
        tx.putUserData(TX_OBS_KEY, null); // Remove the key after retrieving
        if (set == null || set.isEmpty()) return;

        List<Observation> observations = new ArrayList<>(set);
        executor.submit(() -> {
            Timer.Sample sample = Timer.start();
            try {
                logger.info("Processing {} observations for NEWS2 aggregation", observations.size());
                aggregationService.processBundleObservations(observations);
            } catch (Exception e) {
                perfMetrics.recordObservationProcessingError();
                logger.error("News2 aggregation failed", e);
            } finally {
                sample.stop(perfMetrics.observationProcessingTimer);
            }
        });
    }

    private boolean isInternalRequest(RequestDetails requestDetails) {
        if (requestDetails == null) return false;
        String val = requestDetails.getHeader(INTERNAL_REQUEST_HEADER);
        return val != null && "true".equalsIgnoreCase(val);
    }
}
