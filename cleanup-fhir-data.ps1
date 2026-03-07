<#
.SYNOPSIS
    Deletes ALL FHIR data from the server.
    Run this between test scripts to ensure a clean state.
#>

param(
    [string]$FhirBase = "http://localhost:8081/fhir"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

Write-Host ""
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  CLEANUP: Deleting all FHIR data" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host ""

$types = @("Condition", "Observation", "MeasureReport", "Patient")

foreach ($type in $types) {
    Write-Host "  Deleting $type ... " -NoNewline
    $totalDeleted = 0
    $keepGoing = $true

    while ($keepGoing) {
        try {
            $search = Invoke-RestMethod -Uri "$FhirBase/${type}?_count=200&_elements=id" `
                -Headers @{Accept="application/fhir+json"} -TimeoutSec 30
        } catch {
            Write-Host "search failed: $($_.Exception.Message)" -ForegroundColor Red
            break
        }

        if (-not $search.entry -or $search.entry.Count -eq 0) {
            $keepGoing = $false
            break
        }

        $deleteEntries = @()
        foreach ($e in $search.entry) {
            $rid = $e.resource.resourceType + "/" + $e.resource.id
            $deleteEntries += @{
                request = @{ method = "DELETE"; url = $rid }
            }
        }

        $deleteBundle = @{
            resourceType = "Bundle"
            type         = "transaction"
            entry        = $deleteEntries
        }

        try {
            $json = $deleteBundle | ConvertTo-Json -Depth 10 -Compress
            Invoke-RestMethod -Uri $FhirBase -Method POST `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
                -ContentType "application/fhir+json; charset=utf-8" | Out-Null
            $totalDeleted += $deleteEntries.Count
        } catch {
            Write-Host "delete failed: $($_.Exception.Message)" -ForegroundColor Red
            $keepGoing = $false
        }
    }

    Write-Host "OK ($totalDeleted deleted)" -ForegroundColor Green
}

Write-Host ""
Write-Host "  Cleanup complete." -ForegroundColor Green
Write-Host ""
