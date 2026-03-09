<#
.SYNOPSIS
    Spearman rho correlation test - ORIGINAL directions.
    Creates 20 neighbourhood samples and validates Spearman rank correlation coefficient.

.DESCRIPTION
    Posts FHIR data for neighbourhood samples F01-F20 in neighborhood N81, then validates
    that the Spearman rho coefficient has the expected direction, magnitude,
    and statistical significance for all 5 correlation pairs.

    Expected directions (Original):
      careUnits  vs news2        -> NEGATIVE  (high care = low NEWS2)
      income     vs conditions   -> NEGATIVE  (rich = fewer conditions)
      meanAge    vs heartRate    -> POSITIVE  (older = higher HR)
      meanAge    vs systolicBP   -> POSITIVE  (older = higher SBP)
      meanAge    vs diastolicBP  -> POSITIVE  (older = higher DBP)

    Coefficient validated: Spearman rho (rank-based monotonic correlation)

    Data design (non-linear, monotonic):
      income   : geometric growth (~x1.13/step) - non-linear spacing exercises
                 Spearman vs Pearson distinction (rank order preserved, raw
                 values are NOT linearly spaced)
      age      : quadratic progression (slow start, accelerating)
      news2    : logistic / exponential-saturation curve (0.3 -> ~19.6)
      care/cond: step-wise with deliberate plateaus (tied ranks, averaged
                 by Spearman)
      HR/SBP/DBP: logarithmic saturation with age (fast rise then plateau)
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# -- Coefficient under test ------------------------------------------------
$CoefficientName  = "Spearman rho"
$CoefficientField = "spearman"
$PValueField      = "spearmanPValue"

# ==========================================================================
# DATA - 20 neighbourhood samples, non-linear monotonic (geometric income, logistic news2,
#        logarithmic HR/BP, quadratic age, step-wise care/cond with ties)
# Spearman rho detects monotonic relationships regardless of linearity;
# these patterns yield |rho| > 0.95 while Pearson r would be notably lower.
# ==========================================================================
$neighbourhoodSamples = @(
    @{ id="F01"; income=15000;  care=15; age=28; news2=0.3;  cond=14; hr=58;  sbp=96;  dbp=58 }
    @{ id="F02"; income=17000;  care=14; age=30; news2=0.5;  cond=13; hr=62;  sbp=100; dbp=61 }
    @{ id="F03"; income=19200;  care=14; age=33; news2=0.8;  cond=12; hr=65;  sbp=105; dbp=63 }
    @{ id="F04"; income=21700;  care=13; age=37; news2=1.2;  cond=11; hr=68;  sbp=110; dbp=66 }
    @{ id="F05"; income=24500;  care=12; age=42; news2=1.8;  cond=10; hr=71;  sbp=116; dbp=69 }
    @{ id="F06"; income=27700;  care=11; age=47; news2=2.6;  cond=9;  hr=74;  sbp=122; dbp=72 }
    @{ id="F07"; income=31300;  care=10; age=52; news2=3.7;  cond=8;  hr=77;  sbp=127; dbp=74 }
    @{ id="F08"; income=35400;  care=9;  age=56; news2=5.1;  cond=7;  hr=79;  sbp=132; dbp=76 }
    @{ id="F09"; income=40000;  care=9;  age=60; news2=6.8;  cond=7;  hr=81;  sbp=136; dbp=78 }
    @{ id="F10"; income=45200;  care=8;  age=63; news2=8.6;  cond=6;  hr=83;  sbp=139; dbp=80 }
    @{ id="F11"; income=51100;  care=7;  age=66; news2=10.4; cond=5;  hr=84;  sbp=142; dbp=81 }
    @{ id="F12"; income=57700;  care=7;  age=69; news2=12.1; cond=5;  hr=85;  sbp=145; dbp=82 }
    @{ id="F13"; income=65200;  care=6;  age=71; news2=13.7; cond=4;  hr=86;  sbp=148; dbp=83 }
    @{ id="F14"; income=73700;  care=5;  age=73; news2=15.1; cond=3;  hr=87;  sbp=150; dbp=84 }
    @{ id="F15"; income=83300;  care=5;  age=75; news2=16.3; cond=3;  hr=88;  sbp=152; dbp=85 }
    @{ id="F16"; income=94100;  care=4;  age=77; news2=17.3; cond=2;  hr=89;  sbp=154; dbp=86 }
    @{ id="F17"; income=106300; care=3;  age=79; news2=18.1; cond=2;  hr=90;  sbp=156; dbp=87 }
    @{ id="F18"; income=120100; care=3;  age=82; news2=18.7; cond=1;  hr=91;  sbp=159; dbp=89 }
    @{ id="F19"; income=135700; care=2;  age=85; news2=19.2; cond=1;  hr=93;  sbp=162; dbp=91 }
    @{ id="F20"; income=153400; care=1;  age=88; news2=19.6; cond=0;  hr=95;  sbp=166; dbp=93 }
)

