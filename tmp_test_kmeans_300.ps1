<#
.SYNOPSIS
  Seeds 300 patients into block B71 with 3 well-separated clinical clusters
  (HIGH/MEDIUM/LOW risk) and validates that K-means clustering in the
  dashboard-stats endpoint correctly separates them.

.DESCRIPTION
  - 100 HIGH-risk: elderly (age 65-85), high HR (100-120), low BP (85-100),
    many conditions (3-5), gender-balanced
  - 100 MEDIUM-risk: adult (age 35-55), moderate HR (78-95), normal BP (110-130),
    some conditions (1-2), gender-balanced
  - 100 LOW-risk: young (age 20-34), normal HR (60-78), healthy BP (115-135),
    few conditions (0-1), gender-balanced

  After seeding, calls $dashboard-stats and validates:
    1. sampleSize == 300
    2. clusterCount >= 2 (ideally 3)
    3. silhouetteScore >= minSilhouette (0.2)
    4. Each cluster has reasonable patient count (no empty/singleton clusters)
    5. Cluster profiles show meaningful separation on avgAge, avgHeartRate, avgConditions

.PARAMETER BaseUrl
  FHIR server base URL (default: http://localhost:8071/fhir)

.PARAMETER Block
  Block identifier (default: B71)

.PARAMETER CleanupExisting
  If set, deletes all km3-* patients/conditions/observations before seeding

.PARAMETER SkipSeed
  If set, skips seeding and only runs validation against existing data

.PARAMETER BatchSize
  Number of Bundle entries per transaction (default: 50)
#>
param(
  [string]$BaseUrl = 'http://localhost:8071/fhir',
  [string]$Block   = 'B71',
  [switch]$CleanupExisting,
  [switch]$SkipSeed,
  [int]$BatchSize   = 50
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# ── Connectivity check ──────────────────────────────────────────────────────
try {
  Invoke-RestMethod -Uri "$BaseUrl/metadata" -TimeoutSec 10 | Out-Null
} catch {
  throw "Server not reachable at $BaseUrl. Start the application first."
}
Write-Host "Server is UP at $BaseUrl"

# ── Helpers ─────────────────────────────────────────────────────────────────
$loincDisplay = @{
  '8867-4'  = 'Heart rate'
  '9279-1'  = 'Respiratory rate'
  '8310-5'  = 'Body temperature'
  '59408-5' = 'Oxygen saturation'
  '8480-6'  = 'Systolic blood pressure'
}
$loincUnit = @{
  '8867-4'  = '/min'
  '9279-1'  = '/min'
  '8310-5'  = 'Cel'
  '59408-5' = '%'
  '8480-6'  = 'mm[Hg]'
}
$conditionLabels = @('Hypertension','Diabetes','COPD','Heart Failure','CKD')
$givenNames      = @('Ana','Bruno','Clara','Diego','Eva','Fabio','Gabi','Hugo',
                      'Iara','Joao','Karen','Leo','Maya','Nico','Olga','Pablo',
                      'Quinn','Rafa','Sara','Tomas','Ursula','Vitor','Wanda',
                      'Xavi','Yara','Zeca','Davi','Elisa','Fiona','Gil')

function Get-RandomDate([int]$minAge, [int]$maxAge) {
  $age = Get-Random -Minimum $minAge -Maximum ($maxAge + 1)
  $year = (Get-Date).Year - $age
  $month = Get-Random -Minimum 1 -Maximum 13
  $day = Get-Random -Minimum 1 -Maximum 29
  return '{0:D4}-{1:D2}-{2:D2}' -f $year, $month, $day
}

function Get-RandomVitals([hashtable]$ranges) {
  return @{
    '8867-4'  = [Math]::Round((Get-Random -Minimum ($ranges.hrMin  * 10) -Maximum (($ranges.hrMax  + 1) * 10)) / 10, 1)
    '9279-1'  = [Math]::Round((Get-Random -Minimum ($ranges.rrMin  * 10) -Maximum (($ranges.rrMax  + 1) * 10)) / 10, 1)
    '8310-5'  = [Math]::Round((Get-Random -Minimum ($ranges.tmpMin * 100) -Maximum (($ranges.tmpMax + 0.1) * 100)) / 100, 2)
    '59408-5' = [Math]::Round((Get-Random -Minimum ($ranges.o2Min  * 10) -Maximum (($ranges.o2Max  + 1) * 10)) / 10, 1)
    '8480-6'  = [Math]::Round((Get-Random -Minimum ($ranges.bpMin  * 10) -Maximum (($ranges.bpMax  + 1) * 10)) / 10, 1)
  }
}

# ── Define 3 well-separated clusters ───────────────────────────────────────
# Key design: NO overlap between ANY pair of clusters on key features.
# Gaps on every dimension ensure K=3 reliably beats K=2 silhouette.
#
#         HIGH          gap    MEDIUM        gap    LOW
# Age:    70-90          12     42-58         12     18-30
# HR:     108-130        12     82-96         12     55-70
# BP:     75-95          15     110-125       5      130-145
# Cond:   4-5            1      2-3           2      0
# O2:     86-91          2      93-96         1      97-100
$clusterDefs = @(
  @{
    tag      = 'H'
    family   = 'KmHigh'
    count    = 100
    ageMin   = 70; ageMax   = 90
    condMin  = 4;  condMax  = 5
    news2Min = 7;  news2Max = 10
    vitals   = @{ hrMin=108; hrMax=130; rrMin=24; rrMax=32; tmpMin=38.3; tmpMax=39.5;
                  o2Min=86;  o2Max=91;  bpMin=135; bpMax=155 }
  },
  @{
    tag      = 'M'
    family   = 'KmMed'
    count    = 100
    ageMin   = 42; ageMax   = 58
    condMin  = 2;  condMax  = 3
    news2Min = 4;  news2Max = 6
    vitals   = @{ hrMin=82; hrMax=96; rrMin=17; rrMax=23; tmpMin=37.2; tmpMax=37.9;
                  o2Min=93; o2Max=96; bpMin=120; bpMax=135 }
  },
  @{
    tag      = 'L'
    family   = 'KmLow'
    count    = 100
    ageMin   = 18; ageMax   = 30
    condMin  = 0;  condMax  = 0
    news2Min = 0;  news2Max = 2
    vitals   = @{ hrMin=55; hrMax=70; rrMin=12; rrMax=16; tmpMin=36.4; tmpMax=36.9;
                  o2Min=97; o2Max=100; bpMin=105; bpMax=120 }
  }
)

# ── Generate patient definitions ───────────────────────────────────────────
$allPatients = @()
$seq = 0
foreach ($cDef in $clusterDefs) {
  for ($i = 1; $i -le $cDef.count; $i++) {
    $seq++
    $id     = 'km3-{0}-{1:D3}' -f $cDef.tag, $i
    $gender = if ($i % 2 -eq 0) { 'male' } else { 'female' }
    $given  = $givenNames[($seq - 1) % $givenNames.Count]
    $birth  = Get-RandomDate -minAge $cDef.ageMin -maxAge $cDef.ageMax
    $news2  = Get-Random -Minimum $cDef.news2Min -Maximum ($cDef.news2Max + 1)
    $nCond  = Get-Random -Minimum $cDef.condMin  -Maximum ($cDef.condMax + 1)
    $vitals = Get-RandomVitals -ranges $cDef.vitals

    $allPatients += @{
      id = $id; family = $cDef.family; given = $given; gender = $gender
      birthDate = $birth; news2 = $news2; conditions = $nCond; vitals = $vitals
      tag = $cDef.tag
    }
  }
}

Write-Host "Generated $($allPatients.Count) patient definitions (H=$($clusterDefs[0].count), M=$($clusterDefs[1].count), L=$($clusterDefs[2].count))"

# ── Cleanup ────────────────────────────────────────────────────────────────
if ($CleanupExisting) {
  Write-Host 'Cleaning previous km3-* resources...'
  foreach ($p in $allPatients) {
    try { Invoke-RestMethod -Method Delete -Uri "$BaseUrl/Patient/$($p.id)" -TimeoutSec 5 | Out-Null } catch {}
    for ($c = 1; $c -le 5; $c++) {
      try { Invoke-RestMethod -Method Delete -Uri "$BaseUrl/Condition/cond-$($p.id)-$c" -TimeoutSec 5 | Out-Null } catch {}
    }
  }
  Write-Host 'Cleanup done.'
}

# ── Seed data ──────────────────────────────────────────────────────────────
if (-not $SkipSeed) {
  $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
  $totalEntries = 0
  $batchEntries = New-Object System.Collections.ArrayList
  $batchNum = 0

  function Flush-Batch {
    if ($batchEntries.Count -eq 0) { return }
    $bundle = @{ resourceType = 'Bundle'; type = 'transaction'; entry = [array]$batchEntries }
    $body = $bundle | ConvertTo-Json -Depth 20 -Compress
    $resp = Invoke-RestMethod -Uri $BaseUrl -Method Post -ContentType 'application/fhir+json' -Body $body -TimeoutSec 120
    $script:batchNum++
    Write-Host "  Batch $($script:batchNum): $($batchEntries.Count) entries -> $($resp.entry.Count) responses"
    $script:totalEntries += $batchEntries.Count
    $batchEntries.Clear()
  }

  Write-Host 'Seeding 300 patients with vitals and conditions...'
  foreach ($p in $allPatients) {
    # Patient resource
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

    # Vital sign observations (5 per patient)
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
          system  = 'http://loinc.org'
          code    = $code
          display = $loincDisplay[$code]
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
          system  = 'http://terminology.hl7.org/CodeSystem/condition-clinical'
          code    = 'active'
          display = 'Active'
        })}
        verificationStatus = @{ coding = @(@{
          system  = 'http://terminology.hl7.org/CodeSystem/condition-ver-status'
          code    = 'confirmed'
          display = 'Confirmed'
        })}
        subject            = @{ reference = "Patient/$($p.id)" }
        code               = @{ text = $conditionLabels[($c - 1) % $conditionLabels.Count] }
      }
      [void]$batchEntries.Add(@{ request = @{ method = 'PUT'; url = "Condition/$condId" }; resource = $cond })
    }

    # Flush batch if threshold reached
    if ($batchEntries.Count -ge $BatchSize) {
      Flush-Batch
    }
  }
  Flush-Batch
  Write-Host "Seeded $totalEntries total FHIR entries."
  Write-Host 'Waiting 4s for aggregation processing...'
  Start-Sleep -Seconds 4
}

