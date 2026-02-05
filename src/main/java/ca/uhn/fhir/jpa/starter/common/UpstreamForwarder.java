package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UpstreamForwarder {
    private final IGenericClient client;
    private final String neighborhood;

    public UpstreamForwarder(FhirContext ctx, 
                           @Value("${upstream.fhir.base-url:http://localhost:8082/fhir}") String upstreamUrl,
                           @Value("${location.neighborhood:center}") String neighborhood) {
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
        this.neighborhood = neighborhood;
    }

    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Observation o : observations) {
                // Extract block from observation location extension
                String block = extractBlock(o);
                String scopedId = buildScopedId(neighborhood, block, o.getIdElement().getIdPart());
                
                // Add identifier for block-scoped tracking
                o.addIdentifier()
                    .setSystem("urn:observation:neigh-block-scope")
                    .setValue(scopedId);
                
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(o);
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Observation?identifier=urn:observation:neigh-block-scope|" + scopedId);
            }
            client.transaction().withBundle(tx).execute();
        } catch (Exception ignored) { }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Patient p : patients) {
                // Extract block from patient location extension
                String block = extractBlock(p);
                String scopedId = buildScopedId(neighborhood, block, p.getIdElement().getIdPart());
                
                // Add identifier for block-scoped tracking
                p.addIdentifier()
                    .setSystem("urn:patient:neigh-block-scope")
                    .setValue(scopedId);
                
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(p);
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl("Patient?identifier=urn:patient:neigh-block-scope|" + scopedId);
            }
            client.transaction().withBundle(tx).execute();
        } catch (Exception ignored) { }
    }
    
    private String buildScopedId(String neighborhood, String block, String resourceId) {
        return neighborhood + "-" + block + "-" + resourceId;
    }
    
    private String extractBlock(org.hl7.fhir.r4.model.Resource resource) {
        org.hl7.fhir.r4.model.Extension locExt = null;
        if (resource instanceof Patient) {
            locExt = ((Patient) resource).getExtensionByUrl("http://patient-location");
        } else if (resource instanceof Observation) {
            locExt = ((Observation) resource).getExtensionByUrl("http://patient-location");
        }
        
        if (locExt != null) {
            org.hl7.fhir.r4.model.Extension blockExt = locExt.getExtension().stream()
                .filter(e -> "block".equals(e.getUrl()))
                .findFirst().orElse(null);
            if (blockExt != null && blockExt.getValue() != null) {
                return blockExt.getValue().primitiveValue();
            }
        }
        return "UNKNOWN";
    }
}
