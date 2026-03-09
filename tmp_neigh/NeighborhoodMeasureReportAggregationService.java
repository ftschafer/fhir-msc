package ca.uhn.fhir.jpa.starter.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

@Service
public class NeighborhoodMeasureReportAggregationService {

    private static final Logger ourLog = LoggerFactory.getLogger(NeighborhoodMeasureReportAggregationService.class);

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String NEIGHBORHOOD_URL = "neighborhood";

    private static final String BLOCK_MEASURE = "Measure/block-health-aggregation";
    private static final String NEIGHBORHOOD_MEASURE = "Measure/neighborhood-health-aggregation";

    private static final String BLOCK_IDENTIFIER_SYSTEM = "urn:block:health-aggregation";
    private static final String NEIGHBORHOOD_IDENTIFIER_SYSTEM = "urn:neighborhood:health-aggregation";

    private static final String STRAT_AVERAGE_INCOME = "Average Income";
    private static final String STRAT_CARE_UNITS = "Care Units";
    private static final String STRAT_MEAN_AGE = "Mean Age";
    private static final String STRAT_MALE_PATIENTS = "Male Patients";
    private static final String STRAT_FEMALE_PATIENTS = "Female Patients";
    private static final String STRAT_SEASONALITY = "Seasonality";

    private final DaoRegistry daoRegistry;
    private final UpstreamForwarder upstreamForwarder;

    @org.springframework.beans.factory.annotation.Value("${location.neighborhood}")
    private String defaultNeighborhood;

    public NeighborhoodMeasureReportAggregationService(DaoRegistry daoRegistry, UpstreamForwarder upstreamForwarder) {
        this.daoRegistry = daoRegistry;
        this.upstreamForwarder = upstreamForwarder;
    }

    @Scheduled(fixedDelayString = "${aggregation.neighborhood.measure-report.ms:5000}")
    public void aggregateNeighborhoodMeasureReports() {
        try {
            IFhirResourceDao<MeasureReport> dao = daoRegistry.getResourceDao(MeasureReport.class);
            List<MeasureReport> blockReports = loadBlockReports(dao);
            if (blockReports.isEmpty()) {
                return;
            }

            Map<String, NeighborhoodAccumulator> byNeighborhood = new HashMap<>();
            for (MeasureReport blockReport : blockReports) {
                String neighborhood = normalize(defaultNeighborhood);
                if (neighborhood == null) {
                    continue;
                }

                NeighborhoodAccumulator acc = byNeighborhood.computeIfAbsent(neighborhood, NeighborhoodAccumulator::new);
                acc.accept(blockReport);
            }

            List<MeasureReport> forwarded = new java.util.ArrayList<>();
            for (NeighborhoodAccumulator acc : byNeighborhood.values()) {
                MeasureReport report = upsertNeighborhoodReport(dao, acc);
                if (report != null) {
                    forwarded.add(report);
                }
            }

            // Forward to upstream after local persistence succeeds
            if (!forwarded.isEmpty() && upstreamForwarder != null) {
                try {
                    upstreamForwarder.upsertMeasureReports(forwarded);
                } catch (Exception ue) {
                    ourLog.warn("Upstream MeasureReport forwarding failed (local copy is safe): {}", ue.getMessage());
                }
            }
        } catch (Exception e) {
            ourLog.warn("Neighborhood MeasureReport aggregation failed: {}", e.getMessage(), e);
        }
    }

    private List<MeasureReport> loadBlockReports(IFhirResourceDao<MeasureReport> dao) {
        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);
        IBundleProvider provider = dao.search(search);
        List<IBaseResource> all = getAllResources(provider);