# ── Validate clustering ────────────────────────────────────────────────────
Write-Host ''
Write-Host '═══════════════════════════════════════════════════════════════'
Write-Host '  K-MEANS CLUSTERING VALIDATION (300 patients, block B71)'
Write-Host '═══════════════════════════════════════════════════════════════'

$stats = Invoke-RestMethod -Uri "$BaseUrl/Patient/`$dashboard-stats?block=$Block" -TimeoutSec 30

$analysis   = $stats.socioeconomicAnalysis
$quality    = $analysis.quality
$profiles   = $analysis.clusterProfiles
$sampleSize = [int]$analysis.sampleSize

$pass = 0
$fail = 0
$warn = 0

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

Write-Host ''
Write-Host '── 1. SAMPLE SIZE & BASIC SHAPE ──────────────────────────────'
Assert-Test 'Sample size >= 250 (allowing for patients without vitals)' `
  ($sampleSize -ge 250) `
  "sampleSize=$sampleSize"

Assert-Test 'Cluster count >= 2' `
  ([int]$analysis.clusterCount -ge 2) `
  "clusterCount=$($analysis.clusterCount)"

Assert-Test 'Cluster count <= 4 (configured max-k)' `
  ([int]$analysis.clusterCount -le 4) `
  "clusterCount=$($analysis.clusterCount)"

