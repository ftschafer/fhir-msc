<#
.SYNOPSIS
    Edge-case test: SINGLE NEIGHBOURHOOD.
    Creates only 1 neighbourhood sample and validates insufficient_samples handling.

.DESCRIPTION
    Posts FHIR data for a single neighbourhood sample (J01) in neighborhood N91, then calls
    the analytics endpoints and validates:
      - neighCount = 1
      - All 5 correlations return status = "insufficient_samples"
      - All coefficients are null
      - confidence = "low" for all
      - Moran's I variables return "insufficient_data"

    This tests the controller's minimum-sample guard (n < 3).
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "C91"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# Single neighbourhood sample data
$neighborhoodSamples = @(
    @{ id="J01"; income=40000; care=6; age=55; news2=3.0; cond=3; hr=78; sbp=126; dbp=81 }
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

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  EDGE CASE: Single Neighbourhood (${City})" -ForegroundColor Cyan
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
    name = @(@{ family = "EdgeSingle"; given = @("Anchor") })
}
$anchorJson = $anchorBody | ConvertTo-Json -Depth 10
$anchorResp = Invoke-RestMethod -Uri "$FhirBase/Patient" -Method POST `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($anchorJson)) `
    -ContentType "application/fhir+json; charset=utf-8"
$anchorId = $anchorResp.id
Write-Host "OK (Patient/$anchorId)" -ForegroundColor Green

# ==========================================================================
# STEP 2 - MeasureReport (1 neighbourhood sample)
# ==========================================================================
Write-Host ""
Write-Host "[2] Creating MeasureReport (1 neighbourhood sample) ..."
$b = $neighborhoodSamples[0]
$mrEntries = @(@{
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
})
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$mrEntries } -Label "MeasureReport"

# ==========================================================================
# STEP 3 - Patient
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patient ..."
$patEntries = @(@{
    resource = @{
        resourceType = "Patient"; active = $true
        name      = @(@{ family = "EdgeSingle-$($b.id)"; given = @("P1") })
        birthDate = "1970-01-15"
        extension = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
    }
    request = @{ method = "POST"; url = "Patient" }
})
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$patEntries } -Label "Patient"

# ==========================================================================
# STEP 4 - Conditions
# ==========================================================================
Write-Host ""
Write-Host "[4] Creating Conditions ($($b.cond)) ..."
$condEntries = @()
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
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$condEntries } -Label "Conditions"

# ==========================================================================
# STEP 5 - NEWS2 Observation
# ==========================================================================
Write-Host ""
Write-Host "[5] Creating NEWS2 Observation ..."
$news2Entries = @(@{
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
})
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$news2Entries } -Label "NEWS2"

# ==========================================================================
# STEP 6 - Vital Signs
# ==========================================================================
Write-Host ""
Write-Host "[6] Creating Vital Signs (3) ..."
$vitalEntries = @()
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
Write-Host "  VALIDATION: Single Neighbourhood -> insufficient_samples" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$pass = 0
$fail = 0

# neighCount = 1
Write-Host "  Checking neighCount = 1 ... " -NoNewline
if ($corr.neighCount -eq 1) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($corr.neighCount))" -ForegroundColor Red
    $fail++
}

# Each correlation must be insufficient_samples
$expectedLabels = @(
    "careUnits_vs_news2",
    "averageIncome_vs_conditions",
    "meanAge_vs_heartRate",
    "meanAge_vs_systolicBP",
    "meanAge_vs_diastolicBP"
)

foreach ($label in $expectedLabels) {
    $item = $corr.correlations | Where-Object { $_.label -eq $label }
    Write-Host ""
    Write-Host "  $label" -ForegroundColor White

    # Status = insufficient_samples
    Write-Host "    status = insufficient_samples ... " -NoNewline
    if ($item.status -eq "insufficient_samples") {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (status=$($item.status))" -ForegroundColor Red
        $fail++
    }

    # Pearson = null
    Write-Host "    pearson = null ... " -NoNewline
    if ($null -eq $item.pearson) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (pearson=$($item.pearson))" -ForegroundColor Red
        $fail++
    }

    # Spearman = null
    Write-Host "    spearman = null ... " -NoNewline
    if ($null -eq $item.spearman) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (spearman=$($item.spearman))" -ForegroundColor Red
        $fail++
    }

    # Kendall = null
    Write-Host "    kendallTau = null ... " -NoNewline
    if ($null -eq $item.kendallTau) {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (kendallTau=$($item.kendallTau))" -ForegroundColor Red
        $fail++
    }

    # confidence = low
    Write-Host "    confidence = low ... " -NoNewline
    if ($item.confidence -eq "low") {
        Write-Host "PASS" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "FAIL (confidence=$($item.confidence))" -ForegroundColor Red
        $fail++
    }
}

# ==========================================================================
# STEP 11 - Moran's I: all insufficient_data
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  MORAN'S I - Single Neighbourhood (insufficient_data)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($moran -and $moran.variables) {
    foreach ($v in $moran.variables) {
        Write-Host "  $($v.variable): status=$($v.status) ... " -NoNewline
        if ($v.status -eq "insufficient_data") {
            Write-Host "PASS" -ForegroundColor Green
            $pass++
        } else {
            Write-Host "FAIL (expected insufficient_data)" -ForegroundColor Red
            $fail++
        }
    }
} else {
    Write-Host "  No variables returned" -ForegroundColor Yellow
}

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SUMMARY - Single Neighbourhood Edge Case" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "  ALL SINGLE-NEIGHBOURHOOD TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "  FAIL: $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""






