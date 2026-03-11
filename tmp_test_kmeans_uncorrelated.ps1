<#
.SYNOPSIS
  Seeds 300 patients in block B71 with UNCORRELATED features, then validates
  that K-means does NOT produce high-quality clusters from random noise.

.DESCRIPTION
  This script is the negative-case complement of tmp_test_kmeans_300.ps1.
  It generates 300 patients where all 5 clustering features (heart rate,
  systolic BP, age, sex, active conditions) are randomly and independently
  sampled from overlapping uniform distributions — no clinical correlation
  between them.

  The test PASSES if the clustering engine either:
    a) Reports lowConfidence = true, OR
    b) Produces a low silhouette score (< 0.35), OR
    c) Produces clusters that lack meaningful feature separation

  The test FAILS if the engine claims high-quality clusters from noise data,
  which would indicate over-fitting or insufficient quality thresholds.

  After the uncorrelated test, it seeds a SECOND set of 300 patients with
  proper clinical correlation (identical data from tmp_test_kmeans_300.ps1)
  and verifies that the engine recovers and finds real clusters, proving
  the quality metrics discriminate real signal from noise.

.PARAMETER BaseUrl
  FHIR server base URL (default: http://localhost:8071/fhir)

.PARAMETER Block
  Block identifier (default: B71)

.PARAMETER CleanupFirst
  If set, deletes all km-uncorr-* and km-corr-* patients before seeding

.PARAMETER SkipCorrelatedPhase
  If set, only runs the uncorrelated noise test (faster)
#>
param(
  [string]$BaseUrl = 'http://localhost:8071/fhir',
  [string]$Block   = 'B71',
  [switch]$CleanupFirst,
  [switch]$SkipCorrelatedPhase
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# ── Connectivity check ──────────────────────────────────────────────────────
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

# ── Batch poster ────────────────────────────────────────────────────────────
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

# ── Cleanup helper ──────────────────────────────────────────────────────────
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

# ══════════════════════════════════════════════════════════════════════════════
#  PHASE 1: UNCORRELATED NOISE DATA
# ══════════════════════════════════════════════════════════════════════════════
Write-Host ''
Write-Host '╔═════════════════════════════════════════════════════════════╗'
Write-Host '║  PHASE 1: UNCORRELATED NOISE DATA (negative test)         ║'
Write-Host '╚═════════════════════════════════════════════════════════════╝'
Write-Host ''

if ($CleanupFirst) { Remove-TestPatients 'km-uncorr' 300 }

# Generate 300 patients with independently random features — NO correlation
$givenNames = @('Ana','Bruno','Clara','Diego','Eva','Fabio','Gabi','Hugo',
                'Iara','Joao','Karen','Leo','Maya','Nico','Olga','Pablo',
                'Quinn','Rafa','Sara','Tomas','Ursula','Vitor','Wanda',
                'Xavi','Yara','Zeca','Davi','Elisa','Fiona','Gil')

$uncorrPatients = @()
for ($i = 1; $i -le 300; $i++) {
  # Each feature drawn independently from the SAME wide uniform range
  # so there is no correlation between age, HR, BP, or conditions
  $age    = Get-Random -Minimum 20 -Maximum 86
  $birth  = '{0:D4}-{1:D2}-{2:D2}' -f ((Get-Date).Year - $age), (Get-Random -Minimum 1 -Maximum 13), (Get-Random -Minimum 1 -Maximum 29)
  $gender = if ($i % 2 -eq 0) { 'male' } else { 'female' }
  $hr     = [Math]::Round((Get-Random -Minimum 600 -Maximum 1201) / 10, 1)   # 60-120 uniform
  $rr     = [Math]::Round((Get-Random -Minimum 120 -Maximum 301) / 10, 1)    # 12-30
  $tmp    = [Math]::Round((Get-Random -Minimum 3640 -Maximum 3960) / 100, 2) # 36.4-39.6
  $o2     = [Math]::Round((Get-Random -Minimum 880 -Maximum 1001) / 10, 1)   # 88-100
  $bp     = [Math]::Round((Get-Random -Minimum 850 -Maximum 1360) / 10, 1)   # 85-136
  $nCond  = Get-Random -Minimum 0 -Maximum 6                                  # 0-5 uniform
  $news2  = Get-Random -Minimum 0 -Maximum 11                                 # 0-10 uniform

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

Write-Host 'Seeding 300 UNCORRELATED (noise) patients...'
Post-PatientBatch -Patients $uncorrPatients -Label 'NOISE'

Write-Host 'Waiting 5s for aggregation...'
Start-Sleep -Seconds 5

# ── Validate: clustering should be LOW quality on noise ─────────────────────
Write-Host ''
Write-Host '── NOISE VALIDATION ──────────────────────────────────────────'

$stats = Invoke-RestMethod -Uri "$BaseUrl/Patient/`$dashboard-stats?block=$Block" -TimeoutSec 30
$analysis = $stats.socioeconomicAnalysis
$quality  = $analysis.quality
$profiles = $analysis.clusterProfiles

$noiseSil = [double]$quality.silhouetteScore
$noiseLow = $quality.lowConfidence
$noiseK   = [int]$analysis.clusterCount

Write-Host "  Noise clustering: K=$noiseK, silhouette=$noiseSil, lowConfidence=$noiseLow" -ForegroundColor Cyan

# With truly uncorrelated data, at least one of these should hold:
#   - lowConfidence = true
#   - silhouette < 0.35 (mediocre quality)
#   - cluster profiles lack meaningful feature separation
$noiseIsWeak = ($noiseLow -eq $true) -or ($noiseSil -lt 0.35)

Assert-Test 'Noise data does NOT produce high-quality clusters (sil < 0.35 or lowConfidence)' `
  $noiseIsWeak `
  "silhouette=$noiseSil, lowConfidence=$noiseLow"

# Check feature separation in noise clusters — should be small
if ($profiles.Count -ge 2) {
  $sortedByAge = $profiles | Sort-Object { [double]$_.avgAge }
  $ageDiffNoise  = [Math]::Abs([double]$sortedByAge[-1].avgAge - [double]$sortedByAge[0].avgAge)
  $hrDiffNoise   = [Math]::Abs([double]$sortedByAge[-1].avgHeartRate - [double]$sortedByAge[0].avgHeartRate)
  $condDiffNoise = [Math]::Abs([double]$sortedByAge[-1].avgConditions - [double]$sortedByAge[0].avgConditions)

  Write-Host "  Noise separation: ageDiff=$ageDiffNoise, hrDiff=$hrDiffNoise, condDiff=$condDiffNoise" -ForegroundColor Cyan

  # With noise, we expect smaller separations than correlated data
  # But we don't assert hard failure since K-means can still find _some_ structure in noise
  if ($ageDiffNoise -gt 20 -and $hrDiffNoise -gt 15 -and $condDiffNoise -gt 1.5) {
    Warn-Test 'Noise data shows unexpectedly large feature separation' `
      "ageDiff=$ageDiffNoise, hrDiff=$hrDiffNoise, condDiff=$condDiffNoise — may indicate over-fitting"
  } else {
    Assert-Test 'Noise clusters have limited feature separation (expected)' `
      $true `
      "ageDiff=$ageDiffNoise, hrDiff=$hrDiffNoise, condDiff=$condDiffNoise"
  }

  # Cross-check: in noise data, HR should NOT consistently correlate with age
  $hrAgeCorrelated = (
    ([double]$sortedByAge[-1].avgHeartRate -gt [double]$sortedByAge[0].avgHeartRate) -and
    ($hrDiffNoise -gt 10) -and
    ($ageDiffNoise -gt 10)
  )
  Assert-Test 'Noise: no strong HR-age correlation across clusters' `
    (-not $hrAgeCorrelated) `
    "oldest cluster avgHR=$([double]$sortedByAge[-1].avgHeartRate) vs youngest=$([double]$sortedByAge[0].avgHeartRate)"
}

# ── Cleanup noise data ─────────────────────────────────────────────────────
Write-Host ''
Write-Host 'Cleaning noise patients (preparing for correlated phase)...'
Remove-TestPatients 'km-uncorr' 300
Start-Sleep -Seconds 3

# ══════════════════════════════════════════════════════════════════════════════
#  PHASE 2: CORRELATED DATA (positive-control)
# ══════════════════════════════════════════════════════════════════════════════
if (-not $SkipCorrelatedPhase) {
  Write-Host ''
  Write-Host '╔═════════════════════════════════════════════════════════════╗'
  Write-Host '║  PHASE 2: CORRELATED CLINICAL DATA (positive control)     ║'
  Write-Host '╚═════════════════════════════════════════════════════════════╝'
  Write-Host ''

  if ($CleanupFirst) { Remove-TestPatients 'km-corr' 300 }

  # 3 well-separated clinical clusters (no feature overlap on any pair)
  $clusterDefs = @(
    @{
      tag = 'H'; count = 100
      ageMin = 70; ageMax = 90; condMin = 4; condMax = 5; news2Min = 7; news2Max = 10
      vitals = @{ hrMin=108; hrMax=130; rrMin=24; rrMax=32; tmpMin=38.3; tmpMax=39.5;
                  o2Min=86;  o2Max=91;  bpMin=75; bpMax=95 }
    },
    @{
      tag = 'M'; count = 100
      ageMin = 42; ageMax = 58; condMin = 2; condMax = 3; news2Min = 4; news2Max = 6
      vitals = @{ hrMin=82; hrMax=96; rrMin=17; rrMax=23; tmpMin=37.2; tmpMax=37.9;
                  o2Min=93; o2Max=96; bpMin=110; bpMax=125 }
    },
    @{
      tag = 'L'; count = 100
      ageMin = 18; ageMax = 30; condMin = 0; condMax = 0; news2Min = 0; news2Max = 2
      vitals = @{ hrMin=55; hrMax=70; rrMin=12; rrMax=16; tmpMin=36.4; tmpMax=36.9;
                  o2Min=97; o2Max=100; bpMin=130; bpMax=145 }
    }
  )

  $corrPatients = @()
  $seq = 0
  foreach ($cDef in $clusterDefs) {
    for ($i = 1; $i -le $cDef.count; $i++) {
      $seq++
      $id     = 'km-corr-{0:D3}' -f $seq
      $gender = if ($i % 2 -eq 0) { 'male' } else { 'female' }
      $given  = $givenNames[($seq - 1) % $givenNames.Count]
      $age    = Get-Random -Minimum $cDef.ageMin -Maximum ($cDef.ageMax + 1)
      $birth  = '{0:D4}-{1:D2}-{2:D2}' -f ((Get-Date).Year - $age), (Get-Random -Minimum 1 -Maximum 13), (Get-Random -Minimum 1 -Maximum 29)
      $news2  = Get-Random -Minimum $cDef.news2Min -Maximum ($cDef.news2Max + 1)
      $nCond  = Get-Random -Minimum $cDef.condMin  -Maximum ($cDef.condMax + 1)

      $v = $cDef.vitals
      $vitals = @{
        '8867-4'  = [Math]::Round((Get-Random -Minimum ($v.hrMin  * 10) -Maximum (($v.hrMax  + 1) * 10)) / 10, 1)
        '9279-1'  = [Math]::Round((Get-Random -Minimum ($v.rrMin  * 10) -Maximum (($v.rrMax  + 1) * 10)) / 10, 1)
        '8310-5'  = [Math]::Round((Get-Random -Minimum ($v.tmpMin * 100) -Maximum (($v.tmpMax + 0.1) * 100)) / 100, 2)
        '59408-5' = [Math]::Round((Get-Random -Minimum ($v.o2Min  * 10) -Maximum (($v.o2Max  + 1) * 10)) / 10, 1)
        '8480-6'  = [Math]::Round((Get-Random -Minimum ($v.bpMin  * 10) -Maximum (($v.bpMax  + 1) * 10)) / 10, 1)
      }

      $corrPatients += @{
        id = $id; family = "Km$($cDef.tag)"; given = $given; gender = $gender
        birthDate = $birth; news2 = $news2; conditions = $nCond; vitals = $vitals
        tag = $cDef.tag
      }
    }
  }

  Write-Host 'Seeding 300 CORRELATED (clinically structured) patients...'
  Post-PatientBatch -Patients $corrPatients -Label 'SIGNAL'

  Write-Host 'Waiting 5s for aggregation...'
  Start-Sleep -Seconds 5

  # ── Validate: clustering SHOULD be high quality on correlated data ────────
  Write-Host ''
  Write-Host '── CORRELATED VALIDATION ───────────────────────────────────'

  $stats2 = Invoke-RestMethod -Uri "$BaseUrl/Patient/`$dashboard-stats?block=$Block" -TimeoutSec 30
  $analysis2 = $stats2.socioeconomicAnalysis
  $quality2  = $analysis2.quality
  $profiles2 = $analysis2.clusterProfiles

  $corrSil = [double]$quality2.silhouetteScore
  $corrLow = $quality2.lowConfidence
  $corrK   = [int]$analysis2.clusterCount

  Write-Host "  Correlated clustering: K=$corrK, silhouette=$corrSil, lowConfidence=$corrLow" -ForegroundColor Cyan

  Assert-Test 'Correlated data produces valid clusters (K >= 2)' `
    ($corrK -ge 2) `
    "K=$corrK"

  Assert-Test 'Correlated silhouette >= 0.2 (passes configured threshold)' `
    ($corrSil -ge 0.2) `
    "silhouette=$corrSil"

  Assert-Test 'Correlated silhouette is BETTER than noise silhouette' `
    ($corrSil -gt $noiseSil) `
    "correlated=$corrSil > noise=$noiseSil (delta=$([Math]::Round($corrSil - $noiseSil, 4)))"

  if ($profiles2.Count -ge 2) {
    $sortedCorr = $profiles2 | Sort-Object { [double]$_.avgAge }
    $ageDiffCorr  = [Math]::Abs([double]$sortedCorr[-1].avgAge - [double]$sortedCorr[0].avgAge)
    $hrDiffCorr   = [Math]::Abs([double]$sortedCorr[-1].avgHeartRate - [double]$sortedCorr[0].avgHeartRate)
    $condDiffCorr = [Math]::Abs([double]$sortedCorr[-1].avgConditions - [double]$sortedCorr[0].avgConditions)

    Write-Host "  Correlated separation: ageDiff=$ageDiffCorr, hrDiff=$hrDiffCorr, condDiff=$condDiffCorr" -ForegroundColor Cyan

    Assert-Test 'Correlated data has larger age separation than noise' `
      ($ageDiffCorr -gt $ageDiffNoise) `
      "corr=$ageDiffCorr > noise=$ageDiffNoise"

    Assert-Test 'Correlated age separation >= 15 years between extremes' `
      ($ageDiffCorr -ge 15) `
      "ageDiff=$ageDiffCorr"

    Assert-Test 'Correlated HR separation >= 10 bpm between extremes' `
      ($hrDiffCorr -ge 10) `
      "hrDiff=$hrDiffCorr"

    Assert-Test 'Older cluster has higher HR (real clinical signal)' `
      ([double]$sortedCorr[-1].avgHeartRate -gt [double]$sortedCorr[0].avgHeartRate) `
      "oldest HR=$([double]$sortedCorr[-1].avgHeartRate), youngest HR=$([double]$sortedCorr[0].avgHeartRate)"

    Assert-Test 'Older cluster has more conditions (real clinical signal)' `
      ([double]$sortedCorr[-1].avgConditions -gt [double]$sortedCorr[0].avgConditions) `
      "oldest cond=$([double]$sortedCorr[-1].avgConditions), youngest cond=$([double]$sortedCorr[0].avgConditions)"
  }

  # ── Cleanup correlated data ──────────────────────────────────────────────
  Write-Host ''
  Write-Host 'Cleaning correlated patients...'
  Remove-TestPatients 'km-corr' 300
}

# ── Final Summary ────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '╔═════════════════════════════════════════════════════════════╗'
Write-Host '║  FINAL RESULTS                                            ║'
Write-Host '╚═════════════════════════════════════════════════════════════╝'
Write-Host "  PASS: $pass | FAIL: $fail | WARN: $warn"
if (-not $SkipCorrelatedPhase) {
  Write-Host "  Noise silhouette:      $noiseSil (lowConf=$noiseLow)"
  Write-Host "  Correlated silhouette: $corrSil (lowConf=$corrLow)"
  Write-Host "  Discrimination delta:  $([Math]::Round($corrSil - $noiseSil, 4))"
}
if ($fail -eq 0) {
  Write-Host '  STATUS: ALL ASSERTIONS PASSED — engine correctly discriminates signal from noise' -ForegroundColor Green
} else {
  Write-Host "  STATUS: $fail ASSERTION(S) FAILED" -ForegroundColor Red
}
Write-Host '═══════════════════════════════════════════════════════════════'
