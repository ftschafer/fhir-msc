# WARNING: This script will delete ALL Patient, Condition, and Observation resources from your FHIR server.
# Use with caution!


param(
  [string]$BaseUrl = 'http://localhost:8071/fhir/'
)

$ErrorActionPreference = 'Stop'

function Ensure-TrailingSlash($url) {
  if ($url.EndsWith('/')) { return $url } else { return $url + '/' }
}

$BaseUrl = Ensure-TrailingSlash($BaseUrl)

function Delete-AllResources($resourceType) {
  Write-Host "Deleting all $resourceType resources..."
  $url = "{0}{1}?_count=1000" -f $BaseUrl, $resourceType
  Write-Host "GET: $url"
  try {
    $resources = Invoke-RestMethod -Uri $url -Method Get
  } catch {
    Write-Host "ERROR fetching ${resourceType}: $($_.Exception.Message)"
    return
  }
  if ($resources.entry) {
    foreach ($entry in $resources.entry) {
      $rid = $entry.resource.id
      $delUrl = "{0}{1}/{2}" -f $BaseUrl, $resourceType, $rid
      Write-Host "DELETE: $delUrl"
      try {
        Invoke-RestMethod -Uri $delUrl -Method Delete -TimeoutSec 5 | Out-Null
      } catch {
        Write-Host "ERROR deleting ${resourceType}/${rid}: $($_.Exception.Message)"
      }
    }
    Write-Host "All $resourceType resources deleted."
  } else {
    Write-Host "No $resourceType resources found."
  }
}

Delete-AllResources Patient
Delete-AllResources Condition
Delete-AllResources Observation
Write-Host "Full cleanup complete."
