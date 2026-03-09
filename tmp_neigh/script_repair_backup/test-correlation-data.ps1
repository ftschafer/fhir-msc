<#
.SYNOPSIS
    Integration test for CityCorrelationController.
    Creates 20 neighbourhood samples of FHIR data with known correlations and validates analytics endpoints.

.DESCRIPTION
    Posts MeasureReports, Patients, Conditions, Observations (NEWS2 + vital signs)
    for neighbourhood samples B01-B20 in neighborhood N81, then calls:
      GET /analytics/neigh-correlations
      GET /analytics/neigh-spatial-autocorrelation

    Data is designed with deliberate, verifiable correlation patterns:
      careUnits  vs news2        -> strong NEGATIVE  (~-0.99)
      income     vs conditions   -> strong NEGATIVE  (~-0.97)
      meanAge    vs heartRate    -> strong POSITIVE  (~0.99)
      meanAge    vs systolicBP   -> strong POSITIVE  (~0.99)
      meanAge    vs diastolicBP  -> strong POSITIVE  (~0.99)
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# ==========================================================================
# DATA DESIGN - 20 neighbourhood samples with known correlation structure
# ==========================================================================
#
#   income :  20000 -> 58000   (monotonically increasing)
#   care   :  12 -> 1          (monotonically decreasing = negative with income)
#   news2  :  increases as care decreases  (strong NEGATIVE with care)
#   cond   :  8 -> 0           (strong negative with income)
#   age    :  mixed 30-75      (varies independently of income)
#   hr     :  50 + 0.5 x age  (strong positive with age)
#   sbp    :  60 + 1.2 x age  (strong positive with age)
#   dbp    :  40 + 0.75 x age (strong positive with age)
#
$neighbourhoodSamples = @(
    @{ id="B01"; income=20000; care=12; age=65; news2=0.8; cond=8; hr=83;  sbp=138; dbp=89 }
    @{ id="B02"; income=22000; care=11; age=60; news2=1.0; cond=7; hr=80;  sbp=132; dbp=85 }
    @{ id="B03"; income=25000; care=11; age=72; news2=1.2; cond=7; hr=86;  sbp=146; dbp=94 }
    @{ id="B04"; income=27000; care=10; age=48; news2=1.5; cond=6; hr=74;  sbp=118; dbp=76 }
    @{ id="B05"; income=30000; care=9;  age=55; news2=1.7; cond=5; hr=78;  sbp=126; dbp=81 }
    @{ id="B06"; income=32000; care=9;  age=42; news2=2.0; cond=5; hr=71;  sbp=110; dbp=72 }
    @{ id="B07"; income=35000; care=8;  age=68; news2=2.2; cond=4; hr=84;  sbp=142; dbp=91 }
    @{ id="B08"; income=37000; care=7;  age=35; news2=2.5; cond=4; hr=68;  sbp=102; dbp=66 }
    @{ id="B09"; income=40000; care=7;  age=62; news2=2.7; cond=3; hr=81;  sbp=134; dbp=87 }
    @{ id="B10"; income=42000; care=6;  age=50; news2=3.0; cond=3; hr=75;  sbp=120; dbp=78 }
    @{ id="B11"; income=43000; care=6;  age=75; news2=3.2; cond=3; hr=88;  sbp=150; dbp=96 }
    @{ id="B12"; income=45000; care=5;  age=38; news2=3.4; cond=2; hr=69;  sbp=106; dbp=69 }
    @{ id="B13"; income=47000; care=5;  age=58; news2=3.6; cond=2; hr=79;  sbp=130; dbp=84 }
    @{ id="B14"; income=48000; care=4;  age=33; news2=4.2; cond=2; hr=67;  sbp=100; dbp=65 }
    @{ id="B15"; income=50000; care=4;  age=70; news2=4.5; cond=2; hr=85;  sbp=144; dbp=93 }
    @{ id="B16"; income=51000; care=3;  age=45; news2=4.7; cond=1; hr=73;  sbp=114; dbp=74 }
    @{ id="B17"; income=53000; care=3;  age=52; news2=5.1; cond=1; hr=76;  sbp=122; dbp=79 }
    @{ id="B18"; income=55000; care=2;  age=40; news2=5.4; cond=1; hr=70;  sbp=108; dbp=70 }
    @{ id="B19"; income=56000; care=2;  age=30; news2=5.6; cond=1; hr=65;  sbp=96;  dbp=63 }
    @{ id="B20"; income=58000; care=1;  age=46; news2=6.1; cond=0; hr=73;  sbp=115; dbp=75 }
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
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  FHIR Correlation Integration Test - 20 neighbourhood samples" -ForegroundColor Cyan
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
# STEP 1 - Create anchor patient (for Condition.subject references)
# ==========================================================================
Write-Host ""
Write-Host "[1] Creating anchor patient ... " -NoNewline
$anchorBody = @{
    resourceType = "Patient"
    name = @(@{ family = "CorrelationTest"; given = @("Anchor") })
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
            identifier   = @(
                @{ system = "urn:block:health-aggregation"; value = $b.id }
            )
            extension = @(
                (Make-LocationExtension -NeighborhoodId $b.id -City $City)
            )
            group = @(
                @{
                    stratifier = @(
                        @{
                            code    = @(@{ text = "Average Income" })
                            stratum = @(@{ measureScore = @{ value = [decimal]$b.income } })
                        }
                        @{
                            code    = @(@{ text = "Care Units" })
                            stratum = @(@{ measureScore = @{ value = [decimal]$b.care } })
                        }
                        @{
                            code    = @(@{ text = "Mean Age" })
                            stratum = @(@{ measureScore = @{ value = [decimal]$b.age } })
                        }
                    )
                }
            )
        }
        request = @{ method = "POST"; url = "MeasureReport" }
    }
}

