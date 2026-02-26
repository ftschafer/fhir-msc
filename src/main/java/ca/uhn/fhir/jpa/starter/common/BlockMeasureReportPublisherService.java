package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class BlockMeasureReportPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(BlockMeasureReportPublisherService.class);

    private final UpstreamForwarder upstreamForwarder;
    private final FhirContext fhirContext;
    private final DaoRegistry daoRegistry;
    private int lastPublishedTotalPatients = -1;

    @Value("${hapi.fhir.location.block:North}")
    private String blockValue;

    @Value("${hapi.fhir.measure-report.block-health.measure:Measure/block-health-aggregation}")
    private String measureCanonical;

    @Value("${hapi.fhir.measure-report.block-health.identifier-system:urn:block:health-aggregation}")
    private String identifierSystem;

    @Value("${hapi.fhir.measure-report.block-health.id-prefix:block-health-aggregation}")
    private String reportIdPrefix;

    @Value("${hapi.fhir.measure-report.block-health.period-start:2026-02-01}")
    private String periodStart;

    @Value("${hapi.fhir.measure-report.block-health.period-end:2026-02-28}")
    private String periodEnd;

    @Value("${hapi.fhir.measure-report.block-health.location-extension-url:http://patient-location}")
    private String locationExtensionUrl;

    @Value("${hapi.fhir.measure-report.block-health.block-extension-url:block}")
    private String blockExtensionUrl;

    @Value("${hapi.fhir.measure-report.block-health.group-text:Block Summary}")
    private String groupText;

    @Value("${hapi.fhir.measure-report.block-health.avg-income:3200}")
    private double averageIncome;

    @Value("${hapi.fhir.measure-report.block-health.avg-income-unit:BRL/month}")
    private String averageIncomeUnit;

    @Value("${hapi.fhir.measure-report.block-health.care-units:4}")
    private int careUnits;

    @Value("${hapi.fhir.measure-report.block-health.care-units-unit:units}")
    private String careUnitsUnit;

    @Value("${hapi.fhir.measure-report.block-health.mean-age-unit:years}")
    private String meanAgeUnit;

    @Value("${hapi.fhir.measure-report.block-health.seasonality:summer}")
    private String seasonality;

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String BLOCK_URL = "block";

    public BlockMeasureReportPublisherService(
        UpstreamForwarder upstreamForwarder,
        FhirContext fhirContext,
        DaoRegistry daoRegistry
    ) {
        this.upstreamForwarder = upstreamForwarder;
        this.fhirContext = fhirContext;
      this.daoRegistry = daoRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishAtStartup() {
      publishIfEligible("startup");
    }

    @Scheduled(fixedDelay = 5, initialDelay = 5)
    public void publishEveryFiveMinutes() {
      publishIfEligible("scheduled-5min");
    }

    private synchronized void publishIfEligible(String trigger) {
      AgeStats ageStats = calculateAgeStatsForBlock(blockValue);

      if (lastPublishedTotalPatients < 0) {
        if (ageStats.validBirthDateCount() == 0) {
          logger.info("Skipping initial MeasureReport publish: waiting for first valid age calculation.");
          return;
        }
        publishMeasureReport(trigger, ageStats);
        lastPublishedTotalPatients = ageStats.totalPatients();
        return;
      }

      if (ageStats.totalPatients() <= lastPublishedTotalPatients) {
        logger.debug("Skipping MeasureReport publish: no new patients since last publish.");
        return;
      }

      if (ageStats.validBirthDateCount() == 0) {
        logger.info("Skipping MeasureReport publish: new patients detected but no valid birthDate available.");
        return;
      }

      publishMeasureReport(trigger, ageStats);
      lastPublishedTotalPatients = ageStats.totalPatients();
    }

    private void publishMeasureReport(String trigger, AgeStats ageStats) {
        try {
            MeasureReport measureReport = fhirContext
                    .newJsonParser()
          .parseResource(MeasureReport.class, configuredMeasureReportJson(
                  blockValue,
            ageStats.meanAge(),
            ageStats.maleCount(),
            ageStats.femaleCount()
          ));

        upsertLocalMeasureReport(measureReport);

            upstreamForwarder.upsertMeasureReports(List.of(measureReport));
        logger.info("Saved locally and forwarded block MeasureReport. trigger={}, block={}, meanAge={}",
            trigger,
            blockValue,
            String.format(Locale.US, "%.2f", ageStats.meanAge()));
        } catch (Exception e) {
        logger.warn("Failed to build/save/forward block MeasureReport: {}", e.getMessage());
        }
    }

    private IFhirResourceDao<MeasureReport> measureReportDao() {
      return daoRegistry.getResourceDao(MeasureReport.class);
    }

    private IFhirResourceDao<Patient> patientDao() {
      return daoRegistry.getResourceDao(Patient.class);
    }

    private void upsertLocalMeasureReport(MeasureReport measureReport) {
      if (measureReport.getIdElement() != null
          && measureReport.getIdElement().hasIdPart()
          && !measureReport.getIdElement().getIdPart().isBlank()) {
        measureReportDao().update(measureReport);
      } else {
        measureReportDao().create(measureReport);
      }
    }

    private String configuredMeasureReportJson(
        String block,
      double resolvedMeanAge,
      int maleCount,
      int femaleCount
    ) {
        String safeBlock = block == null || block.isBlank() ? "North" : block.trim();
        String sanitizedBlock = safeBlock.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String reportId = reportIdPrefix + "-" + sanitizedBlock;
        String identifier = safeBlock + "-" + reportIdPrefix;

        return String.format(Locale.US, """
                {
                  "resourceType": "MeasureReport",
                  "id": "%s",
                  "status": "complete",
                  "type": "summary",
                  "measure": "%s",
                  "identifier": [
                    {
                      "system": "%s",
                      "value": "%s"
                    }
                  ],
                  "extension": [
                    {
                      "url": "%s",
                      "extension": [
                        {
                          "url": "%s",
                          "valueString": "%s"
                        }
                      ]
                    }
                  ],
                  "period": {
                    "start": "%s",
                    "end": "%s"
                  },
                  "group": [
                    {
                      "code": {
                        "text": "%s"
                      },
                      "stratifier": [
                        {
                          "code": [{ "text": "Average Income" }],
                          "stratum": [
                            {
                              "value": { "text": "Income" },
                              "measureScore": {
                                "value": %.2f,
                                "unit": "%s"
                              }
                            }
                          ]
                        },
                        {
                          "code": [{ "text": "Care Units" }],
                          "stratum": [
                            {
                              "value": { "text": "Primary Care Units" },
                              "measureScore": {
                                "value": %d,
                                "unit": "%s"
                              }
                            }
                          ]
                        },
                        {
                          "code": [{ "text": "Mean Age" }],
                          "stratum": [
                            {
                              "value": { "text": "Age" },
                              "measureScore": {
                                "value": %.2f,
                                "unit": "%s"
                              }
                            }
                          ]
                        },
                        {
                          "code": [{ "text": "Male Patients" }],
                          "stratum": [
                            {
                              "value": { "text": "Male" },
                              "measureScore": {
                                "value": %d,
                                "unit": "patients"
                              }
                            }
                          ]
                        },
                        {
                          "code": [{ "text": "Female Patients" }],
                          "stratum": [
                            {
                              "value": { "text": "Female" },
                              "measureScore": {
                                "value": %d,
                                "unit": "patients"
                              }
                            }
                          ]
                        },
                        {
                          "code": [{ "text": "Seasonality" }],
                          "stratum": [
                            {
                              "value": { "text": "%s" }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """,
                reportId,
                measureCanonical,
                identifierSystem,
                identifier,
                locationExtensionUrl,
                blockExtensionUrl,
                safeBlock,
                periodStart,
                periodEnd,
                groupText,
                averageIncome,
                averageIncomeUnit,
                careUnits,
                careUnitsUnit,
                resolvedMeanAge,
                meanAgeUnit,
                maleCount,
                femaleCount,
                seasonality
              );
    }

    private AgeStats calculateAgeStatsForBlock(String block) {
      try {
        SearchParameterMap searchMap = new SearchParameterMap();
        searchMap.setLoadSynchronous(true);

        IBundleProvider results = patientDao().search(searchMap);
        List<IBaseResource> resources = results.getAllResources();
        int totalPatients = 0;
        int maleCount = 0;
        int femaleCount = 0;

        LocalDate today = LocalDate.now();
        int count = 0;
        int totalAgeYears = 0;
        Set<String> patientIdsInBlock = new HashSet<>();

        for (IBaseResource resource : resources) {
          if (!(resource instanceof Patient patient) || !patient.hasBirthDate()) {
            if (resource instanceof Patient patientWithoutBirthDate
                    && isPatientInBlock(patientWithoutBirthDate, block)
                    && patientWithoutBirthDate.getIdElement() != null
                    && patientWithoutBirthDate.getIdElement().hasIdPart()) {
              totalPatients++;
              if (patientWithoutBirthDate.getGender() == Enumerations.AdministrativeGender.MALE) {
                maleCount++;
              } else if (patientWithoutBirthDate.getGender() == Enumerations.AdministrativeGender.FEMALE) {
                femaleCount++;
              }
              patientIdsInBlock.add(patientWithoutBirthDate.getIdElement().getIdPart());
            }
            continue;
          }

          if (!isPatientInBlock(patient, block)) {
            continue;
          }

          if (patient.getIdElement() != null && patient.getIdElement().hasIdPart()) {
            patientIdsInBlock.add(patient.getIdElement().getIdPart());
          }

          totalPatients++;
          if (patient.getGender() == Enumerations.AdministrativeGender.MALE) {
            maleCount++;
          } else if (patient.getGender() == Enumerations.AdministrativeGender.FEMALE) {
            femaleCount++;
          }

          LocalDate birthDate = patient.getBirthDate().toInstant()
              .atZone(ZoneId.systemDefault())
              .toLocalDate();

          int years = Period.between(birthDate, today).getYears();
          if (years >= 0 && years <= 130) {
            totalAgeYears += years;
            count++;
          }
        }

        if (count == 0) {
          return new AgeStats(totalPatients, 0, 0.0, patientIdsInBlock, maleCount, femaleCount);
        }

        return new AgeStats(totalPatients, count, (double) totalAgeYears / (double) count, patientIdsInBlock, maleCount, femaleCount);
      } catch (Exception e) {
        logger.warn("Mean age calculation failed: {}", e.getMessage());
        return new AgeStats(0, 0, 0.0, new HashSet<>(), 0, 0);
      }
    }

    private boolean isPatientInBlock(Patient patient, String block) {
      if (block == null || block.isBlank()) {
        return true;
      }

      Extension locationExt = patient.getExtensionByUrl(LOCATION_EXTENSION_URL);
      if (locationExt == null) {
        return false;
      }

      for (Extension nested : locationExt.getExtension()) {
        if (BLOCK_URL.equals(nested.getUrl()) && nested.getValue() instanceof StringType st) {
          String patientBlock = st.getValue();
          return patientBlock != null && patientBlock.equalsIgnoreCase(block);
        }
      }

      return false;
    }

    private record AgeStats(
      int totalPatients,
      int validBirthDateCount,
      double meanAge,
      Set<String> patientIdsInBlock,
      int maleCount,
      int femaleCount
    ) {
    }
}