# Expected directions & minimum |coefficient| thresholds (Spearman is rank-based, slightly lower thresholds)
$expected = @{
    "careUnits_vs_news2"          = @{ dir = "negative"; minR = 0.85 }
    "averageIncome_vs_conditions" = @{ dir = "negative"; minR = 0.85 }
    "meanAge_vs_heartRate"        = @{ dir = "positive"; minR = 0.90 }
    "meanAge_vs_systolicBP"       = @{ dir = "positive"; minR = 0.90 }
    "meanAge_vs_diastolicBP"      = @{ dir = "positive"; minR = 0.90 }
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
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $raw = $_.ErrorDetails.Message
            try {
                $detail = $raw | ConvertFrom-Json
                if ($detail.issue) {
                    foreach ($iss in $detail.issue) {
                        Write-Host "    FHIR: $($iss.diagnostics)" -ForegroundColor Yellow
                    }
                }
            } catch {
                $snip = $raw.Substring(0, [Math]::Min(500, $raw.Length))
                Write-Host "    $snip" -ForegroundColor Yellow
            }
        }
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

# ==========================================================================
# STEP 0 - Health check
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  $CoefficientName Test - ORIGINAL Directions ($City)" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
Write-Host ""
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
    name = @(@{ family = "SpearmanPos"; given = @("Anchor") })
}
$anchorJson = $anchorBody | ConvertTo-Json -Depth 10
$anchorResp = Invoke-RestMethod -Uri "$FhirBase/Patient" -Method POST `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($anchorJson)) `
    -ContentType "application/fhir+json; charset=utf-8"
$anchorId = $anchorResp.id
Write-Host "OK (Patient/$anchorId)" -ForegroundColor Green

# ==========================================================================
# STEP 2 - MeasureReports (20 neighbourhood samples)
# ==========================================================================
Write-Host ""
Write-Host "[2] Creating MeasureReports (20 neighbourhood samples) ..."

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
# STEP 3 - Patients (3 per neighbourhood sample = 60)
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (60) ..."

$patEntries = @()
foreach ($b in $neighbourhoodSamples) {
    for ($p = 1; $p -le 3; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "SprmPos-$($b.id)"; given = @("P$p") })
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

$condCodes = @(
    @{ code="38341003";  display="Hypertension" }
    @{ code="44054006";  display="Type 2 Diabetes" }
    @{ code="195967001"; display="Asthma" }
    @{ code="13645005";  display="COPD" }
    @{ code="84114007";  display="Heart Failure" }
    @{ code="73211009";  display="Diabetes Mellitus" }
    @{ code="22298006";  display="Myocardial Infarction" }
    @{ code="49436004";  display="Atrial Fibrillation" }
)

$condEntries = @()
foreach ($b in $neighbourhoodSamples) {
    for ($c = 1; $c -le $b.cond; $c++) {
        $cc = $condCodes[($c - 1) % $condCodes.Count]
        $condEntries += @{
            resource = @{
                resourceType   = "Condition"
                clinicalStatus = @{ coding = @(@{ system="http://terminology.hl7.org/CodeSystem/condition-clinical"; code="active" }) }
                code      = @{ coding = @(@{ system="http://snomed.info/sct"; code=$cc.code; display=$cc.display }) }
                subject   = @{ reference = "Patient/$anchorId" }
                extension = @((Make-LocationExtension -NeighborhoodId $b.id -City $City))
            }
            request = @{ method = "POST"; url = "Condition" }
        }
    }
}
if ($condEntries.Count -gt 0) {
    Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$condEntries } -Label "Conditions"
} else {
    Write-Host "  (no conditions)" -ForegroundColor DarkGray
}

