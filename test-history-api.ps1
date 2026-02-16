# Test script to check the vital sign history API
Write-Host "Testing Vital Sign History API..." -ForegroundColor Cyan
Write-Host ""

$baseUrl = "http://localhost:8085"

# Test 1: Check available blocks
Write-Host "1. Testing with Block-Default:" -ForegroundColor Yellow
$url1 = "$baseUrl/fhir/Observation/`$vital-history?block=Block-Default&days=30"
Write-Host "   URL: $url1"
try {
    $response1 = Invoke-RestMethod -Uri $url1 -Method Get -ContentType "application/json"
    $json1 = $response1 | ConvertTo-Json -Depth 10
    Write-Host "   Response:" -ForegroundColor Green
    Write-Host $json1
} catch {
    Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "2. Testing with North block:" -ForegroundColor Yellow
$url2 = "$baseUrl/fhir/Observation/`$vital-history?block=North&days=30"
Write-Host "   URL: $url2"
try {
    $response2 = Invoke-RestMethod -Uri $url2 -Method Get -ContentType "application/json"
    $json2 = $response2 | ConvertTo-Json -Depth 10
    Write-Host "   Response:" -ForegroundColor Green
    Write-Host $json2
} catch {
    Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "3. Check dashboard stats:" -ForegroundColor Yellow
$url3 = "$baseUrl/fhir/Patient/`$dashboard-stats"
Write-Host "   URL: $url3"
try {
    $response3 = Invoke-RestMethod -Uri $url3 -Method Get -ContentType "application/json"
    Write-Host "   Total Patients: $($response3.totalPatients)" -ForegroundColor Green
    Write-Host "   Total Averages: $($response3.totalAverages)" -ForegroundColor Green
    if ($response3.vitalSignAverages) {
        Write-Host "   Vital Signs Available:" -ForegroundColor Green
        foreach ($vs in $response3.vitalSignAverages) {
            Write-Host "      - $($vs.vitalSign) (location: $($vs.location)): $($vs.averageValue)"
        }
    }
    if ($response3.locationStats) {
        Write-Host "   Locations with patients:" -ForegroundColor Green
        foreach ($loc in $response3.locationStats) {
            Write-Host "      - $($loc.location): $($loc.patientCount) patients"
        }
    }
} catch {
    Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "Test complete!" -ForegroundColor Cyan
