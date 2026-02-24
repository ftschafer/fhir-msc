$ErrorActionPreference='Stop'
$base='http://localhost:8071/fhir'

try { Invoke-RestMethod -Uri "$base/metadata" -TimeoutSec 8 | Out-Null } catch { throw "Server not reachable at $base. Start app first." }

$patients = @(
  @{id='b71-km-001'; birth='1952-02-10'; income=1800; vitals=@{'8867-4'=112;'9279-1'=26;'8310-5'=38.8;'59408-5'=90;'8480-6'=92}},
  @{id='b71-km-002'; birth='1955-06-03'; income=2100; vitals=@{'8867-4'=106;'9279-1'=24;'8310-5'=38.5;'59408-5'=91;'8480-6'=95}},
  @{id='b71-km-003'; birth='1960-01-21'; income=2300; vitals=@{'8867-4'=104;'9279-1'=23;'8310-5'=38.2;'59408-5'=92;'8480-6'=98}},
  @{id='b71-km-004'; birth='1984-03-14'; income=4200; vitals=@{'8867-4'=92;'9279-1'=20;'8310-5'=37.7;'59408-5'=95;'8480-6'=116}},
  @{id='b71-km-005'; birth='1987-11-30'; income=4500; vitals=@{'8867-4'=88;'9279-1'=18;'8310-5'=37.2;'59408-5'=97;'8480-6'=120}},
  @{id='b71-km-006'; birth='1990-08-12'; income=4700; vitals=@{'8867-4'=84;'9279-1'=17;'8310-5'=36.9;'59408-5'=98;'8480-6'=118}},
  @{id='b71-km-007'; birth='1972-05-09'; income=3000; vitals=@{'8867-4'=98;'9279-1'=22;'8310-5'=37.9;'59408-5'=94;'8480-6'=108}},
  @{id='b71-km-008'; birth='1975-07-25'; income=3200; vitals=@{'8867-4'=96;'9279-1'=21;'8310-5'=37.6;'59408-5'=95;'8480-6'=110}},
  @{id='b71-km-009'; birth='1978-12-19'; income=3400; vitals=@{'8867-4'=94;'9279-1'=20;'8310-5'=37.4;'59408-5'=96;'8480-6'=112}},
  @{id='b71-km-010'; birth='2001-04-02'; income=5200; vitals=@{'8867-4'=78;'9279-1'=16;'8310-5'=36.8;'59408-5'=99;'8480-6'=114}},
  @{id='b71-km-011'; birth='1998-09-16'; income=5400; vitals=@{'8867-4'=80;'9279-1'=16;'8310-5'=36.7;'59408-5'=99;'8480-6'=112}},
  @{id='b71-km-012'; birth='1996-10-27'; income=5600; vitals=@{'8867-4'=82;'9279-1'=17;'8310-5'=36.9;'59408-5'=98;'8480-6'=116}}
)

$display = @{
  '8867-4'='Heart rate'
  '9279-1'='Respiratory rate'
  '8310-5'='Body temperature'
  '59408-5'='Oxygen saturation'
  '8480-6'='Systolic blood pressure'
}
$unit = @{
  '8867-4'='/min'
  '9279-1'='/min'
  '8310-5'='Cel'
  '59408-5'='%'
  '8480-6'='mm[Hg]'
}

$entries = New-Object System.Collections.ArrayList
$now = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')

foreach($p in $patients){
  $patientResource = @{
    resourceType='Patient'
    id=$p.id
    birthDate=$p.birth
    extension=@(
      @{url='http://patient-location'; extension=@(@{url='block'; valueString='B71'})},
      @{url='http://average-income'; valueDecimal=[double]$p.income}
    )
  }

  [void]$entries.Add(@{request=@{method='PUT'; url="Patient/$($p.id)"}; resource=$patientResource})

  foreach($code in $p.vitals.Keys){
    $obs = @{
      resourceType='Observation'
      status='final'
      category=@(@{coding=@(@{system='http://terminology.hl7.org/CodeSystem/observation-category'; code='vital-signs'; display='Vital Signs'})})
      code=@{coding=@(@{system='http://loinc.org'; code=$code; display=$display[$code]})}
      subject=@{reference="Patient/$($p.id)"}
      effectiveDateTime=$now
      valueQuantity=@{value=[double]$p.vitals[$code]; unit=$unit[$code]; system='http://unitsofmeasure.org'; code=$unit[$code]}
    }

    [void]$entries.Add(@{request=@{method='POST'; url='Observation'}; resource=$obs})
  }
}

$bundle = @{resourceType='Bundle'; type='transaction'; entry=$entries}
$body = $bundle | ConvertTo-Json -Depth 20

$resp = Invoke-RestMethod -Uri $base -Method Post -ContentType 'application/fhir+json' -Body $body -TimeoutSec 60
Write-Output "POSTED_ENTRIES=$($entries.Count)"
Write-Output "TX_RESPONSE_ENTRIES=$($resp.entry.Count)"

Start-Sleep -Seconds 2

$stats = Invoke-RestMethod -Uri "$base/Patient/%24dashboard-stats?block=B71" -TimeoutSec 20
Write-Output "AVG_NEWS2=$($stats.avgNews2Score)"
Write-Output "ANALYSIS_SAMPLE=$($stats.socioeconomicAnalysis.sampleSize)"
Write-Output "ANALYSIS_CLUSTERS=$($stats.socioeconomicAnalysis.clusterCount)"
$stats.socioeconomicAnalysis.clusterProfiles | ConvertTo-Json -Depth 6
