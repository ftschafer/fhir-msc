package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import org.hl7.fhir.r4.model.MeasureReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class BlockMeasureReportPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(BlockMeasureReportPublisherService.class);

    private final UpstreamForwarder upstreamForwarder;
    private final FhirContext fhirContext;
    private final DaoRegistry daoRegistry;

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

    @Value("${hapi.fhir.measure-report.block-health.total-conditions-label:Total Number of Conditions}")
    private String totalConditionsLabel;

    @Value("${hapi.fhir.measure-report.block-health.total-conditions:340}")
    private int totalConditions;

    @Value("${hapi.fhir.measure-report.block-health.mean-news2:3.7}")
    private double meanNews2;

    @Value("${hapi.fhir.measure-report.block-health.mean-news2-unit:Mean NEWS2}")
    private String meanNews2Unit;

    @Value("${hapi.fhir.measure-report.block-health.mean-news2-code:{score}}")
    private String meanNews2Code;

    @Value("${hapi.fhir.measure-report.block-health.avg-income:3200}")
    private double averageIncome;

    @Value("${hapi.fhir.measure-report.block-health.avg-income-unit:BRL/month}")
    private String averageIncomeUnit;

    @Value("${hapi.fhir.measure-report.block-health.care-units:4}")
    private int careUnits;

    @Value("${hapi.fhir.measure-report.block-health.care-units-unit:units}")
    private String careUnitsUnit;

    @Value("${hapi.fhir.measure-report.block-health.mean-age:42.5}")
    private double meanAge;

    @Value("${hapi.fhir.measure-report.block-health.mean-age-unit:years}")
    private String meanAgeUnit;

    @Value("${hapi.fhir.measure-report.block-health.seasonality:summer}")
    private String seasonality;

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
        forwardHardcodedMeasureReport("startup");
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void publishEveryFiveMinutes() {
        forwardHardcodedMeasureReport("scheduled-5min");
    }

    private void forwardHardcodedMeasureReport(String trigger) {
        try {
            MeasureReport measureReport = fhirContext
                    .newJsonParser()
              .parseResource(MeasureReport.class, configuredMeasureReportJson(blockValue));

        upsertLocalMeasureReport(measureReport);

            upstreamForwarder.upsertMeasureReports(List.of(measureReport));
        logger.info("Saved locally and forwarded hardcoded block MeasureReport. trigger={}, block={}", trigger, blockValue);
        } catch (Exception e) {
        logger.warn("Failed to build/save/forward hardcoded block MeasureReport: {}", e.getMessage());
        }
    }

    private IFhirResourceDao<MeasureReport> measureReportDao() {
      return daoRegistry.getResourceDao(MeasureReport.class);
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

    private String configuredMeasureReportJson(String block) {
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
                      "population": [
                        {
                          "code": { "text": "%s" },
                          "count": %d
                        }
                      ],
                      "measureScore": {
                        "value": %.2f,
                        "unit": "%s",
                        "system": "http://unitsofmeasure.org",
                        "code": "%s"
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
                totalConditionsLabel,
                totalConditions,
                meanNews2,
                meanNews2Unit,
                meanNews2Code,
                averageIncome,
                averageIncomeUnit,
                careUnits,
                careUnitsUnit,
                meanAge,
                meanAgeUnit,
                seasonality
              );
    }
}
