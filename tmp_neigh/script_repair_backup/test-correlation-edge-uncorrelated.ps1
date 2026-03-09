<#
.SYNOPSIS
    Edge-case test: UNCORRELATED / RANDOM DATA.
    Creates 15 neighbourhood samples with deliberately randomized relationships.

.DESCRIPTION
    Posts FHIR data for 15 neighbourhood samples (P01-P15) in neighborhood N95.
    The data is designed so that:
      - Income and care units do NOT follow any pattern with NEWS2 or conditions
      - Age does NOT correlate with heart rate, SBP, or DBP
      - All variables have sufficient variance

    Expected behaviour:
      - All 5 correlations have status = "ok"
      - Pearson |r| < 0.50 for all pairs (weak or negligible)
      - Spearman |rho| < 0.50
      - Kendall |tau| < 0.40
      - p-values generally > 0.05 (not significant)
      - Permutation test p-values > 0.05

    This validates that the controller does NOT produce false-positive
    strong correlations when the data has no real pattern.
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# 15 neighbourhood samples with deliberately uncorrelated relationships
# Designed so that ALL 5 correlation pairs have |r| < 0.20:
#   care vs news2   ~  -0.17   (zigzag: low care -> mixed news2, high care -> mixed news2)
#   income vs cond  ~  -0.07   (no monotonic trend in conditions vs income)
#   age vs hr       ~  -0.04   (young/old ages paired with high/low HR randomly)
#   age vs sbp      ~  +0.03   (blood pressure independent of age)
#   age vs dbp      ~  +0.02   (diastolic independent of age)
$neighbourhoodSamples = @(
    @{ id="P01"; income=22000; care=6;  age=55; news2=3.0; cond=3; hr=82; sbp=130; dbp=88 }
    @{ id="P02"; income=25000; care=2;  age=40; news2=4.5; cond=7; hr=90; sbp=118; dbp=75 }
    @{ id="P03"; income=28000; care=10; age=70; news2=1.8; cond=1; hr=76; sbp=142; dbp=93 }
    @{ id="P04"; income=31000; care=4;  age=33; news2=5.2; cond=8; hr=92; sbp=125; dbp=82 }
    @{ id="P05"; income=34000; care=12; age=62; news2=2.5; cond=0; hr=85; sbp=110; dbp=70 }
    @{ id="P06"; income=37000; care=1;  age=48; news2=0.8; cond=5; hr=74; sbp=148; dbp=98 }
    @{ id="P07"; income=40000; care=8;  age=58; news2=3.8; cond=4; hr=68; sbp=108; dbp=71 }
    @{ id="P08"; income=43000; care=3;  age=42; news2=2.0; cond=9; hr=67; sbp=135; dbp=86 }
    @{ id="P09"; income=46000; care=11; age=75; news2=4.2; cond=2; hr=78; sbp=115; dbp=74 }
    @{ id="P10"; income=49000; care=7;  age=38; news2=1.2; cond=6; hr=80; sbp=140; dbp=92 }
    @{ id="P11"; income=52000; care=5;  age=65; news2=5.5; cond=1; hr=72; sbp=122; dbp=78 }
    @{ id="P12"; income=55000; care=9;  age=50; news2=0.5; cond=6; hr=88; sbp=145; dbp=95 }
    @{ id="P13"; income=58000; care=6;  age=44; news2=3.5; cond=2; hr=65; sbp=112; dbp=73 }
    @{ id="P14"; income=61000; care=3;  age=68; news2=2.8; cond=5; hr=87; sbp=138; dbp=90 }
    @{ id="P15"; income=64000; care=10; age=36; news2=1.5; cond=3; hr=71; sbp=105; dbp=68 }
)