$mrBundle = @{ resourceType = "Bundle"; type = "transaction"; entry = $mrEntries }
Post-FhirBundle -Bundle $mrBundle -Label "MeasureReports"

# ==========================================================================
# STEP 3 - Patients (3 per neighbourhood sample = 60 patients)
# ==========================================================================
Write-Host ""
Write-Host "[3] Creating Patients (3 per neighbourhood sample = 60) ..."

$patEntries = @()
foreach ($b in $neighbourhoodSamples) {
    for ($p = 1; $p -le 3; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"
                active       = $true
                name         = @(@{ family = "Test-$($b.id)"; given = @("P$p") })
                birthDate    = "$birthYear-01-15"
                extension    = @(
                    (Make-LocationExtension -NeighborhoodId $b.id -City $City)
                )
            }
            request = @{ method = "POST"; url = "Patient" }
        }
    }
}

$patBundle = @{ resourceType = "Bundle"; type = "transaction"; entry = $patEntries }
Post-FhirBundle -Bundle $patBundle -Label "Patients"

# ==========================================================================
# STEP 4 - Conditions (variable count per neighbourhood sample)
# ==========================================================================
Write-Host ""
$totalCond = ($neighbourhoodSamples | ForEach-Object { $_.cond } | Measure-Object -Sum).Sum
Write-Host "[4] Creating Conditions ($totalCond total across 20 neighbourhood samples) ..."

$condCodes = @(
    @{ code = "38341003";  display = "Hypertension"                          }
    @{ code = "44054006";  display = "Type 2 Diabetes"                       }
    @{ code = "195967001"; display = "Asthma"                                }
    @{ code = "13645005";  display = "Chronic Obstructive Pulmonary Disease" }
    @{ code = "84114007";  display = "Heart Failure"                         }
    @{ code = "73211009";  display = "Diabetes Mellitus"                     }
    @{ code = "22298006";  display = "Myocardial Infarction"                 }
    @{ code = "49436004";  display = "Atrial Fibrillation"                   }
)

