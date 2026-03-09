<#
.SYNOPSIS
    Integration test for CityCorrelationController - INVERSE correlations.
    Creates 20 neighbourhood samples of FHIR data with OPPOSITE correlations to test-correlation-data.ps1.

.DESCRIPTION
        Posts MeasureReports, Patients, Conditions, Observations (NEWS2 + vital signs)
        for neighbourhood samples C01-C20 in neighborhood N81, then calls:
            GET /analytics/neigh-correlations?city=N81
            GET /analytics/neigh-spatial-autocorrelation?city=N81

    Data is designed with INVERTED correlation patterns compared to the original test:
      careUnits  vs news2        -> strong POSITIVE  (~+0.99)   (was negative)
      income     vs conditions   -> strong POSITIVE  (~+0.97)   (was negative)
      meanAge    vs heartRate    -> strong NEGATIVE  (~-0.99)   (was positive)
      meanAge    vs systolicBP   -> strong NEGATIVE  (~-0.99)   (was positive)
      meanAge    vs diastolicBP  -> strong NEGATIVE  (~-0.99)   (was positive)

    The original test (test-correlation-data.ps1) also uses neighborhood N81 / neighbourhood samples B01-B20.
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "C91"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# ==========================================================================
# DATA DESIGN - 20 neighbourhood samples with INVERSE correlation structure
# ==========================================================================
#
#   income :  20000 -> 58000   (monotonically increasing)
#   care   :  1 -> 12          (monotonically INCREASING = positive with income)
#   news2  :  increases with care  (POSITIVE with care)
#   cond   :  0 -> 8           (INCREASING with income = POSITIVE with income)
#   age    :  mixed 30-75      (varies independently of income)
#   hr     :  130 - 0.8*age   (NEGATIVE with age)
#   sbp    :  210 - 1.5*age   (NEGATIVE with age)
#   dbp    :  120 - 0.75*age  (NEGATIVE with age)
#
$neighborhoodSamples = @(
    @{ id="C01"; income=20000; care=1;  age=65; news2=0.4; cond=0; hr=78;  sbp=113; dbp=71 }
    @{ id="C02"; income=22000; care=2;  age=60; news2=0.7; cond=1; hr=82;  sbp=120; dbp=75 }
    @{ id="C03"; income=25000; care=2;  age=72; news2=1.0; cond=1; hr=72;  sbp=102; dbp=66 }
    @{ id="C04"; income=27000; care=3;  age=48; news2=1.3; cond=1; hr=92;  sbp=138; dbp=84 }
    @{ id="C05"; income=30000; care=3;  age=55; news2=1.5; cond=2; hr=86;  sbp=128; dbp=79 }
    @{ id="C06"; income=32000; care=4;  age=42; news2=1.9; cond=2; hr=96;  sbp=147; dbp=89 }
    @{ id="C07"; income=35000; care=4;  age=68; news2=2.1; cond=3; hr=76;  sbp=108; dbp=69 }
    @{ id="C08"; income=37000; care=5;  age=35; news2=2.5; cond=3; hr=102; sbp=158; dbp=94 }
    @{ id="C09"; income=40000; care=5;  age=62; news2=2.8; cond=4; hr=80;  sbp=117; dbp=74 }
    @{ id="C10"; income=42000; care=6;  age=50; news2=3.1; cond=4; hr=90;  sbp=135; dbp=83 }
    @{ id="C11"; income=43000; care=6;  age=75; news2=3.3; cond=4; hr=70;  sbp=98;  dbp=64 }
    @{ id="C12"; income=45000; care=7;  age=38; news2=3.7; cond=5; hr=100; sbp=153; dbp=92 }
    @{ id="C13"; income=47000; care=7;  age=58; news2=4.0; cond=5; hr=84;  sbp=123; dbp=77 }
    @{ id="C14"; income=48000; care=8;  age=33; news2=4.5; cond=5; hr=104; sbp=161; dbp=95 }
    @{ id="C15"; income=50000; care=8;  age=70; news2=4.8; cond=6; hr=74;  sbp=105; dbp=68 }
    @{ id="C16"; income=51000; care=9;  age=45; news2=5.1; cond=6; hr=94;  sbp=143; dbp=86 }
    @{ id="C17"; income=53000; care=9;  age=52; news2=5.3; cond=7; hr=88;  sbp=132; dbp=81 }
    @{ id="C18"; income=55000; care=10; age=40; news2=5.8; cond=7; hr=98;  sbp=150; dbp=90 }
    @{ id="C19"; income=56000; care=11; age=30; news2=6.0; cond=7; hr=106; sbp=165; dbp=98 }
    @{ id="C20"; income=58000; care=12; age=46; news2=6.5; cond=8; hr=93;  sbp=141; dbp=86 }
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
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  FHIR INVERSE Correlation Test - 20 Neighbourhood samples (N81)" -ForegroundColor Magenta
Write-Host "  All correlations are OPPOSITE to test-correlation-data.ps1" -ForegroundColor Magenta
Write-Host "================================================================" -ForegroundColor Magenta
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
    name = @(@{ family = "InverseCorrelationTest"; given = @("Anchor") })
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
foreach ($b in $neighborhoodSamples) {
    for ($p = 1; $p -le 3; $p++) {
        $birthYear = 1950 + [int]([Math]::Round((90 - $b.age) + ($p * 2)))
        $patEntries += @{
            resource = @{
                resourceType = "Patient"
                active       = $true
                name         = @(@{ family = "InvTest-$($b.id)"; given = @("P$p") })
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
$totalCond = ($neighborhoodSamples | ForEach-Object { $_.cond } | Measure-Object -Sum).Sum
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
foreach ($b in $neighborhoodSamples) {
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
foreach ($b in $neighborhoodSamples) {
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
                text = "NEWS2 Neighbourhood Average"
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
foreach ($b in $neighborhoodSamples) {
    foreach ($vt in $vitalTypes) {
        $val = $b[$vt.field]
        $sampleCount = 3
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
# STEP 8 - Call correlation endpoint (with neighborhood=N81)
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
# STEP 9 - Call spatial autocorrelation endpoint (with neighborhood=N81)
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
# STEP 10 - VALIDATE CORRELATIONS (expecting OPPOSITE directions)
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  INVERSE CORRELATION RESULTS" -ForegroundColor Magenta
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host ""
Write-Host "  Neighborhood : $($corr.city)"
Write-Host "  Neighbourhood sample count  : $($corr.neighCount)"
Write-Host ""

# !! INVERTED expected directions compared to the original test !!
$expected = @{
    "careUnits_vs_news2"          = @{ dir = "positive";  minR = 0.90 }
    "averageIncome_vs_conditions" = @{ dir = "positive";  minR = 0.90 }
    "meanAge_vs_heartRate"        = @{ dir = "negative";  minR = 0.95 }
    "meanAge_vs_systolicBP"       = @{ dir = "negative";  minR = 0.95 }
    "meanAge_vs_diastolicBP"      = @{ dir = "negative";  minR = 0.95 }
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

            # Validation against expected INVERSE directions
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
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  SPATIAL AUTOCORRELATION (MORAN I) - N81" -ForegroundColor Magenta
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host ""

if ($moran -and $moran.variables) {
    Write-Host "  Neighbourhood sample count      : $($moran.neighCount)"
    Write-Host "  Weight matrix    : $($moran.weightMatrix)"
    Write-Host "  Permutations     : $($moran.permutations)"
    Write-Host "  Intervention     : $($moran.blockLevelInterventionJustified)"
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
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  NEIGHBOURHOOD-LEVEL DATA (from server) - N81" -ForegroundColor Magenta
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host ""

if ($corr.neighRows) {
    $header = "  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f "Neighbourhood", "Income", "Care", "Age", "NEWS2", "Cond", "HR", "SBP", "DBP"
    Write-Host $header
    $separator = "  " + ("-" * 68)
    Write-Host $separator -ForegroundColor DarkGray

    foreach ($row in $corr.neighRows | Sort-Object { $_.neighbourhood }) {
        $line = "  {0,-6} {1,10} {2,6} {3,6} {4,7} {5,5} {6,6} {7,6} {8,6}" -f $row.neighbourhood, $row.averageIncome, $row.careUnits, $row.meanAge, $row.avgNews2, $row.conditionCount, $row.heartRate, $row.systolicBP, $row.diastolicBP
        Write-Host $line
    }
} else {
    Write-Host "  No neighbourhood rows returned" -ForegroundColor Yellow
}

# ==========================================================================
# STEP 13 - COMPARISON WITH ORIGINAL TEST
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  DIRECTION COMPARISON: Original vs Inverse" -ForegroundColor Magenta
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host ""
Write-Host "  Correlation                  Original          Inverse" -ForegroundColor White
Write-Host "  -----------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "  careUnits_vs_news2           NEGATIVE          POSITIVE"
Write-Host "  averageIncome_vs_conditions  NEGATIVE          POSITIVE"
Write-Host "  meanAge_vs_heartRate         POSITIVE          NEGATIVE"
Write-Host "  meanAge_vs_systolicBP        POSITIVE          NEGATIVE"
Write-Host "  meanAge_vs_diastolicBP       POSITIVE          NEGATIVE"
Write-Host ""

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  SUMMARY" -ForegroundColor Magenta
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host ""
Write-Host "  Resources created (neighborhood ${City}):"
Write-Host "    MeasureReports : 20"
Write-Host "    Patients       : 60  (+ 1 anchor)"
Write-Host "    Conditions     : $totalCond"
Write-Host "    NEWS2 Obs      : 20"
Write-Host "    Vital Signs Obs: 60"
Write-Host ""
Write-Host "  Inverse correlation tests:"
if ($fail -eq 0) {
    Write-Host "    PASS : $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "    FAIL : $fail / $($pass + $fail)" -ForegroundColor Green
} else {
    Write-Host "    PASS : $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "    FAIL : $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""

if ($fail -eq 0) {
    Write-Host "  ALL INVERSE CORRELATION TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  SOME TESTS FAILED - review output above" -ForegroundColor Red
}

Write-Host ""
Write-Host "  Significance: *** p<0.001  ** p<0.01  * p<0.05  ns p>=0.05"
Write-Host ""






