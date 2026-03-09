<#
.SYNOPSIS
    Spearman rho correlation test - INVERSE directions.
    Creates 20 neighbourhood samples and validates Spearman rank correlation coefficient
    with ALL directions flipped compared to the original test.

.DESCRIPTION
    Posts FHIR data for neighbourhood samples G01-G20 in neighborhood N81, then validates
    that the Spearman rho coefficient has the expected INVERSE direction,
    magnitude, and statistical significance for all 5 correlation pairs.

    Expected directions (Inverse):
      careUnits  vs news2        -> POSITIVE  (high care = high NEWS2)
      income     vs conditions   -> POSITIVE  (rich = more conditions)
      meanAge    vs heartRate    -> NEGATIVE  (older = lower HR)
      meanAge    vs systolicBP   -> NEGATIVE  (older = lower SBP)
      meanAge    vs diastolicBP  -> NEGATIVE  (older = lower DBP)

    Coefficient validated: Spearman rho (rank-based monotonic correlation)

    Data design (non-linear, monotonic - all directions INVERSE):
      income   : same geometric growth as positive test (~x1.13/step)
      age      : same quadratic progression
      care/cond: now step-wise INCREASING (ties preserved, direction flipped)
      news2    : same logistic curve, now correlates POSITIVELY with care
      HR/SBP/DBP: logarithmic saturation, now DECREASING with age
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "C91"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# -- Coefficient under test ------------------------------------------------
$CoefficientName  = "Spearman rho"
$CoefficientField = "spearman"
$PValueField      = "spearmanPValue"

# ==========================================================================
# DATA - 20 neighbourhood samples, INVERSE direction pattern, non-linear monotonic
# Mirror of spearman-positive: same income/age non-linear progression,
# care/cond flipped to INCREASE, HR/SBP/DBP flipped to DECREASE with age.
# ==========================================================================
$neighborhoodSamples = @(
    @{ id="G01"; income=15000;  care=1;  age=28; news2=0.3;  cond=0;  hr=95;  sbp=166; dbp=93 }
    @{ id="G02"; income=17000;  care=1;  age=30; news2=0.5;  cond=1;  hr=93;  sbp=162; dbp=91 }
    @{ id="G03"; income=19200;  care=2;  age=33; news2=0.8;  cond=1;  hr=91;  sbp=159; dbp=89 }
    @{ id="G04"; income=21700;  care=3;  age=37; news2=1.2;  cond=2;  hr=90;  sbp=156; dbp=87 }
    @{ id="G05"; income=24500;  care=3;  age=42; news2=1.8;  cond=2;  hr=89;  sbp=154; dbp=86 }
    @{ id="G06"; income=27700;  care=4;  age=47; news2=2.6;  cond=3;  hr=88;  sbp=152; dbp=85 }
    @{ id="G07"; income=31300;  care=5;  age=52; news2=3.7;  cond=3;  hr=87;  sbp=150; dbp=84 }
    @{ id="G08"; income=35400;  care=6;  age=56; news2=5.1;  cond=4;  hr=86;  sbp=148; dbp=83 }
    @{ id="G09"; income=40000;  care=7;  age=60; news2=6.8;  cond=4;  hr=85;  sbp=145; dbp=82 }
    @{ id="G10"; income=45200;  care=7;  age=63; news2=8.6;  cond=5;  hr=83;  sbp=142; dbp=81 }
    @{ id="G11"; income=51100;  care=8;  age=66; news2=10.4; cond=5;  hr=81;  sbp=139; dbp=80 }
    @{ id="G12"; income=57700;  care=9;  age=69; news2=12.1; cond=6;  hr=79;  sbp=136; dbp=78 }
    @{ id="G13"; income=65200;  care=9;  age=71; news2=13.7; cond=7;  hr=77;  sbp=132; dbp=76 }
    @{ id="G14"; income=73700;  care=10; age=73; news2=15.1; cond=7;  hr=74;  sbp=127; dbp=74 }
    @{ id="G15"; income=83300;  care=11; age=75; news2=16.3; cond=8;  hr=71;  sbp=122; dbp=72 }
    @{ id="G16"; income=94100;  care=12; age=77; news2=17.3; cond=9;  hr=68;  sbp=116; dbp=69 }
    @{ id="G17"; income=106300; care=13; age=79; news2=18.1; cond=10; hr=65;  sbp=110; dbp=66 }
    @{ id="G18"; income=120100; care=13; age=82; news2=18.7; cond=11; hr=62;  sbp=105; dbp=63 }
    @{ id="G19"; income=135700; care=14; age=85; news2=19.2; cond=12; hr=59;  sbp=100; dbp=61 }
    @{ id="G20"; income=153400; care=15; age=88; news2=19.6; cond=13; hr=56;  sbp=96;  dbp=58 }
)

