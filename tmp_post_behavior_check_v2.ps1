$ErrorActionPreference = 'Stop'

function Get-LatestReportSummary {
  $bundle = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/fhir/MeasureReport?_count=1'
  if ([int]$bundle.total -lt 1 -or -not $bundle.entry -or $bundle.entry.Count -lt 1) {
    return [pscustomobject]@{ exists = $false; id = $null; lastUpdated = $null; meanAge = $null }
  }

  $mr = $bundle.entry[0].resource
  $meanAge = $null
  if ($mr.group -and $mr.group.Count -gt 0 -and $mr.group[0].stratifier) {
    foreach ($s in $mr.group[0].stratifier) {
      if ($s.code -and $s.code.Count -gt 0 -and $s.code[0].text -eq 'Mean Age') {
        if ($s.stratum -and $s.stratum.Count -gt 0 -and $s.stratum[0].measureScore) {
          $meanAge = $s.stratum[0].measureScore.value
        }
      }
    }
  }

  return [pscustomobject]@{
    exists = $true
    id = $mr.id
    lastUpdated = $mr.meta.lastUpdated
    meanAge = $meanAge
  }
}

$health = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/actuator/health' -TimeoutSec 8
if ($health.status -ne 'UP') { throw "Server not UP: $($health.status)" }

$before = Get-LatestReportSummary
Write-Host "BEFORE => exists=$($before.exists) id=$($before.id) updated=$($before.lastUpdated) meanAge=$($before.meanAge)"

$suffix = [guid]::NewGuid().ToString('N').Substring(0,6)
$p1 = "beh-p1-$suffix"
$p2 = "beh-p2-$suffix"

$pat1 = @{
  resourceType='Patient'; id=$p1; name=@(@{family='Behavior';given=@('One')}); gender='female'; birthDate='1980-04-10';
  extension=@(
    @{url='http://patient-location'; extension=@(@{url='block'; valueString='B71'})},
    @{url='http://news2-score'; valueInteger=7}
  )
} | ConvertTo-Json -Depth 10

$pat2 = @{
  resourceType='Patient'; id=$p2; name=@(@{family='Behavior';given=@('Two')}); gender='male'; birthDate='2000-08-20';
  extension=@(
    @{url='http://patient-location'; extension=@(@{url='block'; valueString='B71'})},
    @{url='http://news2-score'; valueInteger=3}
  )
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Put -Uri ("http://localhost:8071/fhir/Patient/" + $p1) -ContentType 'application/fhir+json' -Body $pat1 | Out-Null
Invoke-RestMethod -Method Put -Uri ("http://localhost:8071/fhir/Patient/" + $p2) -ContentType 'application/fhir+json' -Body $pat2 | Out-Null

$obs1 = @{
  resourceType='Observation'; status='final';
  category=@(@{coding=@(@{system='http://terminology.hl7.org/CodeSystem/observation-category'; code='vital-signs'})});
  code=@{coding=@(@{system='http://loinc.org'; code='8867-4'; display='Heart rate'})};
  subject=@{reference=("Patient/" + $p1)};
  effectiveDateTime=(Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ");
  valueQuantity=@{value=90; unit='/min'; system='http://unitsofmeasure.org'; code='/min'};
  extension=@(@{url='http://news2-score'; valueInteger=7})
} | ConvertTo-Json -Depth 10

$obs2 = @{
  resourceType='Observation'; status='final';
  category=@(@{coding=@(@{system='http://terminology.hl7.org/CodeSystem/observation-category'; code='vital-signs'})});
  code=@{coding=@(@{system='http://loinc.org'; code='9279-1'; display='Respiratory rate'})};
  subject=@{reference=("Patient/" + $p2)};
  effectiveDateTime=(Get-Date).ToUniversalTime().AddSeconds(1).ToString("yyyy-MM-ddTHH:mm:ssZ");
  valueQuantity=@{value=18; unit='/min'; system='http://unitsofmeasure.org'; code='/min'};
  extension=@(@{url='http://news2-score'; valueInteger=3})
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Post -Uri 'http://localhost:8071/fhir/Observation' -ContentType 'application/fhir+json' -Body $obs1 | Out-Null
Invoke-RestMethod -Method Post -Uri 'http://localhost:8071/fhir/Observation' -ContentType 'application/fhir+json' -Body $obs2 | Out-Null

Start-Sleep -Seconds 3

$after = Get-LatestReportSummary
Write-Host "AFTER  => exists=$($after.exists) id=$($after.id) updated=$($after.lastUpdated) meanAge=$($after.meanAge)"
Write-Host ("POSTED patients: " + $p1 + ", " + $p2)
