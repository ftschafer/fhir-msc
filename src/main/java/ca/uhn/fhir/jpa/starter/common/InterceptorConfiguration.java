package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.context.FhirContext;


@Configuration
public class InterceptorConfiguration {

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired
    private FhirContext fhirContext;

    @Bean
    public PatientTotalNews2ScoreInterceptor patientTotalNews2ScoreInterceptor() {
        return new PatientTotalNews2ScoreInterceptor(daoRegistry, fhirContext);
    }
}
