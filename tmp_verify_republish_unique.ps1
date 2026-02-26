$ErrorActionPreference = 'Stop'

$mr = Invoke-RestMethod -Method Get -Uri 'http://localhost:8071/fhir/MeasureReport?_count=1'
if ([int]$mr.total -lt 1) { throw 'No MeasureReport found.' }

$entry = $mr.entry[0].resource
$mrId = $entry.id
$before = $entry.meta.lastUpdated
$newId = 'test-age-' + [guid]::NewGuid().ToString('N').Substring(0,8)
Write-Host "Before lastUpdated=$before id=$mrId newPatientId=$newId"

$newPatient = @{
  resourceType='Patient'
  id=$newId
  name=@(@{family='AgeTest';given=@('Fresh')})
  gender='male'
  birthDate='1992-07-19'
  extension=@(
    @{url='http://patient-location';extension=@(@{url='block';valueString='B71'})},
    @{url='http://news2-score';valueInteger=2}
  )
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Put -Uri ("http://localhost:8071/fhir/Patient/" + $newId) -ContentType 'application/fhir+json' -Body $newPatient | Out-Null

$deadline=(Get-Date).AddSeconds(40)
$updated=$false
while((Get-Date) -lt $deadline){
  $nowMr = Invoke-RestMethod -Method Get -Uri ("http://localhost:8071/fhir/MeasureReport/" + $mrId)
  $now = $nowMr.meta.lastUpdated
  if($now -ne $before){
    $updated=$true
    Write-Host "After lastUpdated=$now"
    break
  }
  Start-Sleep -Milliseconds 500
}

if($updated){
  Write-Host 'UPDATED_AFTER_TRULY_NEW_PATIENT=YES'
} else {
  Write-Host 'UPDATED_AFTER_TRULY_NEW_PATIENT=NO'
}
