# Clean up all FHIR resources (Patients, Conditions, Observations) for test data
# This script deletes all Patient, Condition, and Observation resources with IDs starting with 'km3-'
# and all related Conditions/Observations for those patients.

param(
  [string]$BaseUrl = 'http://localhost:8071/fhir'
)

$ErrorActionPreference = 'Stop'

Write-Host "Fetching all test Patients (id starts with 'km3-')..."
$patients = Invoke-RestMethod -Uri "$BaseUrl/Patient?_id:contains=km3-&_count=1000" -Method Get
if ($patients.entry) {
  foreach ($entry in $patients.entry) {
    $pid = $entry.resource.id
    Write-Host "Deleting Patient/$pid"
    try { Invoke-RestMethod -Uri "$BaseUrl/Patient/$pid" -Method Delete -TimeoutSec 5 | Out-Null } catch {}
    # Delete related Conditions
    $conds = Invoke-RestMethod -Uri "$BaseUrl/Condition?subject=Patient/$pid&_count=100" -Method Get
    if ($conds.entry) {
      foreach ($centry in $conds.entry) {
        $cid = $centry.resource.id
        Write-Host "Deleting Condition/$cid"
        try { Invoke-RestMethod -Uri "$BaseUrl/Condition/$cid" -Method Delete -TimeoutSec 5 | Out-Null } catch {}
      }
    }
    # Delete related Observations
    $obs = Invoke-RestMethod -Uri "$BaseUrl/Observation?subject=Patient/$pid&_count=100" -Method Get
    if ($obs.entry) {
      foreach ($oentry in $obs.entry) {
        $oid = $oentry.resource.id
        Write-Host "Deleting Observation/$oid"
        try { Invoke-RestMethod -Uri "$BaseUrl/Observation/$oid" -Method Delete -TimeoutSec 5 | Out-Null } catch {}
      }
    }
  }
  Write-Host "Cleanup complete."
} else {
  Write-Host "No test patients found."
}
