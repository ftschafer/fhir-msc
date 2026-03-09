<#
.SYNOPSIS
    Edge-case test: MISSING / PARTIAL DATA.
    Creates 10 neighbourhood samples where some are missing vital signs, NEWS2, or conditions.

.DESCRIPTION
    Posts FHIR data for 10 neighbourhood samples (M01-M10) in neighborhood N94.
    Neighbourhood samples M01-M06 have complete data; M07-M10 have gaps:
      - M07: no NEWS2 observation
      - M08: no vital signs observations
      - M09: no conditions
      - M10: no NEWS2 AND no vital signs

    Expected behaviour:
      - neighCount = 10 (all neighbourhood samples have MeasureReports)
      - Correlations involving missing data pairs -> fewer samples
      - careUnits_vs_news2: samples < 10 (M07, M10 lack NEWS2)
      - meanAge_vs_heartRate: samples < 10 (M08, M10 lack vitals)
      - averageIncome_vs_conditions: samples = 10 (conditions=0 is valid)
      - The controller should NOT crash or error

    This tests graceful degradation when FHIR data is incomplete.
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "C91"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# 10 neighbourhood samples: M01-M06 complete, M07-M10 have missing data
$neighborhoodSamples = @(
    @{ id="M01"; income=25000; care=9;  age=40; news2=1.5; cond=4; hr=72; sbp=110; dbp=72; hasNews2=$true;  hasVitals=$true  }
    @{ id="M02"; income=30000; care=8;  age=45; news2=2.0; cond=3; hr=74; sbp=116; dbp=75; hasNews2=$true;  hasVitals=$true  }
    @{ id="M03"; income=35000; care=7;  age=50; news2=2.5; cond=3; hr=76; sbp=122; dbp=79; hasNews2=$true;  hasVitals=$true  }
    @{ id="M04"; income=40000; care=6;  age=55; news2=3.0; cond=2; hr=78; sbp=128; dbp=82; hasNews2=$true;  hasVitals=$true  }
    @{ id="M05"; income=45000; care=5;  age=60; news2=3.5; cond=2; hr=80; sbp=132; dbp=85; hasNews2=$true;  hasVitals=$true  }
    @{ id="M06"; income=50000; care=4;  age=65; news2=4.0; cond=1; hr=82; sbp=138; dbp=89; hasNews2=$true;  hasVitals=$true  }
    @{ id="M07"; income=55000; care=3;  age=70; news2=0;   cond=1; hr=84; sbp=144; dbp=92; hasNews2=$false; hasVitals=$true  }
    @{ id="M08"; income=60000; care=2;  age=52; news2=4.5; cond=1; hr=0;  sbp=0;   dbp=0;  hasNews2=$true;  hasVitals=$false }
    @{ id="M09"; income=65000; care=1;  age=48; news2=5.0; cond=0; hr=73; sbp=114; dbp=74; hasNews2=$true;  hasVitals=$true  }
    @{ id="M10"; income=70000; care=1;  age=58; news2=0;   cond=0; hr=0;  sbp=0;   dbp=0;  hasNews2=$false; hasVitals=$false }
)

# ==========================================================================
# HELPER FUNCTIONS
# ==========================================================================

