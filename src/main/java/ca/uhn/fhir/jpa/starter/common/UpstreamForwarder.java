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

    public UpstreamForwarder(FhirContext ctx, @Value("${upstream.fhir.base-url:http://18.218.25.8:8081/fhir}") String upstreamUrl) {
        this.client = ctx.newRestfulGenericClient(upstreamUrl);
        this.client.registerInterceptor(new SimpleRequestHeaderInterceptor("X-Internal-Request", "true"));
    }

    public void createObservations(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) return;
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Observation o : observations) {
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(o);
                String id = o.getIdElement().getIdPart();
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl(id != null ? "Observation/" + id : "Observation");
            }
            client.transaction().withBundle(tx).execute();
        } catch (Exception ignored) { }
    }

    public void upsertPatients(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        try {
            Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
            for (Patient p : patients) {
                Bundle.BundleEntryComponent e = tx.addEntry().setResource(p);
                String id = p.getIdElement().getIdPart();
                e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
                    .setUrl(id != null ? "Patient/" + id : "Patient");
            }
            client.transaction().withBundle(tx).execute();
        } catch (Exception ignored) { }
    }
}
