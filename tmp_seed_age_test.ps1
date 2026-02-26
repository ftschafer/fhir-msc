$ErrorActionPreference = 'Stop'

$bundle = @'
{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    {
      "resource": {
        "resourceType": "Patient",
        "id": "test-age-p1",
        "name": [{"family":"AgeTest","given":["Alice"]}],
        "gender": "female",
        "birthDate": "1988-05-10",
        "extension": [
          {
            "url": "http://patient-location",
            "extension": [{"url":"block","valueString":"B71"}]
          },
          {"url":"http://news2-score","valueInteger":6}
        ]
      },
      "request": {"method": "PUT", "url": "Patient/test-age-p1"}
    },
    {
      "resource": {
        "resourceType": "Patient",
        "id": "test-age-p2",
        "name": [{"family":"AgeTest","given":["Bob"]}],
        "gender": "male",
        "birthDate": "1975-09-22",
        "extension": [
          {
            "url": "http://patient-location",
            "extension": [{"url":"block","valueString":"B71"}]
          },
          {"url":"http://news2-score","valueInteger":4}
        ]
      },
      "request": {"method": "PUT", "url": "Patient/test-age-p2"}
    },
    {
      "resource": {
        "resourceType": "Observation",
        "status": "final",
        "category": [{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs"}]}],
        "code": {"coding":[{"system":"http://loinc.org","code":"8867-4","display":"Heart rate"}]},
        "subject": {"reference":"Patient/test-age-p1"},
        "effectiveDateTime": "2026-02-25T22:00:00Z",
        "valueQuantity": {"value": 88, "unit": "/min", "system": "http://unitsofmeasure.org", "code": "/min"}
      },
      "request": {"method": "POST", "url": "Observation"}
    },
    {
      "resource": {
        "resourceType": "Observation",
        "status": "final",
        "category": [{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs"}]}],
        "code": {"coding":[{"system":"http://loinc.org","code":"8867-4","display":"Heart rate"}]},
        "subject": {"reference":"Patient/test-age-p2"},
        "effectiveDateTime": "2026-02-25T22:01:00Z",
        "valueQuantity": {"value": 92, "unit": "/min", "system": "http://unitsofmeasure.org", "code": "/min"}
      },
      "request": {"method": "POST", "url": "Observation"}
    }
  ]
}
'@

$tx = Invoke-RestMethod -Method Post -Uri 'http://localhost:8071/fhir' -ContentType 'application/fhir+json' -Body $bundle
Write-Host "Posted transaction entries:" $tx.entry.Count

$patients = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/fhir/Patient?_id=test-age-p1,test-age-p2'
Write-Host "Seeded patients found:" $patients.total

$mr = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/fhir/MeasureReport?_count=20'
Write-Host "Current local MeasureReport total:" $mr.total
