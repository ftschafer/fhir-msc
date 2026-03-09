<#
.SYNOPSIS
    Kendall tau correlation test - ORIGINAL directions.
    Creates 20 neighbourhood samples and validates Kendall tau B concordance coefficient.

.DESCRIPTION
    Posts FHIR data for neighbourhood samples H01-H20 in neighborhood N81, then validates
    that the Kendall tau B coefficient has the expected direction, magnitude,
    and statistical significance for all 5 correlation pairs.

    Expected directions (Original):
      careUnits  vs news2        -> NEGATIVE  (high care = low NEWS2)
      income     vs conditions   -> NEGATIVE  (rich = fewer conditions)
      meanAge    vs heartRate    -> POSITIVE  (older = higher HR)
      meanAge    vs systolicBP   -> POSITIVE  (older = higher SBP)
      meanAge    vs diastolicBP  -> POSITIVE  (older = higher DBP)

    Coefficient validated: Kendall tau B (concordance-based correlation)
    Note: Kendall tau values are typically smaller than Pearson/Spearman
    for the same data, so thresholds are set lower (0.70 / 0.80).

    Data design (ordinal grouped, tie-rich - optimal for Kendall tau-B):
      5 groups of 4 neighbourhood samples each with clear between-group gaps and
      deliberate within-group ties. Kendall tau-B tie-correction factor
      sqrt((n0-n1)(n0-n2)) is exercised by repeated care/cond values.
      income: 5 well-separated bands (14k-19k, 27k-37k, 52k-72k,
              97k-137k, 172k-247k) - ordinal socioeconomic tiers.
      care/cond: ordinal levels with 2 ties per level per group.
      HR/SBP/DBP: near-constant within each group (plateau structure).
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# -- Coefficient under test ------------------------------------------------
$CoefficientName  = "Kendall tau"
$CoefficientField = "kendallTau"
$PValueField      = "kendallTauPValue"

# ==========================================================================
# DATA - 20 neighbourhood samples in 5 ordinal tiers of 4 neighbourhood samples each
# Tie structure: care/cond repeat within each 4-neighbourhood-sample tier, giving Kendall
# tau-B its characteristic tie-correction advantage over Spearman.
# Between-tier gaps are large; within-tier variation is small.
# ==========================================================================
$neighbourhoodSamples = @(
    # Tier 1 - Deprived (highest care needs, lowest income, youngest)
    @{ id="H01"; income=14000;  care=14; age=28; news2=0.5;  cond=13; hr=60;  sbp=97;  dbp=60 }
    @{ id="H02"; income=15500;  care=13; age=31; news2=0.8;  cond=12; hr=62;  sbp=100; dbp=62 }
    @{ id="H03"; income=17000;  care=13; age=34; news2=1.0;  cond=12; hr=64;  sbp=103; dbp=64 }
    @{ id="H04"; income=19000;  care=12; age=37; news2=1.4;  cond=11; hr=66;  sbp=106; dbp=66 }
    # Tier 2 - Low-income
    @{ id="H05"; income=27000;  care=10; age=44; news2=2.8;  cond=9;  hr=73;  sbp=118; dbp=73 }
    @{ id="H06"; income=30000;  care=10; age=47; news2=3.5;  cond=8;  hr=75;  sbp=121; dbp=75 }
    @{ id="H07"; income=33000;  care=9;  age=50; news2=4.3;  cond=8;  hr=77;  sbp=124; dbp=77 }
    @{ id="H08"; income=37000;  care=9;  age=53; news2=5.2;  cond=7;  hr=79;  sbp=127; dbp=79 }
    # Tier 3 - Moderate-income
    @{ id="H09"; income=52000;  care=7;  age=59; news2=8.0;  cond=5;  hr=83;  sbp=137; dbp=83 }
    @{ id="H10"; income=58000;  care=7;  age=62; news2=9.5;  cond=5;  hr=85;  sbp=140; dbp=85 }
    @{ id="H11"; income=64000;  care=6;  age=65; news2=11.0; cond=4;  hr=87;  sbp=143; dbp=87 }
    @{ id="H12"; income=72000;  care=6;  age=68; news2=12.4; cond=4;  hr=88;  sbp=146; dbp=88 }
    # Tier 4 - Well-off
    @{ id="H13"; income=97000;  care=4;  age=72; news2=14.8; cond=3;  hr=90;  sbp=153; dbp=90 }
    @{ id="H14"; income=109000; care=4;  age=75; news2=16.0; cond=2;  hr=91;  sbp=156; dbp=91 }
    @{ id="H15"; income=122000; care=3;  age=77; news2=17.0; cond=2;  hr=92;  sbp=158; dbp=92 }
    @{ id="H16"; income=137000; care=3;  age=80; news2=17.9; cond=2;  hr=93;  sbp=161; dbp=93 }
    # Tier 5 - Affluent (lowest care needs, highest income, oldest)
    @{ id="H17"; income=172000; care=2;  age=82; news2=18.5; cond=1;  hr=94;  sbp=163; dbp=94 }
    @{ id="H18"; income=194000; care=2;  age=84; news2=19.0; cond=1;  hr=95;  sbp=166; dbp=95 }
    @{ id="H19"; income=219000; care=1;  age=87; news2=19.4; cond=0;  hr=96;  sbp=168; dbp=96 }
    @{ id="H20"; income=247000; care=1;  age=89; news2=19.7; cond=0;  hr=97;  sbp=171; dbp=97 }
)

# Expected directions & minimum |coefficient| thresholds
# Kendall tau is typically smaller than Pearson/Spearman -> lower thresholds
$expected = @{
    "careUnits_vs_news2"          = @{ dir = "negative"; minR = 0.70 }
    "averageIncome_vs_conditions" = @{ dir = "negative"; minR = 0.70 }
    "meanAge_vs_heartRate"        = @{ dir = "positive"; minR = 0.80 }
    "meanAge_vs_systolicBP"       = @{ dir = "positive"; minR = 0.80 }
    "meanAge_vs_diastolicBP"      = @{ dir = "positive"; minR = 0.80 }
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
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  $CoefficientName Test - ORIGINAL Directions ($City)" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
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
    name = @(@{ family = "KendallPos"; given = @("Anchor") })
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
                name      = @(@{ family = "KndlPos-$($b.id)"; given = @("P$p") })
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
# STEP 10 - VALIDATE: Kendall tau (original directions)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  VALIDATION: $CoefficientName - Original Directions" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
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

            # >>> VALIDATE Kendall tau <<<
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
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  SPATIAL AUTOCORRELATION (MORAN I)" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
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
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  NEIGHBOURHOOD SOURCE DATA" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host ""
if ($corr.neighRows) {
    Write-Host ("  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Neighbourhood","Income","Care","Age","NEWS2","Cond","HR","SBP","DBP")
    Write-Host ("  " + ("-" * 68)) -ForegroundColor DarkGray
    foreach ($row in $corr.neighRows | Sort-Object {




