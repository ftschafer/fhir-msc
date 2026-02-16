# Script to check BlockNews2Aggregate table contents
$baseUrl = "http://localhost:8081"

Write-Host "Checking BlockNews2Aggregate table via H2 console..." -ForegroundColor Green
Write-Host "Please run this query in H2 console at http://localhost:8081/h2-console" -ForegroundColor Yellow
Write-Host ""
Write-Host "JDBC URL: jdbc:h2:mem:test_mem" -ForegroundColor Cyan
Write-Host "Username: sa" -ForegroundColor Cyan
Write-Host "Password: (empty)" -ForegroundColor Cyan
Write-Host ""
Write-Host "Query to run:" -ForegroundColor Yellow
Write-Host @"
SELECT 
    REGION, 
    CITY, 
    BLOCK, 
    PATIENT_COUNT, 
    TOTAL_SCORE,
    UPDATED_AT
FROM NEWS2_BLOCK_AGG
ORDER BY BLOCK, REGION, CITY;
"@ -ForegroundColor White

Write-Host ""
Write-Host "This will show you all aggregate entries and help identify duplicates or UNKNOWN entries" -ForegroundColor Green