function Post-FhirBundle {
    param([object]$Bundle, [string]$Label)
    $json = $Bundle | ConvertTo-Json -Depth 30 -Compress
    Write-Host "  Posting $Label ... " -NoNewline
    try {
        $resp = Invoke-RestMethod -Uri $FhirBase -Method POST `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
            -ContentType "application/fhir+json; charset=utf-8"
        $count = 0
        if ($resp.entry) { $count = $resp.entry.Count }
        Write-Host "OK ($count resources)" -ForegroundColor Green
        return $resp
    } catch {
        Write-Host "FAILED" -ForegroundColor Red
        Write-Host "    $($_.Exception.Message)" -ForegroundColor Red
        throw
    }
}

function Make-LocationExtension {
    param([string]$NeighborhoodId, [string]$City)
    return @{
        url = "http://patient-location"
        extension = @(
            @{ url = "block";        valueString = $NeighborhoodId }
            @{ url = "neighborhood"; valueString = $NeighborhoodId }
            @{ url = "city";         valueString = $City }
        )
    }
}

function Sig-Stars {
    param([double]$p)
    if     ($p -lt 0.001) { return "***" }
    elseif ($p -lt 0.01)  { return "**"  }
    elseif ($p -lt 0.05)  { return "*"   }
    else                  { return "ns"  }
}

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  EDGE CASE: Missing / Partial Data (${City})" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

# ==========================================================================
# STEP 0 - Health check
# ==========================================================================
Write-Host "[0] Health check ... " -NoNewline
try {
    $meta = Invoke-RestMethod -Uri "$FhirBase/metadata" -Headers @{Accept="application/fhir+json"} -TimeoutSec 10
    Write-Host "OK (FHIR $($meta.fhirVersion))" -ForegroundColor Green
} catch {
    Write-Host "FAILED - Is the server running at ${FhirBase}?" -ForegroundColor Red
    exit 1
}

# ==========================================================================
# STEP 1 - Anchor patient
# ==========================================================================
Write-Host ""
Write-Host "[1] Creating anchor patient ... " -NoNewline
$anchorBody = @{
    resourceType = "Patient"
    name = @(@{ family = "EdgeMissing"; given = @("Anchor") })
}
$anchorJson = $anchorBody | ConvertTo-Json -Depth 10
$anchorResp = Invoke-RestMethod -Uri "$FhirBase/Patient" -Method POST `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($anchorJson)) `
    -ContentType "application/fhir+json; charset=utf-8"
$anchorId = $anchorResp.id
Write-Host "OK (Patient/$anchorId)" -ForegroundColor Green

# ==========================================================================
# STEP 2 - MeasureReports (ALL 10 neighbourhood samples - every neighbourhood sample has demographic data)
# ==========================================================================
Write-Host ""
Write-Host "[2] Creating MeasureReports (10 neighbourhood samples) ..."
$mrEntries = @()
foreach ($b in $neighborhoodSamples) {
    $mrEntries += @{
        resource = @{
            resourceType = "MeasureReport"
            status       = "complete"
            type         = "summary"
                        date         = "2025-06-15"
            period       = @{ start = "2025-01-01"; end = "2025-12-31" }
            identifier   = @(@{ system = "urn:block:health-aggregation"; value = $b.id })
            extension    = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
            group = @(@{
                stratifier = @(
                    @{ code = @(@{ text = "Average Income" }); stratum = @(@{ measureScore = @{ value = [decimal]$b.income } }) }
                    @{ code = @(@{ text = "Care Units" });     stratum = @(@{ measureScore = @{ value = [decimal]$b.care } }) }
                    @{ code = @(@{ text = "Mean Age" });       stratum = @(@{ measureScore = @{ value = [decimal]$b.age } }) }
                )
            })
        }
        request = @{ method = "POST"; url = "MeasureReport" }
    }
}
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$mrEntries } -Label "MeasureReports"

# ==========================================================================
# STEP 3 - Patients (2 per neighbourhood sample = 20)
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (20) ..."
$patEntries = @()
foreach ($b in $neighborhoodSamples) {
    for ($p = 1; $p -le 2; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "Missing-$($b.id)"; given = @("P$p") })
                birthDate = "$birthYear-01-15"
                extension = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
            }
            request = @{ method = "POST"; url = "Patient" }
        }
    }
}
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$patEntries } -Label "Patients"

# ==========================================================================
# STEP 4 - Conditions (for neighbourhood samples with cond > 0)
# ==========================================================================
Write-Host ""
$totalCond = ($neighborhoodSamples | ForEach-Object { $_.cond } | Measure-Object -Sum).Sum
Write-Host "[4] Creating Conditions ($totalCond total) ..."
$condEntries = @()
foreach ($b in $neighborhoodSamples) {
    for ($c = 1; $c -le $b.cond; $c++) {
        $condEntries += @{
            resource = @{
                resourceType   = "Condition"
                clinicalStatus = @{ coding = @(@{ system="http://terminology.hl7.org/CodeSystem/condition-clinical"; code="active" }) }
                code      = @{ coding = @(@{ system="http://snomed.info/sct"; code="38341003"; display="Hypertension" }) }
                subject   = @{ reference = "Patient/$anchorId" }
                extension = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
            }
            request = @{ method = "POST"; url = "Condition" }
        }
    }
}
if ($condEntries.Count -gt 0) {
    Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$condEntries } -Label "Conditions"
}

# ==========================================================================
# STEP 5 - NEWS2 Observations (only for neighbourhood samples with hasNews2=$true)
# ==========================================================================
Write-Host ""
$news2Samples = $neighborhoodSamples | Where-Object { $_.hasNews2 }
Write-Host "[5] Creating NEWS2 Observations ($($news2Samples.Count)) ..."
$news2Entries = @()
foreach ($b in $news2Samples) {
    $news2Entries += @{
        resource = @{
            resourceType = "Observation"; status = "final"
            identifier = @(@{ system="urn:aggregate:news2"; value="$($b.id)|block-average" })
            code = @{
                coding = @(@{ system="http://snomed.info/sct"; code="1104051000000101"; display="NEWS2 Score" })
                text   = "NEWS2 Neighbourhood Average"
            }
            valueQuantity = @{ value=[decimal]$b.news2; unit="score"; system="http://unitsofmeasure.org"; code="{score}" }
            extension     = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
        }
        request = @{ method = "POST"; url = "Observation" }
    }
}
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$news2Entries } -Label "NEWS2"

# ==========================================================================
# STEP 6 - Vital Signs (only for neighbourhood samples with hasVitals=$true)
# ==========================================================================
Write-Host ""
$vitalSamples = $neighborhoodSamples | Where-Object { $_.hasVitals }
Write-Host "[6] Creating Vital Signs ($($vitalSamples.Count * 3)) ..."
$vitalEntries = @()
foreach ($b in $vitalSamples) {
    foreach ($vt in @(
        @{ val=$b.hr;  code="8867-4"; display="Heart Rate";              unit="beats/minute"; ucum="/min" },
        @{ val=$b.sbp; code="8480-6"; display="Systolic Blood Pressure"; unit="mmHg";         ucum="mm[Hg]" },
        @{ val=$b.dbp; code="8462-4"; display="Diastolic Blood Pressure";unit="mmHg";         ucum="mm[Hg]" }
    )) {
        $vitalEntries += @{
            resource = @{
                resourceType = "Observation"; status = "final"
                category = @(@{ coding = @(@{ system="http://terminology.hl7.org/CodeSystem/observation-category"; code="vital-signs-average"; display="Vital Signs Average" }) })
                code = @{ coding = @(@{ system="http://loinc.org"; code=$vt.code; display=$vt.display }); text=$vt.display }
                valueQuantity = @{ value=[decimal]$vt.val; unit=$vt.unit; system="http://unitsofmeasure.org"; code=$vt.ucum }
                extension = @(
                    (Make-LocationExtension -NeighborhoodId $b.id -City $City)
                    @{ url="http://observation-sample-count"; valueInteger=3 }
                )
            }
            request = @{ method = "POST"; url = "Observation" }
        }
    }
}
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$vitalEntries } -Label "Vital Signs"

# ==========================================================================
# STEP 7 - Wait
# ==========================================================================
Write-Host ""
Write-Host "[7] Waiting 3 seconds ..."
Start-Sleep -Seconds 3

# ==========================================================================
# STEP 8 - Call correlation endpoint
# ==========================================================================
Write-Host ""
Write-Host "[8] GET /analytics/neigh-correlations ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-correlations" `
        -Headers @{Accept="application/json"} -TimeoutSec 30
    Write-Host "  OK - neighSamples=$($corr.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ==========================================================================
# STEP 9 - Call spatial autocorrelation
# ==========================================================================
Write-Host ""
Write-Host "[9] GET /analytics/neigh-spatial-autocorrelation ..."
$moran = $null
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-spatial-autocorrelation" `
        -Headers @{Accept="application/json"} -TimeoutSec 30
    Write-Host "  OK - neighSamples=$($moran.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# ==========================================================================
# STEP 10 - VALIDATE
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  VALIDATION: Missing Data - Graceful Degradation" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$pass = 0
$fail = 0

# neighCount = 10
Write-Host "  neighCount = 10 ... " -NoNewline
if ($corr.neighCount -eq 10) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($corr.neighCount))" -ForegroundColor Red
    $fail++
}

