package ca.uhn.fhir.jpa.starter.cdss;

import ca.uhn.fhir.rest.server.RestfulServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

/**
 * Configuration for Clinical Decision Support System (CDSS) module
 * Enables scheduled disease analysis using CQL
 */
@Configuration
@EnableScheduling
@ComponentScan(basePackages = "ca.uhn.fhir.jpa.starter.cdss")
public class CdssConfig {

    @Autowired(required = false)
    private RestfulServer restfulServer;

    @Autowired(required = false)
    private ScheduledDiseaseAnalysisService scheduledDiseaseAnalysisService;

    @PostConstruct
    public void init() {
        System.out.println("========================================");
        System.out.println("CDSS Module Initializing");
        System.out.println("========================================");
        
        // Scheduled analysis service will auto-start via @Scheduled
        if (scheduledDiseaseAnalysisService != null) {
            System.out.println("✓ Scheduled disease analysis service enabled (runs every 5 minutes)");
        }
        
        System.out.println("========================================");
        System.out.println("CDSS Module Ready");
        System.out.println("Features:");
        System.out.println("  - Scheduled analysis every 5 minutes");
        System.out.println("  - CQL-based disease detection");
        System.out.println("  - Automatic Condition creation");
        System.out.println("  - Duplicate prevention");
        System.out.println("  - Location tracking via extensions");
        System.out.println("  - Links: Patient + Observations + Encounter + Location");
        System.out.println("========================================");
    }
}
