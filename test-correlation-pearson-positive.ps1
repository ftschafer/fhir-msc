<#
.SYNOPSIS
    Pearson r correlation test - ORIGINAL directions.
    Creates 20 blocks and validates Pearson linear correlation coefficient.

.DESCRIPTION
    Posts FHIR data for blocks D01-D20 in neighborhood N81, then validates
    that the Pearson r coefficient has the expected direction, magnitude,
    and statistical significance for all 5 correlation pairs.

    Expected directions (Original):
      careUnits  vs news2        -> NEGATIVE  (high care = low NEWS2)
      income     vs conditions   -> NEGATIVE  (rich = fewer conditions)
      meanAge    vs heartRate    -> POSITIVE  (older = higher HR)
      meanAge    vs systolicBP   -> POSITIVE  (older = higher SBP)
      meanAge    vs diastolicBP  -> POSITIVE  (older = higher DBP)

    Coefficient validated: Pearson r (linear correlation)
#>

param(
    [string]$FhirBase      = "http://localhost:8081/fhir",
    [string]$AnalyticsBase = "http://localhost:8081/analytics",
    [string]$Neighborhood  = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# -- Coefficient under test ------------------------------------------------
$CoefficientName  = "Pearson r"
$CoefficientField = "pearson"
$PValueField      = "pearsonPValue"

# ==========================================================================
# DATA - 20 blocks, original direction pattern
# ==========================================================================
$blocks = @(
    @{ id="D01"; income=20000; care=12; age=65; news2=0.8; cond=8; hr=83;  sbp=138; dbp=89 }
    @{ id="D02"; income=22000; care=11; age=60; news2=1.0; cond=7; hr=80;  sbp=132; dbp=85 }
    @{ id="D03"; income=25000; care=11; age=72; news2=1.2; cond=7; hr=86;  sbp=146; dbp=94 }
    @{ id="D04"; income=27000; care=10; age=48; news2=1.5; cond=6; hr=74;  sbp=118; dbp=76 }
    @{ id="D05"; income=30000; care=9;  age=55; news2=1.7; cond=5; hr=78;  sbp=126; dbp=81 }
    @{ id="D06"; income=32000; care=9;  age=42; news2=2.0; cond=5; hr=71;  sbp=110; dbp=72 }
    @{ id="D07"; income=35000; care=8;  age=68; news2=2.2; cond=4; hr=84;  sbp=142; dbp=91 }
    @{ id="D08"; income=37000; care=7;  age=35; news2=2.5; cond=4; hr=68;  sbp=102; dbp=66 }
    @{ id="D09"; income=40000; care=7;  age=62; news2=2.7; cond=3; hr=81;  sbp=134; dbp=87 }
    @{ id="D10"; income=42000; care=6;  age=50; news2=3.0; cond=3; hr=75;  sbp=120; dbp=78 }
    @{ id="D11"; income=43000; care=6;  age=75; news2=3.2; cond=3; hr=88;  sbp=150; dbp=96 }
    @{ id="D12"; income=45000; care=5;  age=38; news2=3.4; cond=2; hr=69;  sbp=106; dbp=69 }
    @{ id="D13"; income=47000; care=5;  age=58; news2=3.6; cond=2; hr=79;  sbp=130; dbp=84 }
    @{ id="D14"; income=48000; care=4;  age=33; news2=4.2; cond=2; hr=67;  sbp=100; dbp=65 }
    @{ id="D15"; income=50000; care=4;  age=70; news2=4.5; cond=2; hr=85;  sbp=144; dbp=93 }
    @{ id="D16"; income=51000; care=3;  age=45; news2=4.7; cond=1; hr=73;  sbp=114; dbp=74 }
    @{ id="D17"; income=53000; care=3;  age=52; news2=5.1; cond=1; hr=76;  sbp=122; dbp=79 }
    @{ id="D18"; income=55000; care=2;  age=40; news2=5.4; cond=1; hr=70;  sbp=108; dbp=70 }
    @{ id="D19"; income=56000; care=2;  age=30; news2=5.6; cond=1; hr=65;  sbp=96;  dbp=63 }
    @{ id="D20"; income=58000; care=1;  age=46; news2=6.1; cond=0; hr=73;  sbp=115; dbp=75 }
)

# Expected directions & minimum |coefficient| thresholds
$expected = @{
    "careUnits_vs_news2"          = @{ dir = "negative"; minR = 0.90 }
    "averageIncome_vs_conditions" = @{ dir = "negative"; minR = 0.90 }
    "meanAge_vs_heartRate"        = @{ dir = "positive"; minR = 0.95 }
    "meanAge_vs_systolicBP"       = @{ dir = "positive"; minR = 0.95 }
    "meanAge_vs_diastolicBP"      = @{ dir = "positive"; minR = 0.95 }
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
    param([string]$Block, [string]$Neigh)
    return @{
        url = "http://patient-location"
        extension = @(
            @{ url = "block";        valueString = $Block }
            @{ url = "neighborhood"; valueString = $Neigh }
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
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  $CoefficientName Test - ORIGINAL Directions ($Neighborhood)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
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
    name = @(@{ family = "PearsonPos"; given = @("Anchor") })
}
$anchorJson = $anchorBody | ConvertTo-Json -Depth 10
$anchorResp = Invoke-RestMethod -Uri "$FhirBase/Patient" -Method POST `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($anchorJson)) `
    -ContentType "application/fhir+json; charset=utf-8"
$anchorId = $anchorResp.id
Write-Host "OK (Patient/$anchorId)" -ForegroundColor Green

# ==========================================================================
# STEP 2 - MeasureReports (20 blocks)
# ==========================================================================
Write-Host ""
Write-Host "[2] Creating MeasureReports (20 blocks) ..."

$mrEntries = @()
foreach ($b in $blocks) {
    $mrEntries += @{
        resource = @{
            resourceType = "MeasureReport"
            status       = "complete"
            type         = "summary"
            measure      = "Measure/block-health-aggregation"
            date         = "2025-06-15"
            period       = @{ start = "2025-01-01"; end = "2025-12-31" }
            identifier   = @(@{ system = "urn:block:health-aggregation"; value = $b.id })
            extension    = @((Make-LocationExtension -Block $b.id -Neigh $Neighborhood))
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
# STEP 3 - Patients (3 per block = 60)
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (60) ..."

$patEntries = @()
foreach ($b in $blocks) {
    for ($p = 1; $p -le 3; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "PrsnPos-$($b.id)"; given = @("P$p") })
                birthDate = "$birthYear-01-15"
                extension = @((Make-LocationExtension -Block $b.id -Neigh $Neighborhood))
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
$totalCond = ($blocks | ForEach-Object { $_.cond } | Measure-Object -Sum).Sum
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
foreach ($b in $blocks) {
    for ($c = 1; $c -le $b.cond; $c++) {
        $cc = $condCodes[($c - 1) % $condCodes.Count]
        $condEntries += @{
            resource = @{
                resourceType   = "Condition"
                clinicalStatus = @{ coding = @(@{ system="http://terminology.hl7.org/CodeSystem/condition-clinical"; code="active" }) }
                code      = @{ coding = @(@{ system="http://snomed.info/sct"; code=$cc.code; display=$cc.display }) }
                subject   = @{ reference = "Patient/$anchorId" }
                extension = @((Make-LocationExtension -Block $b.id -Neigh $Neighborhood))
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
foreach ($b in $blocks) {
    $news2Entries += @{
        resource = @{
            resourceType = "Observation"; status = "final"
            identifier = @(@{ system="urn:aggregate:news2"; value="$($b.id)|block-average" })
            code = @{
                coding = @(@{ system="http://snomed.info/sct"; code="1104051000000101"; display="NEWS2 Score" })
                text   = "NEWS2 Block Average"
            }
            valueQuantity = @{ value=[decimal]$b.news2; unit="score"; system="http://unitsofmeasure.org"; code="{score}" }
            extension     = @((Make-LocationExtension -Block $b.id -Neigh $Neighborhood))
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
foreach ($b in $blocks) {
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
                    (Make-LocationExtension -Block $b.id -Neigh $Neighborhood)
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
Write-Host "[8] GET /analytics/block-correlations?neighborhood=$Neighborhood ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/block-correlations?neighborhood=$Neighborhood" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - neighborhood=$($corr.neighborhood), blocks=$($corr.blockCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ==========================================================================
# STEP 9 - Call spatial autocorrelation endpoint
# ==========================================================================
Write-Host ""
Write-Host "[9] GET /analytics/spatial-autocorrelation?neighborhood=$Neighborhood ..."
$moran = $null
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/spatial-autocorrelation?neighborhood=$Neighborhood" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - blocks=$($moran.blockCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# ==========================================================================
# STEP 10 - VALIDATE: Pearson r (original directions)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  VALIDATION: $CoefficientName - Original Directions" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Neighborhood : $($corr.neighborhood)"
Write-Host "  Block count  : $($corr.blockCount)"
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

            # >>> VALIDATE Pearson r <<<
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
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SPATIAL AUTOCORRELATION (MORAN I)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($moran -and $moran.variables) {
    Write-Host "  Blocks: $($moran.blockCount)  |  Permutations: $($moran.permutations)  |  Intervention: $($moran.blockLevelInterventionJustified)"
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
# STEP 12 - Raw block data
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  BLOCK DATA" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
if ($corr.blockRows) {
    Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Block","Income","Care","Age","NEWS2","Cond","HR","SBP","DBP")
    Write-Host ("  " + ("-" * 68)) -ForegroundColor DarkGray
    foreach ($row in $corr.blockRows | Sort-Object { $_.block }) {
        Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f $row.block,$row.averageIncome,$row.careUnits,$row.meanAge,$row.avgNews2,$row.conditionCount,$row.heartRate,$row.systolicBP,$row.diastolicBP)
    }
}

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SUMMARY - $CoefficientName, Original Directions" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Resources: 20 MeasureReports, 60 Patients (+1 anchor), $totalCond Conditions, 20 NEWS2, 60 Vitals"
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "  ALL $CoefficientName TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "  FAIL: $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""
Write-Host "  Significance: *** p<0.001  ** p<0.01  * p<0.05  ns p>=0.05"
Write-Host ""
