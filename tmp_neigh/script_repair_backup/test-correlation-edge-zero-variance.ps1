<#
.SYNOPSIS
    Edge-case test: ZERO VARIANCE.
    Creates 5 neighbourhood samples where one variable is constant across all neighbourhood samples.

.DESCRIPTION
    Posts FHIR data for 5 neighbourhood samples (L01-L05) in neighborhood N93.
    all neighbourhood samples have identical heartRate (75), identical systolicBP (120),
    and identical diastolicBP (80).  The other variables (income, care, age,
    NEWS2, conditions) vary normally.

    Expected behaviour:
      - careUnits_vs_news2:          status=ok (both vary)
      - averageIncome_vs_conditions: status=ok (both vary)
      - meanAge_vs_heartRate:        pearson=null (HR constant -> zero variance)
      - meanAge_vs_systolicBP:       pearson=null (SBP constant -> zero variance)
      - meanAge_vs_diastolicBP:      pearson=null (DBP constant -> zero variance)

    The controller returns null for Pearson/Spearman/Kendall when the
    denominator is zero (one variable has zero variance).  The status
    field should be "undefined".

    Moran's I should return "zero_variance" / "constant" for HR, SBP, DBP.
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# 5 neighbourhood samples: vital signs are constant (zero variance)
$neighbourhoodSamples = @(
    @{ id="L01"; income=25000; care=9;  age=35; news2=1.5; cond=5; hr=75; sbp=120; dbp=80 }
    @{ id="L02"; income=35000; care=7;  age=45; news2=2.5; cond=4; hr=75; sbp=120; dbp=80 }
    @{ id="L03"; income=45000; care=5;  age=55; news2=3.5; cond=3; hr=75; sbp=120; dbp=80 }
    @{ id="L04"; income=55000; care=3;  age=65; news2=4.5; cond=2; hr=75; sbp=120; dbp=80 }
    @{ id="L05"; income=65000; care=1;  age=75; news2=5.5; cond=1; hr=75; sbp=120; dbp=80 }
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
            @{ url = "city"; valueString = $City }
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
Write-Host "  EDGE CASE: Zero Variance ($City)" -ForegroundColor Cyan
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
    name = @(@{ family = "EdgeZeroVar"; given = @("Anchor") })
}
$anchorJson = $anchorBody | ConvertTo-Json -Depth 10
$anchorResp = Invoke-RestMethod -Uri "$FhirBase/Patient" -Method POST `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($anchorJson)) `
    -ContentType "application/fhir+json; charset=utf-8"
$anchorId = $anchorResp.id
Write-Host "OK (Patient/$anchorId)" -ForegroundColor Green

# ==========================================================================
# STEP 2 - MeasureReports
# ==========================================================================
Write-Host ""
Write-Host "[2] Creating MeasureReports (5 neighbourhood samples) ..."
$mrEntries = @()
foreach ($b in $neighbourhoodSamples) {
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
# STEP 3 - Patients
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (10) ..."
$patEntries = @()
foreach ($b in $neighbourhoodSamples) {
    for ($p = 1; $p -le 2; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "ZeroVar-$($b.id)"; given = @("P$p") })
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
$totalCond = ($neighbourhoodSamples | ForEach-Object { $_.cond } | Measure-Object -Sum).Sum
Write-Host "[4] Creating Conditions ($totalCond total) ..."
$condEntries = @()
foreach ($b in $neighbourhoodSamples) {
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
Write-Host "[5] Creating NEWS2 Observations (5) ..."
$news2Entries = @()
foreach ($b in $neighbourhoodSamples) {
    $news2Entries += @{
        resource = @{
            resourceType = "Observation"; status = "final"
            identifier = @(@{ system="urn:aggregate:news2"; value="$($b.id)|block-average" })
            code = @{
                coding = @(@{ system="http://snomed.info/sct"; code="1104051000000101"; display="NEWS2 Score" })
                text   = "NEWS2 Neighborhood Average"
            }
            valueQuantity = @{ value=[decimal]$b.news2; unit="score"; system="http://unitsofmeasure.org"; code="{score}" }
            extension     = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
        }
        request = @{ method = "POST"; url = "Observation" }
    }
}
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$news2Entries } -Label "NEWS2"

# ==========================================================================
# STEP 6 - Vital Signs (all identical across neighbourhood samples)
# ==========================================================================
Write-Host ""
Write-Host "[6] Creating Vital Signs (15 - constant values) ..."
$vitalEntries = @()
foreach ($b in $neighbourhoodSamples) {
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
                    @{ url="http://observation-sample-count"; valueInteger=5 }
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
Write-Host "[8] GET /analytics/neigh-correlations?city=$City ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-correlations?city=$City" `
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
Write-Host "[9] GET /analytics/neigh-spatial-autocorrelation?city=$City ..."
$moran = $null
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-spatial-autocorrelation?city=$City" `
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
Write-Host "  VALIDATION: Zero Variance - constant vital signs" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$pass = 0
$fail = 0

# neighCount = 5
Write-Host "  neighCount = 5 ... " -NoNewline
if ($corr.neighCount -eq 5) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($corr.neighCount))" -ForegroundColor Red
    $fail++
}

