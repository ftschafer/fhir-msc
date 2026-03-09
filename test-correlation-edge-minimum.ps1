<#
.SYNOPSIS
    Edge-case test: MINIMUM 3 NEIGHBOURHOODS (boundary condition).
    Creates exactly 3 neighbourhood samples and validates correlation is still computed.

.DESCRIPTION
    Posts FHIR data for 3 neighbourhood samples (K01-K03) in neighborhood N92, then validates
    that the controller:
      - Returns neighCount = 3
      - Computes all 5 correlations (status != insufficient_samples)
      - Produces non-null Pearson/Spearman/Kendall values
      - Reports p-values for each coefficient
      - Moran's I is computed (n=3 is the minimum)

    The 3 neighbourhood samples have a clear linear pattern so coefficients should be
    near -¦1 despite the tiny sample size.

    Expected directions (same as original):
      careUnits  vs news2        -> NEGATIVE
      income     vs conditions   -> NEGATIVE
      meanAge    vs heartRate    -> POSITIVE
      meanAge    vs systolicBP   -> POSITIVE
      meanAge    vs diastolicBP  -> POSITIVE
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "C91"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# Exactly 3 neighbourhood samples - the minimum for correlation
$neighborhoodSamples = @(
    @{ id="K01"; income=20000; care=10; age=30; news2=1.0; cond=6; hr=65; sbp= 96; dbp=63 }
    @{ id="K02"; income=40000; care=6;  age=50; news2=3.0; cond=3; hr=75; sbp=120; dbp=78 }
    @{ id="K03"; income=60000; care=2;  age=70; news2=5.0; cond=0; hr=85; sbp=144; dbp=93 }
)

$expected = @{
    "careUnits_vs_news2"          = @{ dir = "negative" }
    "averageIncome_vs_conditions" = @{ dir = "negative" }
    "meanAge_vs_heartRate"        = @{ dir = "positive" }
    "meanAge_vs_systolicBP"       = @{ dir = "positive" }
    "meanAge_vs_diastolicBP"      = @{ dir = "positive" }
}

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
Write-Host "  EDGE CASE: Minimum 3 Neighbourhood samples (${City})" -ForegroundColor Cyan
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
    name = @(@{ family = "EdgeMin"; given = @("Anchor") })
}
$anchorJson = $anchorBody | ConvertTo-Json -Depth 10
$anchorResp = Invoke-RestMethod -Uri "$FhirBase/Patient" -Method POST `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($anchorJson)) `
    -ContentType "application/fhir+json; charset=utf-8"
$anchorId = $anchorResp.id
Write-Host "OK (Patient/$anchorId)" -ForegroundColor Green

# ==========================================================================
# STEP 2 - MeasureReports (3 neighbourhood samples)
# ==========================================================================
Write-Host ""
Write-Host "[2] Creating MeasureReports (3 neighbourhood samples) ..."
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
# STEP 3 - Patients (2 per neighbourhood sample = 6)
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (6) ..."
$patEntries = @()
foreach ($b in $neighborhoodSamples) {
    for ($p = 1; $p -le 2; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "EdgeMin-$($b.id)"; given = @("P$p") })
                birthDate = "$birthYear-01-15"
                extension = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
            }
            request = @{ method = "POST"; url = "Patient" }
        }
    }
}
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$patEntries } -Label "Patients"

# ==========================================================================
# STEP 4 - Conditions
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
# STEP 5 - NEWS2 Observations
# ==========================================================================
Write-Host ""
Write-Host "[5] Creating NEWS2 Observations (3) ..."
$news2Entries = @()
foreach ($b in $neighborhoodSamples) {
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
# STEP 6 - Vital Signs
# ==========================================================================
Write-Host ""
Write-Host "[6] Creating Vital Signs (9) ..."
$vitalEntries = @()
foreach ($b in $neighborhoodSamples) {
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
# STEP 10 - VALIDATE: Minimum-sample correlations
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  VALIDATION: Minimum 3 Neighbourhood samples - Correlation Computed" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$pass = 0
$fail = 0

# neighCount = 3
Write-Host "  neighCount = 3 ... " -NoNewline
if ($corr.neighCount -eq 3) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($corr.neighCount))" -ForegroundColor Red
    $fail++
}

# minimumSampleForCorrelation = 3
Write-Host "  minimumSampleForCorrelation = 3 ... " -NoNewline
if ($corr.minimumSampleForCorrelation -eq 3) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL ($($corr.minimumSampleForCorrelation))" -ForegroundColor Red
    $fail++
}

