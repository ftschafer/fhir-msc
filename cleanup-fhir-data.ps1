<#
.SYNOPSIS
    Deletes ALL FHIR data from the server.
    Run this between test scripts to ensure a clean state.
#>

param(
    [string]$FhirBase = "http://localhost:8091/fhir"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

function Get-SearchPage {
    param(
        [string]$Type,
        [string]$Count = "200"
    )

    return Invoke-RestMethod -Uri "$FhirBase/${Type}?_count=$Count&_elements=id" `
        -Headers @{Accept="application/fhir+json"} -TimeoutSec 30
}

function Get-RemainingCount {
    param([string]$Type)

    $page = Invoke-RestMethod -Uri "$FhirBase/${Type}?_count=1&_elements=id" `
        -Headers @{Accept="application/fhir+json"} -TimeoutSec 30

    if ($null -eq $page.total) {
        if ($page.entry) { return $page.entry.Count }
        return 0
    }

    return [int]$page.total
}

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
        $search = Get-SearchPage -Type $type

        if (-not $search.entry -or $search.entry.Count -eq 0) {
            $keepGoing = $false
            break
        }

        $deleteEntries = @()
        foreach ($e in $search.entry) {
            if (-not $e.resource -or -not $e.resource.id) {
                throw "Search page for $type returned an entry without resource.id"
            }

            $rid = "$type/$($e.resource.id)"
            $deleteEntries += @{
                request = @{ method = "DELETE"; url = $rid }
            }
        }

        $deleteBundle = @{
            resourceType = "Bundle"
            type         = "transaction"
            entry        = $deleteEntries
        }

        $json = $deleteBundle | ConvertTo-Json -Depth 10 -Compress
        $txResponse = Invoke-RestMethod -Uri $FhirBase -Method POST `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
            -ContentType "application/fhir+json; charset=utf-8"

        $failedDeletes = @()
        if ($txResponse.entry) {
            foreach ($entry in $txResponse.entry) {
                $status = ""
                if ($entry.response -and $entry.response.status) {
                    $status = [string]$entry.response.status
                }
                if (-not $status.StartsWith("2")) {
                    $failedDeletes += $status
                }
            }
        }

        if ($failedDeletes.Count -gt 0) {
            throw "Delete transaction for $type had non-2xx statuses: $($failedDeletes -join ', ')"
        }

        $totalDeleted += $deleteEntries.Count
    }

    $remaining = Get-RemainingCount -Type $type
    if ($remaining -gt 0) {
        throw "Cleanup incomplete for ${type}: $remaining resources still present after deleting $totalDeleted"
    }

    Write-Host "OK ($totalDeleted deleted, 0 remaining)" -ForegroundColor Green
}

Write-Host ""
Write-Host "  Cleanup complete." -ForegroundColor Green
Write-Host ""