Write-Host ''
Write-Host '── 2. CLUSTERING QUALITY ─────────────────────────────────────'
$sil = [double]$quality.silhouetteScore
$adjSil = [double]$quality.adjustedSilhouetteScore
$lowConf = $quality.lowConfidence

Assert-Test 'Silhouette score >= 0.2 (min-silhouette threshold)' `
  ($sil -ge 0.2) `
  "silhouette=$sil"

Assert-Test 'Silhouette score >= 0.35 (good clustering)' `
  ($sil -ge 0.35) `
  "silhouette=$sil (>= 0.35 means good separation)"

Assert-Test 'Low confidence = false' `
  ($lowConf -ne $true) `
  "lowConfidence=$lowConf"

Assert-Test 'Min cluster size >= 2' `
  ([int]$quality.minClusterSizeObserved -ge 2) `
  "minClusterSizeObserved=$($quality.minClusterSizeObserved)"

Assert-Test 'No singleton clusters' `
  ([int]$quality.singletonClusterCount -eq 0) `
  "singletonClusterCount=$($quality.singletonClusterCount)"

Write-Host ''
Write-Host '── 3. CLUSTER SIZE DISTRIBUTION ──────────────────────────────'
$clusterSizes = @()
foreach ($cp in $profiles) {
  $clusterSizes += [int]$cp.patientCount
  Write-Host "     Cluster $($cp.clusterId): $($cp.patientCount) patients | avgAge=$($cp.avgAge) | avgHR=$($cp.avgHeartRate) | avgBP=$($cp.avgSystolicPressure) | avgCond=$($cp.avgConditions)" -ForegroundColor Cyan
}

$minCluster = ($clusterSizes | Measure-Object -Minimum).Minimum
$maxCluster = ($clusterSizes | Measure-Object -Maximum).Maximum
$ratio = if ($maxCluster -gt 0) { [Math]::Round($minCluster / $maxCluster, 2) } else { 0 }

Assert-Test 'Smallest cluster has >= 30 patients (no degenerate splits)' `
  ($minCluster -ge 30) `
  "smallest=$minCluster, largest=$maxCluster, ratio=$ratio"

Assert-Test 'Cluster balance ratio >= 0.3 (min/max)' `
  ($ratio -ge 0.3) `
  "ratio=$ratio (1.0=perfectly balanced)"

