$ErrorActionPreference = 'Stop'

$mrBefore = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/fhir/MeasureReport?_count=1'
if ([int]$mrBefore.total -lt 1) {
    throw 'No MeasureReport found before update test.'
}

$entry = $mrBefore.entry[0].resource
$mrId = $entry.id
$beforeTs = $entry.meta.lastUpdated
Write-Host "Before -> id=$mrId lastUpdated=$beforeTs"

$newPatient = @{
    resourceType = 'Patient'
    id = 'test-age-p3'
    name = @(@{ family = 'AgeTest'; given = @('Carol') })
    gender = 'female'
    birthDate = '1999-01-15'
    extension = @(
        @{
            url = 'http://patient-location'
            extension = @(@{ url = 'block'; valueString = 'B71' })
        },
        @{
            url = 'http://news2-score'
            valueInteger = 5
        }
    )
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Put -Uri 'http://localhost:8071/fhir/Patient/test-age-p3' -ContentType 'application/fhir+json' -Body $newPatient | Out-Null
Write-Host 'Posted test-age-p3; waiting for next scheduled publish...'

$deadline = (Get-Date).AddMinutes(6)
$updated = $false
while ((Get-Date) -lt $deadline) {
    $mrNow = Invoke-RestMethod -Method Get -Uri ("http://localhost:8071/fhir/MeasureReport/" + $mrId)
    $nowTs = $mrNow.meta.lastUpdated
    Write-Host "Check -> lastUpdated=$nowTs at $((Get-Date).ToString('HH:mm:ss'))"

    if ($nowTs -ne $beforeTs) {
        $updated = $true
        break
    }

    Start-Sleep -Seconds 30
}

if ($updated) {
    Write-Host 'MeasureReport updated after new patient arrival.'
} else {
    Write-Host 'No MeasureReport update observed within 6 minutes.'
}
