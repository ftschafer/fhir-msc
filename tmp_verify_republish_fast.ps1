$ErrorActionPreference = 'Stop'

$mr = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/fhir/MeasureReport?_count=1'
if ([int]$mr.total -lt 1) { throw 'No MeasureReport found.' }

$entry = $mr.entry[0].resource
$mrId = $entry.id
$before = $entry.meta.lastUpdated
Write-Host "Before lastUpdated=$before id=$mrId"

$newPatient = @{
  resourceType='Patient'
  id='test-age-p4'
  name=@(@{family='AgeTest';given=@('Dora')})
  gender='female'
  birthDate='2001-11-03'
  extension=@(
    @{url='http://patient-location';extension=@(@{url='block';valueString='B71'})},
    @{url='http://news2-score';valueInteger=3}
  )
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Put -Uri 'http://localhost:8071/fhir/Patient/test-age-p4' -ContentType 'application/fhir+json' -Body $newPatient | Out-Null
Write-Host 'Posted test-age-p4'

$deadline=(Get-Date).AddMinutes(2)
$updated=$false
while((Get-Date) -lt $deadline){
  $nowMr = Invoke-RestMethod -Method Get -Uri ("http://localhost:8071/fhir/MeasureReport/" + $mrId)
  $now = $nowMr.meta.lastUpdated
  Write-Host "Check lastUpdated=$now"
  if($now -ne $before){ $updated=$true; break }
  Start-Sleep -Seconds 5
}

if($updated){
  Write-Host 'UPDATED_AFTER_NEW_PATIENT=YES'
} else {
  Write-Host 'UPDATED_AFTER_NEW_PATIENT=NO'
}
