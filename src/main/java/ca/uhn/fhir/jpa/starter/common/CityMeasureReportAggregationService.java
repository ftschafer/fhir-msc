package ca.uhn.fhir.jpa.starter.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import io.micrometer.core.instrument.Timer;

/**
 * Scheduled service that aggregates neighbourhood-level MeasureReports into
 * a single city-level MeasureReport for the configured city.
 */
@Service
public class CityMeasureReportAggregationService {

    private static final Logger ourLog = LoggerFactory.getLogger(CityMeasureReportAggregationService.class);

    private static final String LOCATION_EXTENSION_URL = "http://patient-location";
    private static final String CITY_URL = "city";

    private static final String NEIGHBORHOOD_MEASURE    = "Measure/neighborhood-health-aggregation";
    private static final String CITY_MEASURE            = "Measure/city-health-aggregation";

    private static final String NEIGHBORHOOD_IDENTIFIER_SYSTEM = "urn:neighborhood:health-aggregation";
    private static final String CITY_IDENTIFIER_SYSTEM         = "urn:city:health-aggregation";

    private static final String STRAT_AVERAGE_INCOME  = "Average Income";
    private static final String STRAT_CARE_UNITS      = "Care Units";
    private static final String STRAT_MEAN_AGE        = "Mean Age";
    private static final String STRAT_MALE_PATIENTS   = "Male Patients";
    private static final String STRAT_FEMALE_PATIENTS = "Female Patients";
    private static final String STRAT_SEASONALITY     = "Seasonality";

    @Value("${location.city}")
    private String configuredCity;

    private final DaoRegistry daoRegistry;
    private final UpstreamForwarder upstreamForwarder;
    private final PerfMetricsService perfMetrics;

    public CityMeasureReportAggregationService(DaoRegistry daoRegistry, UpstreamForwarder upstreamForwarder, PerfMetricsService perfMetrics) {
        this.daoRegistry = daoRegistry;
        this.upstreamForwarder = upstreamForwarder;
        this.perfMetrics = perfMetrics;
    }

    @Scheduled(fixedDelayString = "${aggregation.city.measure-report.ms:10000}")
    public void aggregateCityMeasureReports() {
        Timer.Sample sample = Timer.start();
        try {
            IFhirResourceDao<MeasureReport> dao = daoRegistry.getResourceDao(MeasureReport.class);
            List<MeasureReport> neighReports = loadNeighbourhoodReports(dao);
            if (neighReports.isEmpty()) {
                return;
            }

            CityAccumulator acc = new CityAccumulator(normalize(configuredCity));
            for (MeasureReport report : neighReports) {
                acc.accept(report);
            }

            MeasureReport cityReport = upsertCityReport(dao, acc);
            if (cityReport != null && upstreamForwarder != null) {
                try {
                    upstreamForwarder.upsertMeasureReports(List.of(cityReport));
                } catch (Exception ue) {
                    ourLog.warn("Upstream city MeasureReport forwarding failed (local copy is safe): {}", ue.getMessage());
                }
            }
        } catch (Exception e) {
            ourLog.warn("City MeasureReport aggregation failed: {}", e.getMessage(), e);
        } finally {
            sample.stop(perfMetrics.cityMeasureReportAggregationTimer);
        }
    }

    private List<MeasureReport> loadNeighbourhoodReports(IFhirResourceDao<MeasureReport> dao) {
        SearchParameterMap search = new SearchParameterMap();
        search.setLoadSynchronous(true);
        IBundleProvider provider = dao.search(search);
        List<IBaseResource> all = getAllResources(provider);

        List<MeasureReport> out = new ArrayList<>();
        for (IBaseResource resource : all) {
            if (!(resource instanceof MeasureReport report)) continue;
            if (isNeighbourhoodLayerReport(report)) out.add(report);
        }
        return out;
    }

    private boolean isNeighbourhoodLayerReport(MeasureReport report) {
        if (report == null) return false;
        if (NEIGHBORHOOD_MEASURE.equals(report.getMeasure())) return true;
        for (Identifier id : report.getIdentifier()) {
            if (id != null && NEIGHBORHOOD_IDENTIFIER_SYSTEM.equals(id.getSystem())) return true;
        }
        return false;
    }

