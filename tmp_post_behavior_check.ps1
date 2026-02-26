$ErrorActionPreference = 'Stop'

function Get-ReportSummary {
  param([string]$Id = 'block-health-aggregation-b71')

  $mr = Invoke-RestMethod -Method Get -Uri ("http://localhost:8071/fhir/MeasureReport/" + $Id)

  $conditionCount = $null
  $meanNews2 = $null
  $meanAge = $null

  if ($mr.group -and $mr.group.Count -gt 0) {
    $g = $mr.group[0]
    if ($g.population -and $g.population.Count -gt 0) { $conditionCount = $g.population[0].count }
    if ($g.measureScore) { $meanNews2 = $g.measureScore.value }

    if ($g.stratifier) {
      foreach ($s in $g.stratifier) {
        $codeText = $null
        if ($s.code -and $s.code.Count -gt 0) { $codeText = $s.code[0].text }
        if ($codeText -eq 'Mean Age' -and $s.stratum -and $s.stratum.Count -gt 0 -and $s.stratum[0].measureScore) {
          $meanAge = $s.stratum[0].measureScore.value
        }
      }
    }
  }

  [pscustomobject]@{
    id = $mr.id
    lastUpdated = $mr.meta.lastUpdated
    conditionCount = $conditionCount
    meanNews2 = $meanNews2
    meanAge = $meanAge
  }
}

$health = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/actuator/health' -TimeoutSec 8
if ($health.status -ne 'UP') { throw "Server not UP: $($health.status)" }

$before = Get-ReportSummary
Write-Host "BEFORE => id=$($before.id) updated=$($before.lastUpdated) conditions=$($before.conditionCount) news2=$($before.meanNews2) meanAge=$($before.meanAge)"

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

$cond = @{
  resourceType='Condition';
  clinicalStatus=@{coding=@(@{system='http://terminology.hl7.org/CodeSystem/condition-clinical'; code='active'})};
  verificationStatus=@{coding=@(@{system='http://terminology.hl7.org/CodeSystem/condition-ver-status'; code='confirmed'})};
  code=@{text='Behavior test condition'};
  subject=@{reference=("Patient/" + $p1)};
  recordedDate=(Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Method Post -Uri 'http://localhost:8071/fhir/Condition' -ContentType 'application/fhir+json' -Body $cond | Out-Null

Start-Sleep -Seconds 3

$after = Get-ReportSummary
Write-Host "AFTER  => id=$($after.id) updated=$($after.lastUpdated) conditions=$($after.conditionCount) news2=$($after.meanNews2) meanAge=$($after.meanAge)"
Write-Host ("POSTED patients: " + $p1 + ", " + $p2)