$condEntries = @()
foreach ($b in $neighbourhoodSamples) {
    for ($c = 1; $c -le $b.cond; $c++) {
        $cc = $condCodes[($c - 1) % $condCodes.Count]
        $condEntries += @{
            resource = @{
                resourceType   = "Condition"
                clinicalStatus = @{
                    coding = @(
                        @{
                            system = "http://terminology.hl7.org/CodeSystem/condition-clinical"
                            code   = "active"
                        }
                    )
                }
                code = @{
                    coding = @(
                        @{
                            system  = "http://snomed.info/sct"
                            code    = $cc.code
                            display = $cc.display
                        }
                    )
                }
                subject   = @{ reference = "Patient/$anchorId" }
                extension = @(
                    (Make-LocationExtension -NeighborhoodId $b.id -City $City)
                )
            }
            request = @{ method = "POST"; url = "Condition" }
        }
    }
}

if ($condEntries.Count -gt 0) {
    $condBundle = @{ resourceType = "Bundle"; type = "transaction"; entry = $condEntries }
    Post-FhirBundle -Bundle $condBundle -Label "Conditions"
} else {
    Write-Host "  (no conditions to create)" -ForegroundColor DarkGray
}

# ==========================================================================
# STEP 5 - NEWS2 neighborhood-average Observations (20)
# ==========================================================================
Write-Host ""
Write-Host "[5] Creating NEWS2 neighborhood-average Observations (20) ..."

$news2Entries = @()
foreach ($b in $neighbourhoodSamples) {
    $news2Entries += @{
        resource = @{
            resourceType = "Observation"
            status       = "final"
            identifier   = @(
                @{ system = "urn:aggregate:news2"; value = "$($b.id)|block-average" }
            )
            code = @{
                coding = @(
                    @{
                        system  = "http://snomed.info/sct"
                        code    = "1104051000000101"
                        display = "NEWS2 Score"
                    }
                )
                text = "NEWS2 Neighborhood Average"
            }
            valueQuantity = @{
                value  = [decimal]$b.news2
                unit   = "score"
                system = "http://unitsofmeasure.org"
                code   = "{score}"
            }
            extension = @(
                (Make-LocationExtension -NeighborhoodId $b.id -City $City)
            )
        }
        request = @{ method = "POST"; url = "Observation" }
    }
}

$news2Bundle = @{ resourceType = "Bundle"; type = "transaction"; entry = $news2Entries }
Post-FhirBundle -Bundle $news2Bundle -Label "NEWS2 Observations"

# ==========================================================================
# STEP 6 - Vital Signs average Observations (3 types x 20 neighbourhood samples = 60)
# ==========================================================================
Write-Host ""
Write-Host "[6] Creating Vital Signs average Observations (60) ..."

$vitalTypes = @(
    @{ field = "hr";  loincCode = "8867-4"; display = "Heart Rate";              unit = "beats/minute"; ucum = "/min"   }
    @{ field = "sbp"; loincCode = "8480-6"; display = "Systolic Blood Pressure"; unit = "mmHg";         ucum = "mm[Hg]" }
    @{ field = "dbp"; loincCode = "8462-4"; display = "Diastolic Blood Pressure";unit = "mmHg";         ucum = "mm[Hg]" }
)

$vitalEntries = @()
foreach ($b in $neighbourhoodSamples) {
    foreach ($vt in $vitalTypes) {
        $val = $b[$vt.field]
        $sampleCount = 3 + [int]([Math]::Floor(([Math]::Abs($val)) % 7))
        $vitalEntries += @{
            resource = @{
                resourceType = "Observation"
                status       = "final"
                category     = @(
                    @{
                        coding = @(
                            @{
                                system  = "http://terminology.hl7.org/CodeSystem/observation-category"
                                code    = "vital-signs-average"
                                display = "Vital Signs Average"
                            }
                        )
                    }
                )
                code = @{
                    coding = @(
                        @{
                            system  = "http://loinc.org"
                            code    = $vt.loincCode
                            display = $vt.display
                        }
                    )
                    text = $vt.display
                }
                valueQuantity = @{
                    value  = [decimal]$val
                    unit   = $vt.unit
                    system = "http://unitsofmeasure.org"
                    code   = $vt.ucum
                }
                extension = @(
                    (Make-LocationExtension -NeighborhoodId $b.id -City $City)
                    @{ url = "http://observation-sample-count"; valueInteger = $sampleCount }
                )
            }
            request = @{ method = "POST"; url = "Observation" }
        }
    }
}