    private MeasureReport upsertCityReport(IFhirResourceDao<MeasureReport> dao, CityAccumulator acc) {
        if (acc.neighCount == 0) return null;

        String idPart = "city-health-aggregation-" + slug(acc.city);
        MeasureReport report = new MeasureReport();
        report.setId("MeasureReport/" + idPart);
        report.getIdentifierFirstRep()
            .setSystem(CITY_IDENTIFIER_SYSTEM)
            .setValue(acc.city + "-city-health-aggregation");

        report.setStatus(MeasureReport.MeasureReportStatus.COMPLETE);
        report.setType(MeasureReport.MeasureReportType.SUMMARY);
        report.setMeasure(CITY_MEASURE);

        Period period = new Period();
        if (acc.minStart != null) period.setStart(acc.minStart);
        if (acc.maxEnd   != null) period.setEnd(acc.maxEnd);
        report.setPeriod(period);

        Extension location = new Extension().setUrl(LOCATION_EXTENSION_URL);
        location.addExtension(new Extension()
            .setUrl(CITY_URL)
            .setValue(new StringType(acc.city)));
        report.addExtension(location);

        MeasureReport.MeasureReportGroupComponent group = report.addGroup();
        group.getCode().setText("City Summary");

        addNumericStratifier(group, STRAT_AVERAGE_INCOME,  "Income",             average(acc.sumIncome, acc.incomeCount), "BRL/month");
        addNumericStratifier(group, STRAT_CARE_UNITS,      "Primary Care Units", acc.sumCareUnits,                        "units");
        addNumericStratifier(group, STRAT_MEAN_AGE,        "Age",                average(acc.sumAge, acc.ageCount),        "years");
        addNumericStratifier(group, STRAT_MALE_PATIENTS,   "Male",               acc.sumMalePatients,                     "patients");
        addNumericStratifier(group, STRAT_FEMALE_PATIENTS, "Female",             acc.sumFemalePatients,                   "patients");
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
        String stratifierText, String valueText,
        BigDecimal value, String unit
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
        if (provider == null) return List.of();
        Integer size = provider.size();
        if (size == null || size < 0) return provider.getAllResources();
        List<IBaseResource> all = new ArrayList<>(size);
        int pageSize = 500;
        for (int from = 0; from < size; from += pageSize) {
            int to = Math.min(from + pageSize, size);
            List<IBaseResource> page = provider.getResources(from, to);
            if (page == null || page.isEmpty()) break;
            all.addAll(page);
        }
        return all;
    }

    private BigDecimal average(BigDecimal sum, int count) {
        if (sum == null || count <= 0) return BigDecimal.ZERO;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private String slug(String input) {
        String normalized = normalize(input);
        if (normalized == null) return "unknown";
        return normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    }

    private String normalize(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class CityAccumulator {
        final String city;
        int neighCount;
        BigDecimal sumIncome = BigDecimal.ZERO;
        int incomeCount;
        BigDecimal sumAge = BigDecimal.ZERO;
        int ageCount;
        BigDecimal sumMalePatients   = BigDecimal.ZERO;
        BigDecimal sumFemalePatients = BigDecimal.ZERO;
        BigDecimal sumCareUnits      = BigDecimal.ZERO;
        Date minStart;
        Date maxEnd;

        CityAccumulator(String city) {
            this.city = city;
        }

        void accept(MeasureReport report) {
            neighCount++;

            BigDecimal income = extract(report, STRAT_AVERAGE_INCOME);
            if (income != null) { sumIncome = sumIncome.add(income); incomeCount++; }

            BigDecimal age = extract(report, STRAT_MEAN_AGE);
            if (age != null) { sumAge = sumAge.add(age); ageCount++; }

            BigDecimal male = extract(report, STRAT_MALE_PATIENTS);
            if (male != null) sumMalePatients = sumMalePatients.add(male);

            BigDecimal female = extract(report, STRAT_FEMALE_PATIENTS);
            if (female != null) sumFemalePatients = sumFemalePatients.add(female);

            BigDecimal careUnits = extract(report, STRAT_CARE_UNITS);
            if (careUnits != null) sumCareUnits = sumCareUnits.add(careUnits);

            if (report.hasPeriod()) {
                Date start = report.getPeriod().getStart();
                Date end   = report.getPeriod().getEnd();
                if (start != null && (minStart == null || start.before(minStart))) minStart = start;
                if (end   != null && (maxEnd   == null || end.after(maxEnd)))      maxEnd   = end;
            }
        }

        private BigDecimal extract(MeasureReport report, String stratifierText) {
            if (report == null || !report.hasGroup()) return null;
            for (MeasureReport.MeasureReportGroupComponent group : report.getGroup()) {
                for (MeasureReport.MeasureReportGroupStratifierComponent stratifier : group.getStratifier()) {
                    String text = null;
                    if (!stratifier.getCode().isEmpty() && stratifier.getCodeFirstRep().hasText()) {
                        text = stratifier.getCodeFirstRep().getText();
                    }
                    if (text == null || !text.equalsIgnoreCase(stratifierText)) continue;
                    if (stratifier.hasStratum()
                        && stratifier.getStratumFirstRep().hasMeasureScore()
                        && stratifier.getStratumFirstRep().getMeasureScore().getValue() != null) {
                        return stratifier.getStratumFirstRep().getMeasureScore().getValue();
                    }
                }
            }
            return null;
        }
    }
}
