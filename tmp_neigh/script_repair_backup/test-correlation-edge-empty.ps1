<#
.SYNOPSIS
    Edge-case test: EMPTY neighborhood.
    Queries a neighborhood with no data and validates the empty-result contract.

.DESCRIPTION
    Calls the analytics endpoints for neighborhood N90 (which has no data posted)
    and verifies:
      - neighCount = 0
      - correlations is empty
      - message field is present
      - spatial-autocorrelation also returns neighCount = 0

    This tests the controller's zero-data guard paths.
#>

param(
    [string]$FhirBase      = "http://localhost:8091/fhir",
    [string]$AnalyticsBase = "http://localhost:8091/analytics",
    [string]$City          = "N81"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  EDGE CASE: Empty City Scope ($City)" -ForegroundColor Cyan
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

$pass = 0
$fail = 0

# ==========================================================================
# STEP 1 - Neighbourhood correlations for empty city scope
# ==========================================================================
Write-Host ""
Write-Host "[1] GET /analytics/neigh-correlations?city=$City ..."
try {
    $corr = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-correlations?city=$City" `
        -Headers @{Accept="application/json"} -TimeoutSec 30
    Write-Host "  Response received" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# Validate: neighborhood matches
Write-Host ""
Write-Host "  Checking city field ... " -NoNewline
if ($corr.city -eq $City) {
    Write-Host "PASS ($($corr.city))" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (expected '$City', got '$($corr.city)')" -ForegroundColor Red
    $fail++
}

# Validate: neighCount = 0
Write-Host "  Checking neighCount = 0 ... " -NoNewline
if ($corr.neighCount -eq 0) {
    Write-Host "PASS (neighCount=0)" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($corr.neighCount))" -ForegroundColor Red
    $fail++
}

# Validate: message field present
Write-Host "  Checking message field ... " -NoNewline
if ($corr.message -and $corr.message.Length -gt 0) {
    Write-Host "PASS (`"$($corr.message)`")" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (no message field)" -ForegroundColor Red
    $fail++
}

# Validate: correlations is empty
Write-Host "  Checking correlations is empty ... " -NoNewline
$corrList = $corr.correlations
if ($null -eq $corrList -or $corrList.Count -eq 0) {
    Write-Host "PASS (empty)" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (got $($corrList.Count) correlations)" -ForegroundColor Red
    $fail++
}

# ==========================================================================
# STEP 2 - Spatial autocorrelation for empty city scope
# ==========================================================================
Write-Host ""
Write-Host "[2] GET /analytics/neigh-spatial-autocorrelation?city=$City ..."
try {
    $moran = Invoke-RestMethod -Uri "$AnalyticsBase/neigh-spatial-autocorrelation?city=$City" `
        -Headers @{Accept="application/json"} -TimeoutSec 30
    Write-Host "  Response received" -ForegroundColor Green
} catch {
    Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# Validate: neighCount = 0
Write-Host ""
Write-Host "  Checking Moran neighCount = 0 ... " -NoNewline
if ($moran.neighCount -eq 0) {
    Write-Host "PASS (neighCount=0)" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (neighCount=$($moran.neighCount))" -ForegroundColor Red
    $fail++
}

# Validate: variables is empty
Write-Host "  Checking Moran variables is empty ... " -NoNewline
$varList = $moran.variables
if ($null -eq $varList -or $varList.Count -eq 0) {
    Write-Host "PASS (empty)" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (got $($varList.Count) variables)" -ForegroundColor Red
    $fail++
}

# Validate: message present
Write-Host "  Checking Moran message field ... " -NoNewline
if ($moran.message -and $moran.message.Length -gt 0) {
    Write-Host "PASS (`"$($moran.message)`")" -ForegroundColor Green
    $pass++
} else {
    Write-Host "FAIL (no message)" -ForegroundColor Red
    $fail++
}

# ==========================================================================
# SUMMARY
# ==========================================================================
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  SUMMARY - Empty City Scope Edge Case" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Green
    Write-Host "  ALL EMPTY-CITY-SCOPE TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "  PASS: $pass / $($pass + $fail)" -ForegroundColor Yellow
    Write-Host "  FAIL: $fail / $($pass + $fail)" -ForegroundColor Red
}
Write-Host ""