# --- Correlations that SHOULD work (both variables vary) ---
$workingPairs = @("careUnits_vs_news2", "averageIncome_vs_conditions")
foreach ($label in $workingPairs) {
    $item = $corr.correlations | Where-Object { $_.label -eq $label }
    Write-Host ""
    Write-Host "  $label (both variables vary)" -ForegroundColor White

    Write-Host "    status = ok ... " -NoNewline
    if ($item.status -eq "ok") {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
        $fail++
    }

    Write-Host "    pearson != null ... " -NoNewline
    if ($null -ne $item.pearson) {
        Write-Host "PASS (r=$($item.pearson))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (null)" -ForegroundColor Red
        $fail++
    }

    Write-Host "    spearman != null ... " -NoNewline
    if ($null -ne $item.spearman) {
        Write-Host "PASS (rho=$($item.spearman))" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (null)" -ForegroundColor Red
        $fail++
    }
}

# --- Correlations that SHOULD be undefined (vital sign has zero variance) ---
$zeroPairs = @("meanAge_vs_heartRate", "meanAge_vs_systolicBP", "meanAge_vs_diastolicBP")
foreach ($label in $zeroPairs) {
    $item = $corr.correlations | Where-Object { $_.label -eq $label }
    Write-Host ""
    Write-Host "  $label (zero-variance Y)" -ForegroundColor White

    Write-Host "    status = undefined ... " -NoNewline
    if ($item.status -eq "undefined") {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
        $fail++
    }

    Write-Host "    pearson = null ... " -NoNewline
    if ($null -eq $item.pearson) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (pearson=$($item.pearson))" -ForegroundColor Red
        $fail++
    }

    Write-Host "    spearman = null ... " -NoNewline
    if ($null -eq $item.spearman) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (spearman=$($item.spearman))" -ForegroundColor Red
        $fail++
    }

    Write-Host "    kendallTau = null ... " -NoNewline
    if ($null -eq $item.kendallTau) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (kendallTau=$($item.kendallTau))" -ForegroundColor Red
        $fail++
    }

    # Note field should mention zero variance
    Write-Host "    note mentions zero variance ... " -NoNewline
    if ($item.note -and $item.note -match "zero variance") {
        Write-Host "PASS (`"$($item.note)`")" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (note='$($item.note)')" -ForegroundColor Red
        $fail++
    }
}

# ==========================================================================
# STEP 11 - Moran's I: constant vitals -> zero_variance
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  MORAN'S I - Zero Variance Variables" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$moranConstantVars = @("heartRate", "systolicBP", "diastolicBP")
$moranVaryingVars  = @("avgNews2", "conditionCount", "averageIncome", "careUnits")

if ($moran -and $moran.variables) {
    foreach ($v in $moran.variables) {
        Write-Host "  $($v.variable): " -NoNewline
        if ($moranConstantVars -contains $v.variable) {
            # Expect zero_variance
            if ($v.status -eq "zero_variance") {
                Write-Host "PASS (zero_variance - constant)" -ForegroundColor Green
                $pass++
            } else {
                Write-Host "FAIL (expected zero_variance, got $($v.status))" -ForegroundColor Red
                $fail++
            }
        } else {
            # Varying variables: should compute
            if ($v.status -eq "ok") {
                Write-Host "PASS  I=$($v.moranI) p=$($v.pValue) pattern=$($v.pattern)" -ForegroundColor Green
                $pass++
            } else {
                Write-Host "FAIL ($($v.status))" -ForegroundColor Red
                $fail++
            }
        }
    }
} else {
    Write-Host "  Not available" -ForegroundColor Yellow
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
    foreach ($row in $corr.neighRows | Sort-Object {




