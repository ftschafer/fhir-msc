# ============================================================================
# 3️⃣  Statistical Summarization — Block + Neighborhood
# ============================================================================
# Goal: Regional health monitoring via NEWS2 aggregates
#
# Methods per level (block / neighborhood):
#   • Mean / Median NEWS2
#   • Variance / Standard Deviation
#   • Incidence rate  (patients-with-score / total patients)
#   • Prevalence rate (total score burden / total patients)
#   • Z-scores        (flag abnormal blocks)
#   • Bootstrapping   (95 % confidence intervals for the mean)
# ============================================================================

param(
    [string]$BaseUrl       = "http://localhost:8081",
    [string]$Neighborhood  = "",          # filter to one neighborhood (empty = all)
    [int]   $BootstrapN    = 1000,        # bootstrap iterations
    [double]$ZScoreThreshold = 1.96       # |z| above this ➜ abnormal
)

# ── Helpers ──────────────────────────────────────────────────────────────────

function Get-Median([double[]]$values) {
    if ($values.Count -eq 0) { return 0.0 }
    $sorted = $values | Sort-Object
    $mid = [math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 0) {
        return ($sorted[$mid - 1] + $sorted[$mid]) / 2
    }
    return $sorted[$mid]
}

function Get-Variance([double[]]$values, [double]$mean) {
    if ($values.Count -le 1) { return 0.0 }
    $ss = ($values | ForEach-Object { [math]::Pow($_ - $mean, 2) } | Measure-Object -Sum).Sum
    return $ss / ($values.Count - 1)            # sample variance
}

function Get-StdDev([double]$variance) {
    return [math]::Sqrt($variance)
}

function Get-ZScore([double]$value, [double]$mean, [double]$stdDev) {
    if ($stdDev -eq 0) { return 0.0 }
    return ($value - $mean) / $stdDev
}

function Get-BootstrapCI([double[]]$values, [int]$iterations, [double]$alpha = 0.05) {
    # Returns (lower, upper) of the (1-α) confidence interval for the mean
    if ($values.Count -eq 0) { return @{ Lower = 0.0; Upper = 0.0 } }
    $rng      = [System.Random]::new()
    $means    = [System.Collections.Generic.List[double]]::new()

    for ($i = 0; $i -lt $iterations; $i++) {
        $sum = 0.0
        for ($j = 0; $j -lt $values.Count; $j++) {
            $sum += $values[$rng.Next($values.Count)]
        }
        $means.Add($sum / $values.Count)
    }

    $sorted   = $means | Sort-Object
    $loIdx    = [math]::Floor(($alpha / 2) * $sorted.Count)
    $hiIdx    = [math]::Floor((1 - $alpha / 2) * $sorted.Count) - 1
    $loIdx    = [math]::Max(0, $loIdx)
    $hiIdx    = [math]::Min($sorted.Count - 1, $hiIdx)

    return @{ Lower = [math]::Round($sorted[$loIdx], 4); Upper = [math]::Round($sorted[$hiIdx], 4) }
}

function Format-Num([double]$v, [int]$d = 4) { return [math]::Round($v, $d) }

# ── Fetch all block aggregates ───────────────────────────────────────────────

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host "  3️⃣  Statistical Summarization  —  Block + Neighborhood" -ForegroundColor Cyan
Write-Host "========================================================`n" -ForegroundColor Cyan

try {
    $blocks = Invoke-RestMethod -Uri "$BaseUrl/city-blocks" -Method Get -ContentType "application/json"
} catch {
    Write-Host "ERROR: Cannot reach the aggregates API — is the server running?" -ForegroundColor Red
    exit 1
}

if (-not $blocks -or $blocks.Count -eq 0) {
    Write-Host "No block aggregates found." -ForegroundColor Yellow
    exit 0
}

$blocks = $blocks | ForEach-Object {
    [PSCustomObject]@{
        Block         = $_.block
        Neighborhood  = $_.neighborhood
        Average       = [double]$_.average
        PatientCount  = [int]$_.patientCount
        TotalScore    = [int]$_.totalScore
    }
}

# Optional neighborhood filter
if ($Neighborhood -ne "") {
    $blocks = $blocks | Where-Object { $_.Neighborhood -ieq $Neighborhood }
    if ($blocks.Count -eq 0) {
        Write-Host "No blocks found for neighborhood '$Neighborhood'." -ForegroundColor Yellow
        exit 0
    }
}

# ── 1.  Block-level statistics ───────────────────────────────────────────────

$allAverages  = @($blocks | ForEach-Object { $_.Average })
$globalMean   = ($allAverages | Measure-Object -Average).Average
$globalVar    = Get-Variance $allAverages $globalMean
$globalStdDev = Get-StdDev $globalVar

Write-Host "── Block-Level Statistics ─────────────────────────────" -ForegroundColor Yellow
Write-Host ("  Blocks analysed     : {0}" -f $blocks.Count)
Write-Host ("  Global mean NEWS2   : {0}" -f (Format-Num $globalMean))
Write-Host ("  Global median NEWS2 : {0}" -f (Format-Num (Get-Median $allAverages)))
Write-Host ("  Variance            : {0}" -f (Format-Num $globalVar))
Write-Host ("  Std Deviation       : {0}" -f (Format-Num $globalStdDev))

$totalPatients = ($blocks | Measure-Object -Property PatientCount -Sum).Sum
$totalScore    = ($blocks | Measure-Object -Property TotalScore   -Sum).Sum
$incidenceRate  = if ($totalPatients -gt 0) { ($blocks | Where-Object { $_.TotalScore -gt 0 }).Count / $blocks.Count } else { 0 }
$prevalenceRate = if ($totalPatients -gt 0) { $totalScore / $totalPatients } else { 0 }