# ==========================================================================
# STEP 5 - NEWS2 Observations (20)
# ==========================================================================
Write-Host ""
Write-Host "[5] Creating NEWS2 Observations (20) ..."

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
Post-FhirBundle -Bundle @{ resourceType="Bundle"; type="transaction"; entry=$news2Entries } -Label "NEWS2 Observations"

# ==========================================================================
# STEP 6 - Vital Signs (3 types x 20 = 60)
# ==========================================================================
Write-Host ""
Write-Host "[6] Creating Vital Signs Observations (60) ..."

$vitalTypes = @(
    @{ field="hr";  loincCode="8867-4"; display="Heart Rate";              unit="beats/minute"; ucum="/min" }
    @{ field="sbp"; loincCode="8480-6"; display="Systolic Blood Pressure"; unit="mmHg";         ucum="mm[Hg]" }
    @{ field="dbp"; loincCode="8462-4"; display="Diastolic Blood Pressure";unit="mmHg";         ucum="mm[Hg]" }
)

$vitalEntries = @()
foreach ($b in $neighbourhoodSamples) {
    foreach ($vt in $vitalTypes) {
        $val = $b[$vt.field]
        $sampleCount = 3 + [int]([Math]::Floor(([Math]::Abs($val)) % 7))
        $vitalEntries += @{
            resource = @{
                resourceType = "Observation"; status = "final"
                category = @(@{ coding = @(@{ system="http://terminology.hl7.org/CodeSystem/observation-category"; code="vital-signs-average"; display="Vital Signs Average" }) })
                code = @{ coding = @(@{ system="http://loinc.org"; code=$vt.loincCode; display=$vt.display }); text=$vt.display }
                valueQuantity = @{ value=[decimal]$val; unit=$vt.unit; system="http://unitsofmeasure.org"; code=$vt.ucum }
                extension = @(
                    (Make-LocationExtension -NeighborhoodId $b.id -City $City)
                    @{ url="http://observation-sample-count"; valueInteger=$sampleCount }
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
Write-Host "[7] Waiting 3 seconds for aggregation ..."
Start-Sleep -Seconds 3

# ==========================================================================
# STEP 8 - Call correlation endpoint
# ==========================================================================
Write-Host ""
Write-Host "[8] GET /analytics/neigh-correlations?city=$City ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-correlations?city=$City" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - city=$($corr.city), neighSamples=$($corr.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ==========================================================================
# STEP 9 - Call spatial autocorrelation endpoint
# ==========================================================================
Write-Host ""
Write-Host "[9] GET /analytics/neigh-spatial-autocorrelation?city=$City ..."
$moran = $null
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-spatial-autocorrelation?city=$City" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - neighSamples=$($moran.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# ==========================================================================
# STEP 10 - VALIDATE: Spearman rho (original directions)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  VALIDATION: $CoefficientName - Original Directions" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  City : $($corr.city)"
Write-Host "  Neighbourhood sample count  : $($corr.neighCount)"
Write-Host ""

$pass = 0
$fail = 0

if ($corr.correlations) {
    foreach ($item in $corr.correlations) {
        $label   = $item.label
        $pearson = $item.pearson
        $spear   = $item.spearman
        $kendall = $item.kendallTau
        $pP      = $item.pearsonPValue
        $pS      = $item.spearmanPValue
        $pK      = $item.kendallTauPValue
        $perm    = $item.permutationTest
        $status  = $item.status
        $n       = $item.samples

        Write-Host "  -------------------------------------------------------" -ForegroundColor DarkGray
        Write-Host "  $label" -ForegroundColor White
        Write-Host "    Status: $status  |  Samples: $n"

        if ($status -eq "ok") {
            # Display all three coefficients
            $pPStr = if ($null -ne $pP) { "p={0:F6} {1}" -f $pP, (Sig-Stars $pP) } else { "p=N/A" }
            $pSStr = if ($null -ne $pS) { "p={0:F6} {1}" -f $pS, (Sig-Stars $pS) } else { "p=N/A" }
            $pKStr = if ($null -ne $pK) { "p={0:F6} {1}" -f $pK, (Sig-Stars $pK) } else { "p=N/A" }
            Write-Host ("    Pearson  r   = {0,8:F4}   ({1})" -f $pearson, $pPStr)
            Write-Host ("    Spearman rho = {0,8:F4}   ({1})" -f $spear, $pSStr)
            Write-Host ("    Kendall  tau = {0,8:F4}   ({1})" -f $kendall, $pKStr)

            if ($perm -and $perm.status -eq "ok") {
                Write-Host ("    Permutation  : p_perm={0:F4} {1}  (n={2})" -f $perm.pValue, (Sig-Stars $perm.pValue), $perm.permutations)
            }

            # >>> VALIDATE Spearman rho <<<
            $coeff = $item.$CoefficientField
            $pVal  = $item.$PValueField
            $exp   = $expected[$label]

            if ($exp) {
                $dirOk = $false
                $magOk = $false
                if ($exp.dir -eq "positive" -and $coeff -gt 0) { $dirOk = $true }
                if ($exp.dir -eq "negative" -and $coeff -lt 0) { $dirOk = $true }
                if ([Math]::Abs($coeff) -ge $exp.minR)         { $magOk = $true }
                $sigOk = ($null -ne $pVal) -and ($pVal -lt 0.05)

                if ($dirOk -and $magOk -and $sigOk) {
                    Write-Host "    >> PASS [$CoefficientName]: dir=$($exp.dir), |coeff|>=$($exp.minR), significant" -ForegroundColor Green
                    $pass++
                } else {
                    $reasons = @()
                    if (-not $dirOk) { $reasons += "wrong direction (expected $($exp.dir))" }
                    if (-not $magOk) { $reasons += ("|coeff|={0:F4} < {1}" -f [Math]::Abs($coeff), $exp.minR) }
                    if (-not $sigOk) { $reasons += "not significant (p=$pVal)" }
                    Write-Host "    >> FAIL [$CoefficientName]: $($reasons -join '; ')" -ForegroundColor Red
                    $fail++
                }
            }
        } else {
            Write-Host "    (insufficient data)" -ForegroundColor Yellow
            if ($expected[$label]) { $fail++ }
        }
    }
} else {
    Write-Host "  No correlations returned!" -ForegroundColor Red
    $fail = 5
}

# ==========================================================================
# STEP 11 - Moran's I
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  SPATIAL AUTOCORRELATION (MORAN I)" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
Write-Host ""

if ($moran -and $moran.variables) {
    Write-Host "  Neighbourhood samples: $($moran.neighCount)  |  Permutations: $($moran.permutations)"
    Write-Host ""
    foreach ($v in $moran.variables) {
        Write-Host "  -------------------------------------------------------" -ForegroundColor DarkGray
        Write-Host "  $($v.variable)  (n=$($v.n))" -ForegroundColor White
        if ($v.status -eq "ok") {
            $sigStr = if ($null -ne $v.pValue) { Sig-Stars $v.pValue } else { "?" }
            Write-Host ("    I={0,8}  E[I]={1}  z={2,8}  p={3} {4}  pattern={5}" -f $v.moranI, $v.expectedI, $v.zScore, $v.pValue, $sigStr, $v.pattern)
        } else {
            Write-Host "    $($v.status): $($v.interpretation)" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "  Not available" -ForegroundColor Yellow
}

# ==========================================================================
# STEP 12 - Raw neighbourhood source data
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  NEIGHBOURHOOD SOURCE DATA" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
Write-Host ""
if ($corr.neighRows) {
    Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Neighbourhood","Income","Care","Age","NEWS2","Cond","HR","SBP","DBP")
    Write-Host ("  " + ("-" * 68)) -ForegroundColor DarkGray
    foreach ($row in $corr.neighRows | Sort-Object {




