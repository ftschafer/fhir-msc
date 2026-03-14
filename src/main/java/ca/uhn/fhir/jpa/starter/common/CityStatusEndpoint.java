package ca.uhn.fhir.jpa.starter.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

@Component
@Endpoint(id = "city-status")
public class CityStatusEndpoint {

    private static final String BLOCK_SYSTEM        = "urn:block:health-aggregation";
    private static final String NEIGHBOURHOOD_SYSTEM = "urn:neighborhood:health-aggregation";
    private static final String CITY_SYSTEM          = "urn:city:health-aggregation";

    @Value("${location.city}")
    private String configuredCity;

    @Value("${upstream.fhir.base-url:http://localhost:8083/fhir}")
    private String upstreamUrl;

    @Value("${aggregation.neighborhood.measure-report.ms:5000}")
    private String neighAggMs;

    @Value("${aggregation.city.measure-report.ms:10000}")
    private String cityAggMs;

    private final DaoRegistry daoRegistry;

    public CityStatusEndpoint(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", Instant.now().toString());
        out.put("city", configuredCity);
        out.put("upstream", upstreamUrl);

        // ── Resource counts ───────────────────────────────────────────────────
        Map<String, Object> resources = new LinkedHashMap<>();

        try {
            IFhirResourceDao<Patient> patientDao = daoRegistry.getResourceDao(Patient.class);
            SearchParameterMap pm = new SearchParameterMap();
            pm.setLoadSynchronous(true);
            IBundleProvider results = patientDao.search(pm);
            Integer size = results.size();
            resources.put("patients", size != null && size >= 0 ? size : results.getAllResources().size());
        } catch (Exception e) {
            resources.put("patients", "error: " + e.getMessage());
        }

        Map<String, Object> measureReports = new LinkedHashMap<>();
        try {
            IFhirResourceDao<MeasureReport> mrDao = daoRegistry.getResourceDao(MeasureReport.class);
            SearchParameterMap mrSearch = new SearchParameterMap();
            mrSearch.setLoadSynchronous(true);
            IBundleProvider mrResults = mrDao.search(mrSearch);
            List<IBaseResource> all = mrResults.size() != null && mrResults.size() >= 0
                    ? mrResults.getResources(0, mrResults.size())
                    : mrResults.getAllResources();

            int blockCount = 0, neighCount = 0, cityCount = 0, otherCount = 0;
            for (IBaseResource res : all) {
                if (!(res instanceof MeasureReport mr)) continue;
                String system = firstIdentifierSystem(mr.getIdentifier());
                if (BLOCK_SYSTEM.equals(system))        blockCount++;
                else if (NEIGHBOURHOOD_SYSTEM.equals(system)) neighCount++;
                else if (CITY_SYSTEM.equals(system))    cityCount++;
                else                                    otherCount++;
            }
            measureReports.put("block",        blockCount);
            measureReports.put("neighbourhood", neighCount);
            measureReports.put("city",          cityCount);
            if (otherCount > 0) measureReports.put("other", otherCount);
        } catch (Exception e) {
            measureReports.put("error", e.getMessage());
        }
        resources.put("measureReports", measureReports);
        out.put("resources", resources);

        // ── Pipeline config ───────────────────────────────────────────────────
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("blockToNeighbourhoodMs",  parseMs(neighAggMs));
        pipeline.put("neighbourhoodToCityMs",    parseMs(cityAggMs));
        out.put("pipeline", pipeline);

        return out;
    }

    private String firstIdentifierSystem(List<Identifier> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) return null;
        for (Identifier id : identifiers) {
            if (id != null && id.getSystem() != null) return id.getSystem();
        }
        return null;
    }

    private long parseMs(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return -1; }
    }
}
