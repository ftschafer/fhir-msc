<#
.SYNOPSIS
  Seeds a neutral cloud of patients intended to keep the dashboard unclustered.

.DESCRIPTION
  This script creates 300 patients with overlapping random values centered on a
  single clinical profile. The variables used by K-means (heart rate, systolic
  blood pressure, age, and active conditions) are deliberately kept close
  together so the dashboard should fall back to the low-confidence, neutral
  rendering instead of showing colored clusters.

  The seeded patients are left in place so you can open the dashboard and take
  a screenshot. Old km-uncorr-* and km-corr-* test patients are removed first
  to avoid contamination from previous runs.

.PARAMETER BaseUrl
  FHIR server base URL (default: http://localhost:8071/fhir)

.PARAMETER Block
  Block identifier (default: B71)

.PARAMETER PatientCount
  Number of neutral patients to seed (default: 300)

.PARAMETER CleanupFirst
  Retained only for backward compatibility. Old test prefixes are always removed.

.PARAMETER CleanupAfter
  If set, deletes the seeded neutral patients after printing the result summary

.PARAMETER SkipCorrelatedPhase
  Retained only for backward compatibility. It has no effect.
#>
param(
  [string]$BaseUrl = 'http://localhost:8071/fhir',
  [string]$Block   = 'B71',
  [int]$PatientCount = 300,
  [switch]$CleanupFirst,
  [switch]$CleanupAfter,
  [switch]$SkipCorrelatedPhase
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# Connectivity check
try {
  Invoke-RestMethod -Uri "$BaseUrl/metadata" -TimeoutSec 10 | Out-Null
} catch {
  throw "Server not reachable at $BaseUrl. Start the application first."
}

$loincDisplay = @{
  '8867-4'  = 'Heart rate';      '9279-1'  = 'Respiratory rate'
  '8310-5'  = 'Body temperature'; '59408-5' = 'Oxygen saturation'
  '8480-6'  = 'Systolic blood pressure'
}
$loincUnit = @{
  '8867-4'  = '/min';   '9279-1'  = '/min'
  '8310-5'  = 'Cel';    '59408-5' = '%'
  '8480-6'  = 'mm[Hg]'
}
$conditionLabels = @('Hypertension','Diabetes','COPD','Heart Failure','CKD')
$rng = [System.Random]::new()

$pass = 0; $fail = 0; $warn = 0

function Assert-Test([string]$name, [bool]$condition, [string]$detail) {
  if ($condition) {
    Write-Host "  [PASS] $name" -ForegroundColor Green
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
    $script:pass++
  } else {
    Write-Host "  [FAIL] $name" -ForegroundColor Red
    if ($detail) { Write-Host "         $detail" -ForegroundColor Yellow }
    $script:fail++
  }
}

function Warn-Test([string]$name, [string]$detail) {
  Write-Host "  [WARN] $name" -ForegroundColor Yellow
  if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
  $script:warn++
}

function Get-NormalLikeValue([double]$mean, [double]$stdDev, [double]$minimum, [double]$maximum, [int]$digits) {
  $z = 0.0
  for ($sample = 0; $sample -lt 12; $sample++) {
    $z += $script:rng.NextDouble()
  }
  $z -= 6.0

  $value = $mean + ($stdDev * $z)
  if ($value -lt $minimum) { $value = $minimum }
  if ($value -gt $maximum) { $value = $maximum }

  return [Math]::Round($value, $digits)
}

# Batch poster
function Post-PatientBatch {
  param(
    [array]$Patients,
    [string]$Label
  )

  $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
  $batchEntries = New-Object System.Collections.ArrayList
  $batchNum = 0
  $total = 0

  foreach ($p in $Patients) {
    # Patient
    $patResource = @{
      resourceType = 'Patient'
      id           = $p.id
      name         = @(@{ family = $p.family; given = @($p.given) })
      gender       = $p.gender
      birthDate    = $p.birthDate
      extension    = @(
        @{ url = 'http://patient-location'; extension = @(@{ url = 'block'; valueString = $Block }) },
        @{ url = 'http://news2-score'; valueInteger = [int]$p.news2 }
      )
    }
    [void]$batchEntries.Add(@{ request = @{ method = 'PUT'; url = "Patient/$($p.id)" }; resource = $patResource })

    # Observations
    foreach ($code in $p.vitals.Keys) {
      $obs = @{
        resourceType      = 'Observation'
        status            = 'final'
        category          = @(@{ coding = @(@{
          system  = 'http://terminology.hl7.org/CodeSystem/observation-category'
          code    = 'vital-signs'
          display = 'Vital Signs'
        })})
        code              = @{ coding = @(@{
          system  = 'http://loinc.org'; code = $code; display = $loincDisplay[$code]
        })}
        subject           = @{ reference = "Patient/$($p.id)" }
        effectiveDateTime = $now
        valueQuantity     = @{
          value  = [double]$p.vitals[$code]
          unit   = $loincUnit[$code]
          system = 'http://unitsofmeasure.org'
          code   = $loincUnit[$code]
        }
      }
      [void]$batchEntries.Add(@{ request = @{ method = 'POST'; url = 'Observation' }; resource = $obs })
    }

    # Conditions
    for ($c = 1; $c -le [int]$p.conditions; $c++) {
      $condId = "cond-$($p.id)-$c"
      $cond = @{
        resourceType       = 'Condition'
        id                 = $condId
        clinicalStatus     = @{ coding = @(@{
          system = 'http://terminology.hl7.org/CodeSystem/condition-clinical'
          code   = 'active'; display = 'Active'
        })}
        verificationStatus = @{ coding = @(@{
          system = 'http://terminology.hl7.org/CodeSystem/condition-ver-status'
          code   = 'confirmed'; display = 'Confirmed'
        })}
        subject            = @{ reference = "Patient/$($p.id)" }
        code               = @{ text = $conditionLabels[($c - 1) % $conditionLabels.Count] }
      }
      [void]$batchEntries.Add(@{ request = @{ method = 'PUT'; url = "Condition/$condId" }; resource = $cond })
    }

    if ($batchEntries.Count -ge 50) {
      $bundle = @{ resourceType = 'Bundle'; type = 'transaction'; entry = [array]$batchEntries }
      $body = $bundle | ConvertTo-Json -Depth 20 -Compress
      $resp = Invoke-RestMethod -Uri $BaseUrl -Method Post -ContentType 'application/fhir+json' -Body $body -TimeoutSec 120
      $batchNum++
      $total += $batchEntries.Count
      Write-Host "  [$Label] Batch $batchNum : $($batchEntries.Count) entries" -ForegroundColor DarkGray
      $batchEntries.Clear()
    }
  }

  if ($batchEntries.Count -gt 0) {
    $bundle = @{ resourceType = 'Bundle'; type = 'transaction'; entry = [array]$batchEntries }
    $body = $bundle | ConvertTo-Json -Depth 20 -Compress
    $resp = Invoke-RestMethod -Uri $BaseUrl -Method Post -ContentType 'application/fhir+json' -Body $body -TimeoutSec 120
    $batchNum++
    $total += $batchEntries.Count
    Write-Host "  [$Label] Batch $batchNum : $($batchEntries.Count) entries" -ForegroundColor DarkGray
  }

  Write-Host "  [$Label] Total: $total entries in $batchNum batches"
}

# Cleanup helper
function Remove-TestPatients([string]$prefix, [int]$count) {
  Write-Host "  Removing $prefix-* resources..."
  for ($i = 1; $i -le $count; $i++) {
    $id = '{0}-{1:D3}' -f $prefix, $i
    try { Invoke-RestMethod -Method Delete -Uri "$BaseUrl/Patient/$id" -TimeoutSec 5 | Out-Null } catch {}
    for ($c = 1; $c -le 5; $c++) {
      try { Invoke-RestMethod -Method Delete -Uri "$BaseUrl/Condition/cond-$id-$c" -TimeoutSec 5 | Out-Null } catch {}
    }
  }
}

Write-Host ''
Write-Host '============================================================='
Write-Host ' NEUTRAL NOISE DATA FOR DASHBOARD SCREENSHOT'
Write-Host '============================================================='
Write-Host ''

Write-Host 'Removing previous km-uncorr/km-corr test patients...' -ForegroundColor DarkGray
Remove-TestPatients 'km-uncorr' $PatientCount
Remove-TestPatients 'km-corr' $PatientCount

# Generate random patient data while keeping clustering features fixed.
# This reliably prevents meaningful separation in K-means.
$givenNames = @('Ana','Bruno','Clara','Diego','Eva','Fabio','Gabi','Hugo',
                'Iara','Joao','Karen','Leo','Maya','Nico','Olga','Pablo',
                'Quinn','Rafa','Sara','Tomas','Ursula','Vitor','Wanda',
                'Xavi','Yara','Zeca','Davi','Elisa','Fiona','Gil')

$uncorrPatients = @()
for ($i = 1; $i -le $PatientCount; $i++) {
  $age    = 52
  $birth  = '1974-01-01'
  $gender = if ($script:rng.NextDouble() -lt 0.5) { 'male' } else { 'female' }
  $hr     = 88.0
  $rr     = Get-NormalLikeValue 18 1.4 15 22 1
  $tmp    = Get-NormalLikeValue 36.9 0.12 36.6 37.2 2
  $o2     = Get-NormalLikeValue 97.1 0.7 95.5 99.0 1
  $bp     = 118.0
  $nCond  = 2
  $news2  = [int](Get-NormalLikeValue 3 0.8 1 5 0)

  $uncorrPatients += @{
    id         = 'km-uncorr-{0:D3}' -f $i
    family     = 'KmNoise'
    given      = $givenNames[($i - 1) % $givenNames.Count]
    gender     = $gender
    birthDate  = $birth
    news2      = $news2
    conditions = $nCond
    vitals     = @{
      '8867-4'  = $hr
      '9279-1'  = $rr
      '8310-5'  = $tmp
      '59408-5' = $o2
      '8480-6'  = $bp
    }
  }
}

Write-Host "Seeding $PatientCount neutral patients..."
Post-PatientBatch -Patients $uncorrPatients -Label 'NOISE'

Write-Host 'Waiting 5s for aggregation...'
Start-Sleep -Seconds 5

# Validate: clustering should be LOW quality on noise
Write-Host ''
Write-Host '--- NOISE VALIDATION ----------------------------------------'

$stats = Invoke-RestMethod -Uri "$BaseUrl/Patient/`$dashboard-stats?block=$Block" -TimeoutSec 30
$analysis = $stats.socioeconomicAnalysis
$quality  = $analysis.quality
$profiles = $analysis.clusterProfiles

$noiseSil = [double]$quality.silhouetteScore
$noiseLow = $quality.lowConfidence
$noiseK   = [int]$analysis.clusterCount

Write-Host "  Noise clustering: K=$noiseK, silhouette=$noiseSil, lowConfidence=$noiseLow" -ForegroundColor Cyan

$neutralDashboard = ($noiseLow -eq $true) -or ($noiseSil -lt 0.20) -or ($noiseK -lt 2)

Assert-Test 'Dashboard should remain neutral for this dataset' $neutralDashboard "K=$noiseK, silhouette=$noiseSil, lowConfidence=$noiseLow"

if ($profiles.Count -ge 2) {
  $sortedByAge = $profiles | Sort-Object { [double]$_.avgAge }
  $ageDiffNoise  = [Math]::Abs([double]$sortedByAge[-1].avgAge - [double]$sortedByAge[0].avgAge)
  $hrDiffNoise   = [Math]::Abs([double]$sortedByAge[-1].avgHeartRate - [double]$sortedByAge[0].avgHeartRate)
  $condDiffNoise = [Math]::Abs([double]$sortedByAge[-1].avgConditions - [double]$sortedByAge[0].avgConditions)
  Write-Host "  Separation check: ageDiff=$ageDiffNoise, hrDiff=$hrDiffNoise, condDiff=$condDiffNoise" -ForegroundColor Cyan
}

Write-Host ''
Write-Host 'Dashboard expectation:' -ForegroundColor Cyan
Write-Host '  - scatter plots should show a single gray cloud'
Write-Host '  - sex by cluster should be hidden'
Write-Host '  - cluster table should say no reliable cluster analysis'

if ($CleanupAfter) {
  Write-Host ''
  Write-Host 'Cleaning seeded neutral patients...' -ForegroundColor DarkGray
  Remove-TestPatients 'km-uncorr' $PatientCount
}

Write-Host ''
Write-Host '============================================================='
Write-Host ' FINAL RESULTS'
Write-Host '============================================================='
Write-Host "  PASS: $pass | FAIL: $fail | WARN: $warn"
Write-Host "  Neutral silhouette: $noiseSil"
Write-Host "  Low confidence:     $noiseLow"
Write-Host "  Selected clusters:  $noiseK"
if ($fail -eq 0) {
  Write-Host '  STATUS: neutral dataset seeded and kept for dashboard viewing' -ForegroundColor Green
} else {
  Write-Host "  STATUS: neutral dataset seeded, but clustering still looks too strong" -ForegroundColor Red
}
Write-Host '============================================================='