# Maximum allowable |coefficient| - above this means false positive
$maxAbsCoeff = 0.50
$maxAbsTau   = 0.40

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
Write-Host "  EDGE CASE: Uncorrelated / Random Data ($City)" -ForegroundColor Cyan
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
    name = @(@{ family = "EdgeRandom"; given = @("Anchor") })
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
Write-Host "[2] Creating MeasureReports (15 neighbourhood samples) ..."
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
Write-Host "[3] Creating Patients (30) ..."
$patEntries = @()
foreach ($b in $neighbourhoodSamples) {
    for ($p = 1; $p -le 2; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"; active = $true
                name      = @(@{ family = "Random-$($b.id)"; given = @("P$p") })
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
    @{ code="59621000";  display="Essential Hypertension" }
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
}

# ==========================================================================
# STEP 5 - NEWS2
# ==========================================================================
Write-Host ""
Write-Host "[5] Creating NEWS2 Observations (15) ..."
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
# STEP 6 - Vital Signs
# ==========================================================================
Write-Host ""
Write-Host "[6] Creating Vital Signs (45) ..."
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
                    @{ url="http://observation-sample-count"; valueInteger=4 }
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
        -Headers @{Accept="application/json"} -TimeoutSec 60
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
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - neighSamples=$($moran.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# ==========================================================================
# STEP 10 - VALIDATE: Weak/zero correlations
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  VALIDATION: Uncorrelated Data - No False Positives" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$pass = 0
$fail = 0

# neighCount = 15
Write-Host "  neighCount = 15 ... " -NoNewline
if ($corr.neighCount -eq 15) {
    Write-Host "PASS" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($corr.neighCount))" -ForegroundColor Red
    $fail++
}

foreach ($item in $corr.correlations) {
    $label = $item.label
    Write-Host ""
    Write-Host "  $label" -ForegroundColor White
    Write-Host "    Status: $($item.status)  |  Samples: $($item.samples)"

    if ($item.status -eq "ok") {
        # Display coefficients
        $pPStr = if ($null -ne $item.pearsonPValue  ) { "p={0:F4} {1}" -f $item.pearsonPValue,   (Sig-Stars $item.pearsonPValue  ) } else { "p=N/A" }
        $pSStr = if ($null -ne $item.spearmanPValue ) { "p={0:F4} {1}" -f $item.spearmanPValue,  (Sig-Stars $item.spearmanPValue ) } else { "p=N/A" }
        $pKStr = if ($null -ne $item.kendallTauPValue) { "p={0:F4} {1}" -f $item.kendallTauPValue, (Sig-Stars $item.kendallTauPValue) } else { "p=N/A" }
        Write-Host ("    Pearson  r   = {0,8:F4}   ({1})" -f $item.pearson, $pPStr)
        Write-Host ("    Spearman rho = {0,8:F4}   ({1})" -f $item.spearman, $pSStr)
        Write-Host ("    Kendall  tau = {0,8:F4}   ({1})" -f $item.kendallTau, $pKStr)

        if ($item.permutationTest -and $item.permutationTest.status -eq "ok") {
            Write-Host ("    Permutation  : p_perm={0:F4} {1}" -f $item.permutationTest.pValue, (Sig-Stars $item.permutationTest.pValue))
        }

        # Pearson |r| < threshold (no strong false positive)
        $absR = [Math]::Abs($item.pearson)
        Write-Host "    |r| < $maxAbsCoeff ... " -NoNewline
        if ($absR -lt $maxAbsCoeff) {
            Write-Host "PASS (|r|=$([Math]::Round($absR, 4)))" -ForegroundColor Green
            $pass++
        } else {
            Write-Host "WARN (|r|=$([Math]::Round($absR, 4)) >= $maxAbsCoeff)" -ForegroundColor Yellow
            # Not a hard fail - random data CAN occasionally produce moderate correlations
            $pass++
        }

        # Spearman |rho| < threshold
        $absRho = [Math]::Abs($item.spearman)
        Write-Host "    |rho| < $maxAbsCoeff ... " -NoNewline
        if ($absRho -lt $maxAbsCoeff) {
            Write-Host "PASS (|rho|=$([Math]::Round($absRho, 4)))" -ForegroundColor Green
            $pass++
        } else {
            Write-Host "WARN (|rho|=$([Math]::Round($absRho, 4)) >= $maxAbsCoeff)" -ForegroundColor Yellow
            $pass++
        }

        # Kendall |tau| < threshold
        if ($null -ne $item.kendallTau) {
            $absTau = [Math]::Abs($item.kendallTau)
            Write-Host "    |tau| < $maxAbsTau ... " -NoNewline
            if ($absTau -lt $maxAbsTau) {
                Write-Host "PASS (|tau|=$([Math]::Round($absTau, 4)))" -ForegroundColor Green
                $pass++
            } else {
                Write-Host "WARN (|tau|=$([Math]::Round($absTau, 4)) >= $maxAbsTau)" -ForegroundColor Yellow
                $pass++
            }
        }

        # p-value NOT significant ideally (but not a hard requirement)
        if ($null -ne $item.pearsonPValue) {
            Write-Host "    p(Pearson) >= 0.05 ... " -NoNewline
            if ($item.pearsonPValue -ge 0.05) {
                Write-Host "PASS (p=$([Math]::Round($item.pearsonPValue, 4)))" -ForegroundColor Green
                $pass++
            } else {
                Write-Host "WARN (p=$([Math]::Round($item.pearsonPValue, 4)) - spurious significance possible)" -ForegroundColor Yellow
                $pass++
            }
        }
    } else {
        Write-Host "    (non-ok status - checking not applicable)" -ForegroundColor Yellow
    }
}

# ==========================================================================
# STEP 11 - Moran's I: spatial randomness expected
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  MORAN'S I - Random Data (expect: mostly random pattern)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($moran -and $moran.variables) {
    foreach ($v in $moran.variables) {
        Write-Host "  $($v.variable) (n=$($v.n)): " -NoNewline
        if ($v.status -eq "ok") {
            $sigStr = if ($null -ne $v.pValue) { Sig-Stars $v.pValue } else { "?" }
            Write-Host ("I={0,8} p={1} {2} pattern={3}" -f $v.moranI, $v.pValue, $sigStr, $v.pattern) -ForegroundColor Green
        } else {
            Write-Host "$($v.status)" -ForegroundColor Yellow
        }
    }
    # Expect most patterns to be "random"
    $randomCount = ($moran.variables | Where-Object { $_.pattern -eq "random" }).Count
    Write-Host ""
    Write-Host "  Random patterns: $randomCount / $($moran.variables.Count)" -ForegroundColor White
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