# 5 correlations returned
Write-Host "  5 correlations returned ... " -NoNewline
if ($corr.correlations -and $corr.correlations.Count -eq 5) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    $c = if ($corr.correlations) { $corr.correlations.Count } else { 0 }
    Write-Host "FAIL (got $c)" -ForegroundColor Red
    $fail++
}

# No response errors (the API returned valid JSON)
Write-Host "  No API error ... " -NoNewline
Write-Host "PASS (valid JSON response)" -ForegroundColor Green
$pass++

Write-Host ""
Write-Host "  --- Per-correlation validation ---" -ForegroundColor White
Write-Host ""

# careUnits_vs_news2: M07 and M10 lack NEWS2 -> samples should be 8
$item = $corr.correlations | Where-Object { $_.label -eq "careUnits_vs_news2" }
Write-Host "  careUnits_vs_news2" -ForegroundColor White
Write-Host "    samples < 10 (M07,M10 missing NEWS2) ... " -NoNewline
if ($item.samples -lt 10 -and $item.samples -ge 3) {
    Write-Host "PASS (samples=$($item.samples))" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (samples=$($item.samples))" -ForegroundColor Red
    $fail++
}
Write-Host "    status = ok ... " -NoNewline
if ($item.status -eq "ok") {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
    $fail++
}

# averageIncome_vs_conditions: all 10 neighbourhood samples have income + conditions counted
$item = $corr.correlations | Where-Object { $_.label -eq "averageIncome_vs_conditions" }
Write-Host ""
Write-Host "  averageIncome_vs_conditions" -ForegroundColor White
Write-Host "    samples = 10 (all neighbourhood samples have demographic data) ... " -NoNewline
if ($item.samples -eq 10) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (samples=$($item.samples))" -ForegroundColor Red
    $fail++
}
Write-Host "    status = ok ... " -NoNewline
if ($item.status -eq "ok") {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
    $fail++
}

