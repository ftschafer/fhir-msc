# Script to check patient block assignments
$baseUrl = "http://localhost:8081"

Write-Host "Checking patient block assignments..." -ForegroundColor Green

# Get all patients
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/fhir/Patient?_count=1000" -Method Get -ContentType "application/fhir+json"
    
    Write-Host "`nTotal patients: $($response.total)" -ForegroundColor Cyan
    
    $blockCounts = @{}
    $patientsWithoutBlock = @()
    
    foreach ($entry in $response.entry) {
        $patient = $entry.resource
        $patientId = $patient.id
        
        # Extract block from extension
        $block = "UNKNOWN"
        if ($patient.extension) {
            $locExt = $patient.extension | Where-Object { $_.url -eq "http://patient-location" }
            if ($locExt -and $locExt.extension) {
                $blockExt = $locExt.extension | Where-Object { $_.url -eq "block" }
                if ($blockExt -and $blockExt.valueString) {
                    $block = $blockExt.valueString
                }
            }
        }
        
        # Count by block
        if ($blockCounts.ContainsKey($block)) {
            $blockCounts[$block]++
        } else {
            $blockCounts[$block] = 1
        }
        
        if ($block -eq "UNKNOWN") {
            $patientsWithoutBlock += $patientId
        }
        
        Write-Host "  Patient/$patientId -> Block: $block" -ForegroundColor Gray
    }
    
    Write-Host "`nBlock Distribution:" -ForegroundColor Yellow
    foreach ($block in $blockCounts.Keys | Sort-Object) {
        Write-Host "  $block : $($blockCounts[$block]) patients" -ForegroundColor White
    }
    
    if ($patientsWithoutBlock.Count -gt 0) {
        Write-Host "`nPatients without block assignments:" -ForegroundColor Red
        foreach ($pid in $patientsWithoutBlock) {
            Write-Host "  Patient/$pid" -ForegroundColor Red
        }
    }
    
} catch {
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host "Make sure the FHIR server is running on port 8081" -ForegroundColor Yellow
}