Write-Host ("  Incidence rate      : {0}  (fraction of blocks with score > 0)" -f (Format-Num $incidenceRate))
Write-Host ("  Prevalence rate     : {0}  (total score / total patients)" -f (Format-Num $prevalenceRate))

$ci = Get-BootstrapCI $allAverages $BootstrapN
Write-Host ("  95% Bootstrap CI    : [{0}, {1}]  (n={2})" -f $ci.Lower, $ci.Upper, $BootstrapN) -ForegroundColor DarkCyan

Write-Host ""
Write-Host "  Z-Scores per block:" -ForegroundColor Yellow

$blockStats = $blocks | ForEach-Object {
    $z = Get-ZScore $_.Average $globalMean $globalStdDev
    $flag = if ([math]::Abs($z) -ge $ZScoreThreshold) { "⚠️  ABNORMAL" } else { "" }
    [PSCustomObject]@{
        Block        = $_.Block
        Neighborhood = $_.Neighborhood
        Average      = Format-Num $_.Average
        Patients     = $_.PatientCount
        ZScore       = Format-Num $z
        Flag         = $flag
    }
}

$blockStats | Sort-Object { [double]$_.ZScore } -Descending |
    Format-Table -AutoSize Block, Neighborhood, Average, Patients, ZScore, Flag |
    Out-String | Write-Host

# ── 2.  Neighborhood-level statistics ────────────────────────────────────────

Write-Host "── Neighborhood-Level Statistics ──────────────────────" -ForegroundColor Yellow

$neighborhoods = $blocks | Group-Object Neighborhood

foreach ($ng in $neighborhoods | Sort-Object Name) {
    $neighBlocks  = @($ng.Group)
    $avgs         = @($neighBlocks | ForEach-Object { $_.Average })
    $nMean        = ($avgs | Measure-Object -Average).Average
    $nMedian      = Get-Median $avgs
    $nVar         = Get-Variance $avgs $nMean
    $nStdDev      = Get-StdDev $nVar
    $nPatients    = ($neighBlocks | Measure-Object -Property PatientCount -Sum).Sum
    $nTotalScore  = ($neighBlocks | Measure-Object -Property TotalScore   -Sum).Sum
    $nIncidence   = if ($neighBlocks.Count -gt 0) { ($neighBlocks | Where-Object { $_.TotalScore -gt 0 }).Count / $neighBlocks.Count } else { 0 }
    $nPrevalence  = if ($nPatients -gt 0) { $nTotalScore / $nPatients } else { 0 }
    $nCI          = Get-BootstrapCI $avgs $BootstrapN

    Write-Host ""
    Write-Host ("  📍 Neighborhood: {0}  ({1} blocks, {2} patients)" -f $ng.Name, $neighBlocks.Count, $nPatients) -ForegroundColor Green
    Write-Host ("     Mean NEWS2       : {0}" -f (Format-Num $nMean))
    Write-Host ("     Median NEWS2     : {0}" -f (Format-Num $nMedian))
    Write-Host ("     Variance         : {0}" -f (Format-Num $nVar))
    Write-Host ("     Std Deviation    : {0}" -f (Format-Num $nStdDev))
    Write-Host ("     Incidence rate   : {0}" -f (Format-Num $nIncidence))
    Write-Host ("     Prevalence rate  : {0}" -f (Format-Num $nPrevalence))
    Write-Host ("     95% Bootstrap CI : [{0}, {1}]" -f $nCI.Lower, $nCI.Upper)

    # Z-scores within this neighborhood
    $abnormal = @()
    foreach ($b in $neighBlocks) {
        $z = Get-ZScore $b.Average $nMean $nStdDev
        if ([math]::Abs($z) -ge $ZScoreThreshold) {
            $abnormal += [PSCustomObject]@{ Block = $b.Block; Average = (Format-Num $b.Average); Z = (Format-Num $z) }
        }
    }
    if ($abnormal.Count -gt 0) {
        Write-Host "     ⚠️  Abnormal blocks (|z| >= $ZScoreThreshold):" -ForegroundColor Red
        $abnormal | ForEach-Object {
            Write-Host ("        Block {0}  avg={1}  z={2}" -f $_.Block, $_.Average, $_.Z) -ForegroundColor Red
        }
    } else {
        Write-Host "     ✅ No abnormal blocks detected" -ForegroundColor DarkGreen
    }
}

# ── 3.  Cross-neighborhood comparison ────────────────────────────────────────

if ($neighborhoods.Count -gt 1) {
    Write-Host "`n── Cross-Neighborhood Comparison ──────────────────────" -ForegroundColor Yellow

    $neighSummary = $neighborhoods | ForEach-Object {
        $avgs    = @($_.Group | ForEach-Object { $_.Average })
        $nMean   = ($avgs | Measure-Object -Average).Average
        $nStdDev = Get-StdDev (Get-Variance $avgs $nMean)
        $nPat    = ($_.Group | Measure-Object -Property PatientCount -Sum).Sum
        [PSCustomObject]@{
            Neighborhood = $_.Name
            Blocks       = $_.Count
            Patients     = $nPat
            MeanNEWS2    = Format-Num $nMean
            StdDev       = Format-Num $nStdDev
        }
    }

    $neighSummary | Sort-Object { [double]$_.MeanNEWS2 } -Descending |
        Format-Table -AutoSize Neighborhood, Blocks, Patients, MeanNEWS2, StdDev |
        Out-String | Write-Host
}

Write-Host "Done." -ForegroundColor Cyan