# meanAge_vs_heartRate: M08 and M10 lack vitals -> samples should be 8
$item = $corr.correlations | Where-Object { $_.label -eq "meanAge_vs_heartRate" }
Write-Host ""
Write-Host "  meanAge_vs_heartRate" -ForegroundColor White
Write-Host "    samples < 10 (M08,M10 missing vitals) ... " -NoNewline
if ($item.samples -lt 10 -and $item.samples -ge 3) {
    Write-Host "PASS (samples=$($item.samples))" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (samples=$($item.samples))" -ForegroundColor Red
    $fail++
}
Write-Host "    status = ok ... " -NoNewline
if ($item.status -eq "ok") {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
    $fail++
}

# systolicBP and diastolicBP same pattern
foreach ($label in @("meanAge_vs_systolicBP", "meanAge_vs_diastolicBP")) {
    $item = $corr.correlations | Where-Object { $_.label -eq $label }
    Write-Host ""
    Write-Host "  $label" -ForegroundColor White
    Write-Host "    samples < 10 ... " -NoNewline
    if ($item.samples -lt 10 -and $item.samples -ge 3) {
        Write-Host "PASS (samples=$($item.samples))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (samples=$($item.samples))" -ForegroundColor Red
        $fail++
    }
    Write-Host "    status = ok ... " -NoNewline
    if ($item.status -eq "ok") {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
        $fail++
    }
}

# Display all coefficients for reference
Write-Host ""
Write-Host "  --- Coefficient summary ---" -ForegroundColor DarkGray
foreach ($item in $corr.correlations) {
    if ($item.status -eq "ok") {
        $pPStr = if ($null -ne $item.pearsonPValue  ) { "p={0:F4}" -f $item.pearsonPValue   } else { "p=N/A" }
        $pSStr = if ($null -ne $item.spearmanPValue ) { "p={0:F4}" -f $item.spearmanPValue  } else { "p=N/A" }
        Write-Host ("  {0,-35} r={1,7:F4} ({2})  rho={3,7:F4} ({4})  n={5}" -f $item.label, $item.pearson, $pPStr, $item.spearman, $pSStr, $item.samples) -ForegroundColor DarkGray
    }
}

# ==========================================================================
# STEP 11 - Moran's I
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  MORAN'S I - Mixed Data Availability" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($moran -and $moran.variables) {
    foreach ($v in $moran.variables) {
        Write-Host "  $($v.variable) (n=$($v.n)): " -NoNewline
        if ($v.status -eq "ok") {
            Write-Host "I=$($v.moranI) p=$($v.pValue) pattern=$($v.pattern)" -ForegroundColor Green
        } elseif ($v.status -eq "zero_variance") {
            Write-Host "zero_variance" -ForegroundColor Yellow
        } elseif ($v.status -eq "insufficient_data") {
            Write-Host "insufficient_data" -ForegroundColor Yellow
        } else {
            Write-Host "$($v.status)" -ForegroundColor Yellow
        }
    }
}

# ==========================================================================
# Neighbourhood source data
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  NEIGHBOURHOOD SOURCE DATA" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
if ($corr.neighRows) {
    Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Neighbourhood","Income","Care","Age","NEWS2","Cond","HR","SBP","DBP")
    Write-Host ("  " + ("-" * 68)) -ForegroundColor DarkGray
    foreach ($row in $corr.neighRows | Sort-Object { $_.neighbourhood }) {
        $n2 = if ($null -ne $row.avgNews2) { "{0,7:F1}" -f $row.avgNews2 } else { "   null" }
        $hr = if ($null -ne $row.heartRate) { "{0,6}" -f $row.heartRate } else { "  null" }
        $sb = if ($null -ne $row.systolicBP) { "{0,6}" -f $row.systolicBP } else { "  null" }
        $db = if ($null -ne $row.diastolicBP) { "{0,6}" -f $row.diastolicBP } else { "  null" }
        Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4} {5,5} {6} {7} {8}" -f $row.neighbourhood,$row.averageIncome,$row.careUnits,$row.meanAge,$n2,$row.conditionCount,$hr,$sb,$db)
    }
}

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SUMMARY - Missing Data Edge Case" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Design: 10 neighbourhood samples, 4 with missing data"
Write-Host "    M07: no NEWS2"
Write-Host "    M08: no vital signs"
Write-Host "    M09: no conditions (but cond=0 is valid)"
Write-Host "    M10: no NEWS2 and no vital signs"
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "  ALL MISSING-DATA TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "  FAIL: $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""