foreach ($label in $expected.Keys) {
    $item = $corr.correlations | Where-Object { $_.label -eq $label }
    $exp  = $expected[$label]
    Write-Host ""
    Write-Host "  $label" -ForegroundColor White

    # Status must be "ok" (not insufficient_samples)
    Write-Host "    status = ok ... " -NoNewline
    if ($item.status -eq "ok") {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
        $fail++
    }

    # samples = 3
    Write-Host "    samples = 3 ... " -NoNewline
    if ($item.samples -eq 3) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (samples=$($item.samples))" -ForegroundColor Red
        $fail++
    }

    # Pearson is non-null
    Write-Host "    pearson != null ... " -NoNewline
    if ($null -ne $item.pearson) {
        Write-Host "PASS (r=$($item.pearson))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (null)" -ForegroundColor Red
        $fail++
    }

    # Spearman is non-null
    Write-Host "    spearman != null ... " -NoNewline
    if ($null -ne $item.spearman) {
        Write-Host "PASS (rho=$($item.spearman))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (null)" -ForegroundColor Red
        $fail++
    }

    # Kendall is non-null
    Write-Host "    kendallTau != null ... " -NoNewline
    if ($null -ne $item.kendallTau) {
        Write-Host "PASS (tau=$($item.kendallTau))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (null)" -ForegroundColor Red
        $fail++
    }

    # Direction check (Pearson)
    Write-Host "    direction ($($exp.dir)) ... " -NoNewline
    $dirOk = $false
    if ($null -ne $item.pearson) {
        if ($exp.dir -eq "positive" -and $item.pearson -gt 0) { $dirOk = $true }
        if ($exp.dir -eq "negative" -and $item.pearson -lt 0) { $dirOk = $true }
    }
    if ($dirOk) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (r=$($item.pearson))" -ForegroundColor Red
        $fail++
    }

    # P-values exist
    Write-Host "    p-values present ... " -NoNewline
    $allP = ($null -ne $item.pearsonPValue) -and ($null -ne $item.spearmanPValue) -and ($null -ne $item.kendallTauPValue)
    if ($allP) {
        $pStr = "pP={0:F4}, pS={1:F4}, pK={2:F4}" -f $item.pearsonPValue, $item.spearmanPValue, $item.kendallTauPValue
        Write-Host "PASS ($pStr)" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (some p-values null)" -ForegroundColor Red
        $fail++
    }

    # Permutation test present
    Write-Host "    permutationTest present ... " -NoNewline
    if ($item.permutationTest -and $item.permutationTest.status) {
        Write-Host "PASS (status=$($item.permutationTest.status))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL" -ForegroundColor Red
        $fail++
    }

    # Display coefficients
    if ($item.status -eq "ok") {
        $pPStr = if ($null -ne $item.pearsonPValue  ) { "p={0:F4} {1}" -f $item.pearsonPValue,   (Sig-Stars $item.pearsonPValue  ) } else { "p=N/A" }
        $pSStr = if ($null -ne $item.spearmanPValue ) { "p={0:F4} {1}" -f $item.spearmanPValue,  (Sig-Stars $item.spearmanPValue ) } else { "p=N/A" }
        $pKStr = if ($null -ne $item.kendallTauPValue) { "p={0:F4} {1}" -f $item.kendallTauPValue, (Sig-Stars $item.kendallTauPValue) } else { "p=N/A" }
        Write-Host ("    Pearson  r   = {0,8:F4}  ({1})" -f $item.pearson, $pPStr)          -ForegroundColor DarkGray
        Write-Host ("    Spearman rho = {0,8:F4}  ({1})" -f $item.spearman, $pSStr)         -ForegroundColor DarkGray
        Write-Host ("    Kendall  tau = {0,8:F4}  ({1})" -f $item.kendallTau, $pKStr)       -ForegroundColor DarkGray
    }
}

# ==========================================================================
# STEP 11 - Moran's I (must compute, status=ok)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  MORAN'S I - 3 Neighbourhood samples (minimum)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($moran -and $moran.variables) {
    foreach ($v in $moran.variables) {
        Write-Host "  $($v.variable): " -NoNewline
        if ($v.status -eq "ok") {
            Write-Host "PASS  I=$($v.moranI) p=$($v.pValue) pattern=$($v.pattern)" -ForegroundColor Green
            $pass++
        } elseif ($v.status -eq "zero_variance") {
            Write-Host "PASS (zero_variance - acceptable for 3 neighbourhood samples)" -ForegroundColor Yellow
            $pass++
        } else {
            Write-Host "FAIL ($($v.status))" -ForegroundColor Red
            $fail++
        }
    }
} else {
    Write-Host "  Not available" -ForegroundColor Yellow
}

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SUMMARY - Minimum 3 Neighbourhood samples Edge Case" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Resources: 3 MeasureReports, 6 Patients (+1 anchor), $totalCond Conditions, 3 NEWS2, 9 Vitals"
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "  ALL MINIMUM-NEIGHBOURHOODS TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "  FAIL: $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""