# Expected INVERSE directions & minimum |coefficient| thresholds
$expected = @{
    "careUnits_vs_news2"          = @{ dir = "positive"; minR = 0.85 }
    "averageIncome_vs_conditions" = @{ dir = "positive"; minR = 0.85 }
    "meanAge_vs_heartRate"        = @{ dir = "negative"; minR = 0.90 }
    "meanAge_vs_systolicBP"       = @{ dir = "negative"; minR = 0.90 }
    "meanAge_vs_diastolicBP"      = @{ dir = "negative"; minR = 0.90 }
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

# ==========================================================================
# STEP 0 - Health check
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host "  $CoefficientName Test - INVERSE Directions (${City})" -ForegroundColor DarkGreen
Write-Host "================================================================" -ForegroundColor DarkGreen
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
    name = @(@{ family = "SpearmanNeg"; given = @("Anchor") })
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
# STEP 3 - Patients (3 per neighbourhood sample = 60)
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (60) ..."

$patEntries = @()
foreach ($b in $neighborhoodSamples) {
    for ($p = 1; $p -le 3; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "SprmNeg-$($b.id)"; given = @("P$p") })
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
foreach ($b in $neighborhoodSamples) {
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
foreach ($b in $neighborhoodSamples) {
    foreach ($vt in $vitalTypes) {
        $val = $b[$vt.field]
        $sampleCount = 3
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
Write-Host "[8] GET /analytics/neigh-correlations ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-correlations" `
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
Write-Host "[9] GET /analytics/neigh-spatial-autocorrelation ..."
$moran = $null
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-spatial-autocorrelation" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - neighSamples=$($moran.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# ==========================================================================
# STEP 10 - VALIDATE: Spearman rho (INVERSE directions)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host "  VALIDATION: $CoefficientName - INVERSE Directions" -ForegroundColor DarkGreen
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host ""
Write-Host "  Neighborhood : $($corr.city)"
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
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host "  SPATIAL AUTOCORRELATION (MORAN I)" -ForegroundColor DarkGreen
Write-Host "================================================================" -ForegroundColor DarkGreen
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
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host "  NEIGHBOURHOOD SOURCE DATA" -ForegroundColor DarkGreen
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host ""
if ($corr.neighRows) {
    Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Neighbourhood","Income","Care","Age","NEWS2","Cond","HR","SBP","DBP")
    Write-Host ("  " + ("-" * 68)) -ForegroundColor DarkGray
    foreach ($row in $corr.neighRows | Sort-Object { $_.neighbourhood }) {
        Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f $row.neighbourhood,$row.averageIncome,$row.careUnits,$row.meanAge,$row.avgNews2,$row.conditionCount,$row.heartRate,$row.systolicBP,$row.diastolicBP)
    }
}

# ==========================================================================
# STEP 13 - Direction comparison table
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host "  DIRECTION COMPARISON: Original vs Inverse" -ForegroundColor DarkGreen
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host ""
Write-Host "  Correlation                  Original    Inverse (this test)" -ForegroundColor White
Write-Host "  -----------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "  careUnits_vs_news2           NEGATIVE    POSITIVE"
Write-Host "  averageIncome_vs_conditions  NEGATIVE    POSITIVE"
Write-Host "  meanAge_vs_heartRate         POSITIVE    NEGATIVE"
Write-Host "  meanAge_vs_systolicBP        POSITIVE    NEGATIVE"
Write-Host "  meanAge_vs_diastolicBP       POSITIVE    NEGATIVE"
Write-Host ""

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host "  SUMMARY - $CoefficientName, INVERSE Directions" -ForegroundColor DarkGreen
Write-Host "================================================================" -ForegroundColor DarkGreen
Write-Host ""
Write-Host "  Resources: 20 MeasureReports, 60 Patients (+1 anchor), $totalCond Conditions, 20 NEWS2, 60 Vitals"
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "  ALL $CoefficientName INVERSE TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "  FAIL: $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""
Write-Host "  Significance: *** p<0.001  ** p<0.01  * p<0.05  ns p>=0.05"
Write-Host ""






