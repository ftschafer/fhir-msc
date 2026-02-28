param(
  [string]$BaseUrl = 'http://localhost:8071/fhir',
  [string]$Block = 'B71',
  [switch]$CleanupExisting
)

$ErrorActionPreference = 'Stop'

function New-PatientPayload {
  param(
    [string]$Id,
    [string]$Family,
    [string]$Given,
    [string]$Gender,
    [string]$BirthDate,
    [int]$News2,
    [string]$BlockValue
  )

  return @{
    resourceType = 'Patient'
    id = $Id
    name = @(@{ family = $Family; given = @($Given) })
    gender = $Gender
    birthDate = $BirthDate
    extension = @(
      @{ url = 'http://patient-location'; extension = @(@{ url = 'block'; valueString = $BlockValue }) },
      @{ url = 'http://news2-score'; valueInteger = $News2 }
    )
  } | ConvertTo-Json -Depth 12
}

function New-ActiveConditionPayload {
  param(
    [string]$ConditionId,
    [string]$PatientId,
    [string]$Display
  )

  return @{
    resourceType = 'Condition'
    id = $ConditionId
    clinicalStatus = @{
      coding = @(@{
        system = 'http://terminology.hl7.org/CodeSystem/condition-clinical'
        code = 'active'
        display = 'Active'
      })
    }
    verificationStatus = @{
      coding = @(@{
        system = 'http://terminology.hl7.org/CodeSystem/condition-ver-status'
        code = 'confirmed'
        display = 'Confirmed'
      })
    }
    subject = @{ reference = "Patient/$PatientId" }
    code = @{ text = $Display }
  } | ConvertTo-Json -Depth 12
}

function Invoke-UpsertPatient {
  param($Patient)
  $payload = New-PatientPayload -Id $Patient.id -Family $Patient.family -Given $Patient.given -Gender $Patient.gender -BirthDate $Patient.birthDate -News2 $Patient.news2 -BlockValue $Block
  Invoke-RestMethod -Method Put -Uri "$BaseUrl/Patient/$($Patient.id)" -ContentType 'application/fhir+json' -Body $payload | Out-Null
}

function Invoke-UpsertConditions {
  param($PatientId, [int]$Count)

  $labels = @('Hypertension','Diabetes','COPD','Heart Failure','CKD')
  for ($i = 1; $i -le $Count; $i++) {
    $conditionId = "cond-$PatientId-$i"
    $display = $labels[($i - 1) % $labels.Count]
    $payload = New-ActiveConditionPayload -ConditionId $conditionId -PatientId $PatientId -Display $display
    Invoke-RestMethod -Method Put -Uri "$BaseUrl/Condition/$conditionId" -ContentType 'application/fhir+json' -Body $payload | Out-Null
  }
}

$patients = @(
  # Cluster H: higher NEWS2 + older + more conditions (balanced sex)
  @{ id='kmc-h1'; family='KMH'; given='Ana';   gender='female'; birthDate='1949-01-10'; news2=8; cond=3 },
  @{ id='kmc-h2'; family='KMH'; given='Bruno'; gender='male';   birthDate='1951-02-15'; news2=9; cond=4 },
  @{ id='kmc-h3'; family='KMH'; given='Clara'; gender='female'; birthDate='1954-03-20'; news2=7; cond=3 },
  @{ id='kmc-h4'; family='KMH'; given='Diego'; gender='male';   birthDate='1950-04-25'; news2=8; cond=4 },
  @{ id='kmc-h5'; family='KMH'; given='Eva';   gender='female'; birthDate='1947-05-30'; news2=9; cond=5 },
  @{ id='kmc-h6'; family='KMH'; given='Fabio'; gender='male';   birthDate='1953-06-14'; news2=7; cond=3 },

  # Cluster M: medium NEWS2 + adult + medium conditions (balanced sex)
  @{ id='kmc-m1'; family='KMM'; given='Gabi';  gender='female'; birthDate='1982-07-12'; news2=5; cond=2 },
  @{ id='kmc-m2'; family='KMM'; given='Hugo';  gender='male';   birthDate='1986-08-09'; news2=6; cond=2 },
  @{ id='kmc-m3'; family='KMM'; given='Iara';  gender='female'; birthDate='1979-09-16'; news2=5; cond=1 },
  @{ id='kmc-m4'; family='KMM'; given='Joao';  gender='male';   birthDate='1988-10-22'; news2=6; cond=2 },
  @{ id='kmc-m5'; family='KMM'; given='Karen'; gender='female'; birthDate='1984-11-03'; news2=5; cond=1 },
  @{ id='kmc-m6'; family='KMM'; given='Leo';   gender='male';   birthDate='1981-12-28'; news2=6; cond=2 },

  # Cluster L: low NEWS2 + younger + low conditions (balanced sex)
  @{ id='kmc-l1'; family='KML'; given='Maya';  gender='female'; birthDate='2002-01-04'; news2=1; cond=0 },
  @{ id='kmc-l2'; family='KML'; given='Nico';  gender='male';   birthDate='2001-02-18'; news2=2; cond=0 },
  @{ id='kmc-l3'; family='KML'; given='Olga';  gender='female'; birthDate='2004-03-09'; news2=1; cond=1 },
  @{ id='kmc-l4'; family='KML'; given='Pablo'; gender='male';   birthDate='2003-04-27'; news2=2; cond=0 },
  @{ id='kmc-l5'; family='KML'; given='Quinn'; gender='female'; birthDate='2005-05-13'; news2=0; cond=0 },
  @{ id='kmc-l6'; family='KML'; given='Rafa';  gender='male';   birthDate='2000-06-30'; news2=2; cond=1 }
)

if ($CleanupExisting) {
  Write-Host 'Cleaning previous kmc-* patients and conditions...'
  foreach ($p in $patients) {
    try { Invoke-RestMethod -Method Delete -Uri "$BaseUrl/Patient/$($p.id)" | Out-Null } catch {}
    for ($i=1; $i -le 6; $i++) {
      try { Invoke-RestMethod -Method Delete -Uri "$BaseUrl/Condition/cond-$($p.id)-$i" | Out-Null } catch {}
    }
  }
}

Write-Host "Seeding clinical K-Means test data to $BaseUrl (block=$Block)..."
foreach ($p in $patients) {
  Invoke-UpsertPatient -Patient $p
  Invoke-UpsertConditions -PatientId $p.id -Count ([int]$p.cond)
  Write-Host "UPSERTED $($p.id) | sex=$($p.gender) | news2=$($p.news2) | cond=$($p.cond)"
}

Write-Host 'Done. You can now refresh dashboard and inspect cluster separation + male/female columns.'
Write-Host "Tip: Use block filter $Block for this seeded set."