$vitalBundle = @{ resourceType = "Bundle"; type = "transaction"; entry = $vitalEntries }
Post-FhirBundle -Bundle $vitalBundle -Label "Vital Signs Observations"

# ==========================================================================
# STEP 7 - Wait for async processing
# ==========================================================================
Write-Host ""
Write-Host "[7] Waiting 3 seconds for aggregation services ..."
Start-Sleep -Seconds 3

# ==========================================================================
# STEP 8 - Call correlation endpoint
# ==========================================================================
Write-Host ""
Write-Host "[8] Calling GET /analytics/neigh-correlations ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-correlations" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - city=$($corr.city), neighCount=$($corr.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ==========================================================================
# STEP 9 - Call spatial autocorrelation endpoint
# ==========================================================================
Write-Host ""
Write-Host "[9] Calling GET /analytics/neigh-spatial-autocorrelation ..."
$moran = $null
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-spatial-autocorrelation" `
        -Headers @{Accept="application/json"} -TimeoutSec 60
    Write-Host "  OK - neighCount=$($moran.neighCount)" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
}

# ==========================================================================
# STEP 10 - VALIDATE CORRELATIONS
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  CORRELATION RESULTS" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  City : $($corr.city)"
Write-Host "  Neighbourhood sample count  : $($corr.neighCount)"
Write-Host ""

# Expected direction for each correlation
$expected = @{
    "careUnits_vs_news2"          = @{ dir = "negative";  minR = 0.90 }
    "averageIncome_vs_conditions" = @{ dir = "negative";  minR = 0.90 }
    "meanAge_vs_heartRate"        = @{ dir = "positive";  minR = 0.95 }
    "meanAge_vs_systolicBP"       = @{ dir = "positive";  minR = 0.95 }
    "meanAge_vs_diastolicBP"      = @{ dir = "positive";  minR = 0.95 }
}

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
        $conf    = $item.confidence
        $status  = $item.status
        $n       = $item.samples

        Write-Host "  -------------------------------------------------------" -ForegroundColor DarkGray
        Write-Host "  $label" -ForegroundColor White
        Write-Host "    Status: $status  |  Samples: $n  |  Confidence: $conf"

        if ($status -eq "ok") {
            # Pearson
            if ($null -ne $pP) {
                $pPStr = "p={0:F4} {1}" -f $pP, (Sig-Stars $pP)
            } else {
                $pPStr = "p=N/A"
            }
            Write-Host ("    Pearson  r   = {0,8:F4}   ({1})" -f $pearson, $pPStr)

            # Spearman
            if ($null -ne $pS) {
                $pSStr = "p={0:F4} {1}" -f $pS, (Sig-Stars $pS)
            } else {
                $pSStr = "p=N/A"
            }
            Write-Host ("    Spearman rho = {0,8:F4}   ({1})" -f $spear, $pSStr)

            # Kendall
            if ($null -ne $pK) {
                $pKStr = "p={0:F4} {1}" -f $pK, (Sig-Stars $pK)
            } else {
                $pKStr = "p=N/A"
            }
            Write-Host ("    Kendall  tau = {0,8:F4}   ({1})" -f $kendall, $pKStr)

            # Permutation
            if ($perm -and $perm.status -eq "ok") {
                $permPStr = "p_perm={0:F4} {1}" -f $perm.pValue, (Sig-Stars $perm.pValue)
                Write-Host ("    Permutation  : {0}  (n={1})" -f $permPStr, $perm.permutations)
            }

            # Validation against expected
            $exp = $expected[$label]
            if ($exp) {
                $dirOk = $false
                $magOk = $false

                if ($exp.dir -eq "positive" -and $pearson -gt 0) { $dirOk = $true }
                if ($exp.dir -eq "negative" -and $pearson -lt 0) { $dirOk = $true }
                if ([Math]::Abs($pearson) -ge $exp.minR)         { $magOk = $true }

                $sigOk = ($null -ne $pP) -and ($pP -lt 0.05)

                if ($dirOk -and $magOk -and $sigOk) {
                    Write-Host "    >> PASS: direction=$($exp.dir), |r|>=$($exp.minR), significant" -ForegroundColor Green
                    $pass++
                } else {
                    $reasons = @()
                    if (-not $dirOk) { $reasons += "wrong direction (expected $($exp.dir))" }
                    if (-not $magOk) { $reasons += ("|r|={0:F4} < {1}" -f [Math]::Abs($pearson), $exp.minR) }
                    if (-not $sigOk) { $reasons += "not significant (p=$pP)" }
                    Write-Host "    >> FAIL: $($reasons -join '; ')" -ForegroundColor Red
                    $fail++
                }
            }
        } else {
            Write-Host "    (insufficient data or undefined)" -ForegroundColor Yellow
            if ($expected[$label]) { $fail++ }
        }
    }
} else {
    Write-Host "  No correlations returned!" -ForegroundColor Red
    $fail = 5
}