        List<MeasureReport> out = new ArrayList<>();
        for (IBaseResource resource : all) {
            if (!(resource instanceof MeasureReport report)) {
                continue;
            }
            if (isBlockLayerReport(report)) {
                out.add(report);
            }
        }
        return out;
    }

    private boolean isBlockLayerReport(MeasureReport report) {
        if (report == null) {
            return false;
        }
        if (BLOCK_MEASURE.equals(report.getMeasure())) {
            return true;
        }
        for (Identifier identifier : report.getIdentifier()) {
            if (identifier == null) {
                continue;
            }
            if (BLOCK_IDENTIFIER_SYSTEM.equals(identifier.getSystem())) {
                return true;
            }
        }
        return false;
    }

    private String extractNeighborhood(MeasureReport report) {
        Extension locExt = report.getExtensionByUrl(LOCATION_EXTENSION_URL);
        if (locExt == null) {
            return null;
        }
        for (Extension nested : locExt.getExtension()) {
            if (NEIGHBORHOOD_URL.equals(nested.getUrl()) && nested.getValue() != null) {
                return nested.getValue().primitiveValue();
            }
        }
        return null;
    }

    private MeasureReport upsertNeighborhoodReport(IFhirResourceDao<MeasureReport> dao, NeighborhoodAccumulator acc) {
        if (acc.blockCount == 0) {
            return null;
        }

        String idPart = "neighborhood-health-aggregation-" + slug(acc.neighborhood);
        MeasureReport report = new MeasureReport();
        report.setId("MeasureReport/" + idPart);
        report.getIdentifierFirstRep()
            .setSystem(NEIGHBORHOOD_IDENTIFIER_SYSTEM)
            .setValue(acc.neighborhood + "-neighborhood-health-aggregation");

        report.setStatus(MeasureReport.MeasureReportStatus.COMPLETE);
        report.setType(MeasureReport.MeasureReportType.SUMMARY);
        report.setMeasure(NEIGHBORHOOD_MEASURE);

        Period period = new Period();
        if (acc.minStart != null) {
            period.setStart(acc.minStart);
        }
        if (acc.maxEnd != null) {
            period.setEnd(acc.maxEnd);
        }
        report.setPeriod(period);

        Extension location = new Extension().setUrl(LOCATION_EXTENSION_URL);
        location.addExtension(new Extension()
            .setUrl(NEIGHBORHOOD_URL)
            .setValue(new StringType(acc.neighborhood)));
        report.addExtension(location);

        MeasureReport.MeasureReportGroupComponent group = report.addGroup();
        group.getCode().setText("Neighborhood Summary");

        addNumericStratifier(group, STRAT_AVERAGE_INCOME, "Income", average(acc.sumIncome, acc.incomeCount), "BRL/month");
        addNumericStratifier(group, STRAT_CARE_UNITS, "Primary Care Units", acc.sumCareUnits, "units");
        addNumericStratifier(group, STRAT_MEAN_AGE, "Age", average(acc.sumAge, acc.ageCount), "years");
        addNumericStratifier(group, STRAT_MALE_PATIENTS, "Male", acc.sumMalePatients, "patients");
        addNumericStratifier(group, STRAT_FEMALE_PATIENTS, "Female", acc.sumFemalePatients, "patients");
        addSeasonalityStratifier(group, "summer");

        try {
            dao.update(report);
        } catch (Exception e) {
            dao.create(report);
        }
        return report;
    }

    private void addNumericStratifier(
        MeasureReport.MeasureReportGroupComponent group,
        String stratifierText,
        String valueText,
        BigDecimal value,
        String unit
    ) {
        MeasureReport.MeasureReportGroupStratifierComponent stratifier = group.addStratifier();
        stratifier.addCode().setText(stratifierText);
        MeasureReport.StratifierGroupComponent stratum = stratifier.addStratum();
        stratum.getValue().setText(valueText);
        if (value != null) {
            stratum.getMeasureScore().setValue(value).setUnit(unit);
        }
    }

    private void addSeasonalityStratifier(MeasureReport.MeasureReportGroupComponent group, String season) {
        MeasureReport.MeasureReportGroupStratifierComponent stratifier = group.addStratifier();
        stratifier.addCode().setText(STRAT_SEASONALITY);
        MeasureReport.StratifierGroupComponent stratum = stratifier.addStratum();
        stratum.getValue().setText(season);
    }

    private List<IBaseResource> getAllResources(IBundleProvider provider) {
        if (provider == null) {
            return List.of();
        }

        Integer size = provider.size();
        if (size == null || size < 0) {
            return provider.getAllResources();
        }

        List<IBaseResource> all = new ArrayList<>(size);
        int pageSize = 500;
        for (int from = 0; from < size; from += pageSize) {
            int to = Math.min(from + pageSize, size);
            List<IBaseResource> page = provider.getResources(from, to);
            if (page == null || page.isEmpty()) {
                break;
            }
            all.addAll(page);
        }
        return all;
    }

    private BigDecimal average(BigDecimal sum, int count) {
        if (sum == null || count <= 0) {
            return BigDecimal.ZERO;
        }
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private String slug(String input) {
        String normalized = normalize(input);
        if (normalized == null) {
            return "unknown";
        }
        return normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class NeighborhoodAccumulator {
        final String neighborhood;

        int blockCount;
        BigDecimal sumIncome = BigDecimal.ZERO;
        int incomeCount;

        BigDecimal sumAge = BigDecimal.ZERO;
        int ageCount;

        BigDecimal sumMalePatients = BigDecimal.ZERO;
        BigDecimal sumFemalePatients = BigDecimal.ZERO;
        BigDecimal sumCareUnits = BigDecimal.ZERO;

        Date minStart;
        Date maxEnd;

        NeighborhoodAccumulator(String neighborhood) {
            this.neighborhood = neighborhood;
        }

        void accept(MeasureReport report) {
            blockCount++;

            BigDecimal income = extract(report, STRAT_AVERAGE_INCOME);
            if (income != null) {
                sumIncome = sumIncome.add(income);
                incomeCount++;
            }

            BigDecimal age = extract(report, STRAT_MEAN_AGE);
            if (age != null) {
                sumAge = sumAge.add(age);
                ageCount++;
            }

            BigDecimal male = extract(report, STRAT_MALE_PATIENTS);
            if (male != null) {
                sumMalePatients = sumMalePatients.add(male);
            }

            BigDecimal female = extract(report, STRAT_FEMALE_PATIENTS);
            if (female != null) {
                sumFemalePatients = sumFemalePatients.add(female);
            }

            BigDecimal careUnits = extract(report, STRAT_CARE_UNITS);
            if (careUnits != null) {
                sumCareUnits = sumCareUnits.add(careUnits);
            }

            if (report.hasPeriod()) {
                Date start = report.getPeriod().getStart();
                Date end = report.getPeriod().getEnd();
                if (start != null && (minStart == null || start.before(minStart))) {
                    minStart = start;
                }
                if (end != null && (maxEnd == null || end.after(maxEnd))) {
                    maxEnd = end;
                }
            }
        }

        private BigDecimal extract(MeasureReport report, String stratifierText) {
            if (report == null || !report.hasGroup()) {
                return null;
            }
            for (MeasureReport.MeasureReportGroupComponent group : report.getGroup()) {
                for (MeasureReport.MeasureReportGroupStratifierComponent stratifier : group.getStratifier()) {
                    String text = null;
                    if (!stratifier.getCode().isEmpty() && stratifier.getCodeFirstRep().hasText()) {
                        text = stratifier.getCodeFirstRep().getText();
                    }
                    if (text == null || !text.equalsIgnoreCase(stratifierText)) {
                        continue;
                    }
                    if (stratifier.hasStratum() && stratifier.getStratumFirstRep().hasMeasureScore()) {
                        if (stratifier.getStratumFirstRep().getMeasureScore().getValue() != null) {
                            return stratifier.getStratumFirstRep().getMeasureScore().getValue();
                        }
                    }
                }
            }
            return null;
        }
    }
}