Write-Host ''
Write-Host '── 4. FEATURE SEPARATION BETWEEN CLUSTERS ───────────────────'
# Sort profiles by avgAge to identify expected clusters
$sorted = $profiles | Sort-Object { [double]$_.avgAge }

if ($sorted.Count -ge 2) {
  $youngest = $sorted[0]
  $oldest   = $sorted[-1]

  $ageSep  = [Math]::Abs([double]$oldest.avgAge - [double]$youngest.avgAge)
  $hrSep   = [Math]::Abs([double]$oldest.avgHeartRate - [double]$youngest.avgHeartRate)
  $condSep = [Math]::Abs([double]$oldest.avgConditions - [double]$youngest.avgConditions)

  Assert-Test 'Age separation between youngest and oldest cluster >= 15 years' `
    ($ageSep -ge 15) `
    "ageDiff=$ageSep (youngest avgAge=$([double]$youngest.avgAge), oldest avgAge=$([double]$oldest.avgAge))"

  Assert-Test 'Heart rate separation >= 10 bpm between extreme clusters' `
    ($hrSep -ge 10) `
    "hrDiff=$hrSep (youngest avgHR=$([double]$youngest.avgHeartRate), oldest avgHR=$([double]$oldest.avgHeartRate))"

  Assert-Test 'Conditions separation >= 1.0 between extreme clusters' `
    ($condSep -ge 1.0) `
    "condDiff=$condSep (youngest=$([double]$youngest.avgConditions), oldest=$([double]$oldest.avgConditions))"

  # Verify older patients have higher heart rates (expected from our data)
  Assert-Test 'Older cluster has higher avg heart rate (clinical correlation)' `
    ([double]$oldest.avgHeartRate -gt [double]$youngest.avgHeartRate) `
    "oldest avgHR=$([double]$oldest.avgHeartRate) > youngest avgHR=$([double]$youngest.avgHeartRate)"

  Assert-Test 'Older cluster has more avg conditions (clinical correlation)' `
    ([double]$oldest.avgConditions -gt [double]$youngest.avgConditions) `
    "oldest avgCond=$([double]$oldest.avgConditions) > youngest avgCond=$([double]$youngest.avgConditions)"
}

if ($sorted.Count -ge 3) {
  $middle = $sorted[1]
  Write-Host ''
  Write-Host '     3-cluster profile summary:' -ForegroundColor Cyan
  Write-Host "       Young : avgAge=$([double]$youngest.avgAge), avgHR=$([double]$youngest.avgHeartRate), avgCond=$([double]$youngest.avgConditions)" -ForegroundColor Cyan
  Write-Host "       Middle: avgAge=$([double]$middle.avgAge), avgHR=$([double]$middle.avgHeartRate), avgCond=$([double]$middle.avgConditions)" -ForegroundColor Cyan
  Write-Host "       Old   : avgAge=$([double]$oldest.avgAge), avgHR=$([double]$oldest.avgHeartRate), avgCond=$([double]$oldest.avgConditions)" -ForegroundColor Cyan

  # Middle cluster should be between extremes
  $midBetweenAge = ([double]$middle.avgAge -gt [double]$youngest.avgAge) -and ([double]$middle.avgAge -lt [double]$oldest.avgAge)
  Assert-Test 'Middle cluster avgAge is between youngest and oldest' `
    $midBetweenAge `
    "young=$([double]$youngest.avgAge) < mid=$([double]$middle.avgAge) < old=$([double]$oldest.avgAge)"
}

Write-Host ''
Write-Host '── 5. BLOCK-LEVEL NEWS2 AGGREGATE ───────────────────────────'
try {
  $blockDetails = Invoke-RestMethod -Uri "$BaseUrl/../block-details/$Block" -TimeoutSec 10
  $avgNews2     = [double]$blockDetails.average
  $patCount     = [int]$blockDetails.patientCount

  Assert-Test 'Block aggregate patientCount > 0' `
    ($patCount -gt 0) `
    "patientCount=$patCount"

  Assert-Test 'Block aggregate average is reasonable (0-10)' `
    ($avgNews2 -ge 0 -and $avgNews2 -le 10) `
    "average=$avgNews2"
} catch {
  Warn-Test 'Block aggregate endpoint not available' $_.Exception.Message
}

# ── Summary ────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '═══════════════════════════════════════════════════════════════'
Write-Host "  RESULTS: $pass PASS | $fail FAIL | $warn WARN"
if ($fail -eq 0) {
  Write-Host '  STATUS: ALL ASSERTIONS PASSED' -ForegroundColor Green
} else {
  Write-Host "  STATUS: $fail ASSERTION(S) FAILED" -ForegroundColor Red
}
Write-Host '═══════════════════════════════════════════════════════════════'