# ==========================================================================
# STEP 11 - VALIDATE SPATIAL AUTOCORRELATION (MORAN I)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SPATIAL AUTOCORRELATION (MORAN I)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($moran -and $moran.variables) {
    Write-Host "  Neighbourhood sample count      : $($moran.neighCount)"
    Write-Host "  Weight matrix    : $($moran.weightMatrix)"
    Write-Host "  Permutations     : $($moran.permutations)"
    Write-Host "  Intervention flag available in payload"
    Write-Host ""

    foreach ($v in $moran.variables) {
        $varName = $v.variable
        $vStatus = $v.status
        $mi      = $v.moranI
        $ei      = $v.expectedI
        $zs      = $v.zScore
        $pv      = $v.pValue
        $pat     = $v.pattern

        Write-Host "  -------------------------------------------------------" -ForegroundColor DarkGray
        Write-Host "  $varName  (n=$($v.n))" -ForegroundColor White

        if ($vStatus -eq "ok") {
            if ($null -ne $pv) {
                $sigStr = Sig-Stars $pv
            } else {
                $sigStr = "?"
            }
            Write-Host ("    Moran I   = {0,8}   Expected I = {1}" -f $mi, $ei)
            Write-Host ("    z-score   = {0,8}   p-value    = {1} {2}" -f $zs, $pv, $sigStr)

            if ($pat -eq "clustered") {
                Write-Host "    Pattern   = $pat" -ForegroundColor Yellow
            } elseif ($pat -eq "dispersed") {
                Write-Host "    Pattern   = $pat" -ForegroundColor Magenta
            } else {
                Write-Host "    Pattern   = $pat" -ForegroundColor Gray
            }
        } else {
            Write-Host "    Status: $vStatus" -ForegroundColor Yellow
            if ($v.interpretation) {
                Write-Host "    $($v.interpretation)" -ForegroundColor DarkGray
            }
        }
    }
} else {
    Write-Host "  Spatial autocorrelation data not available" -ForegroundColor Yellow
}

# ==========================================================================
# STEP 12 - RAW NEIGHBOURHOOD SOURCE DATA
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  NEIGHBOURHOOD-SOURCE DATA (from server)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

if ($corr.neighRows) {
    $header = "  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Neighbourhood", "Income", "Care", "Age", "NEWS2", "Cond", "HR", "SBP", "DBP"
    Write-Host $header
    $separator = "  " + ("-" * 68)
    Write-Host $separator -ForegroundColor DarkGray

    foreach ($row in $corr.neighRows | Sort-Object {




