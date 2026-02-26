param(
  [string]$BaseUrl = 'http://localhost:8071/fhir',
  [string]$Block = 'B71'
)

$ErrorActionPreference = 'Stop'

function New-PatientPayload {
  param(
    [string]$Id,
    [string]$Family,
    [string]$Given,
    [string]$Gender,
    [string]$BirthDate,
    [int]$News2,
    [string]$BlockValue
  )

  return @{
    resourceType = 'Patient'
    id = $Id
    name = @(
      @{
        family = $Family
        given = @($Given)
      }
    )
    gender = $Gender
    birthDate = $BirthDate
    extension = @(
      @{
        url = 'http://patient-location'
        extension = @(
          @{
            url = 'block'
            valueString = $BlockValue
          }
        )
      },
      @{
        url = 'http://news2-score'
        valueInteger = $News2
      }
    )
  } | ConvertTo-Json -Depth 12
}

$patients = @(
  # Cluster A - high risk / older
  @{ id='kmeans-a1'; family='ClusterA'; given='Ana';   gender='female'; birthDate='1948-02-14'; news2=9 },
  @{ id='kmeans-a2'; family='ClusterA'; given='Bruno'; gender='male';   birthDate='1952-07-03'; news2=8 },
  @{ id='kmeans-a3'; family='ClusterA'; given='Clara'; gender='female'; birthDate='1955-11-20'; news2=7 },
  @{ id='kmeans-a4'; family='ClusterA'; given='Davi';  gender='male';   birthDate='1945-04-29'; news2=9 },

  # Cluster B - medium risk / adults
  @{ id='kmeans-b1'; family='ClusterB'; given='Eva';   gender='female'; birthDate='1982-01-10'; news2=5 },
  @{ id='kmeans-b2'; family='ClusterB'; given='Felipe';gender='male';   birthDate='1978-08-25'; news2=6 },
  @{ id='kmeans-b3'; family='ClusterB'; given='Gabi';  gender='female'; birthDate='1988-12-12'; news2=5 },
  @{ id='kmeans-b4'; family='ClusterB'; given='Hugo';  gender='male';   birthDate='1990-03-07'; news2=6 },

  # Cluster C - low risk / younger
  @{ id='kmeans-c1'; family='ClusterC'; given='Iara';  gender='female'; birthDate='2004-09-30'; news2=1 },
  @{ id='kmeans-c2'; family='ClusterC'; given='Joao';  gender='male';   birthDate='2002-06-18'; news2=2 },
  @{ id='kmeans-c3'; family='ClusterC'; given='Karen'; gender='female'; birthDate='2006-10-05'; news2=0 },
  @{ id='kmeans-c4'; family='ClusterC'; given='Leo';   gender='male';   birthDate='2001-05-22'; news2=2 }
)

Write-Host "Seeding $($patients.Count) patients to $BaseUrl (block=$Block)..."

foreach ($p in $patients) {
  $payload = New-PatientPayload -Id $p.id -Family $p.family -Given $p.given -Gender $p.gender -BirthDate $p.birthDate -News2 $p.news2 -BlockValue $Block
  $url = "$BaseUrl/Patient/$($p.id)"
  Invoke-RestMethod -Method Put -Uri $url -ContentType 'application/fhir+json' -Body $payload | Out-Null
  Write-Host "UPSERTED $($p.id) (news2=$($p.news2), birthDate=$($p.birthDate))"
}

$bundle = Invoke-RestMethod -Method Get -Uri "$BaseUrl/Patient?_count=200"
$total = [int]$bundle.total
Write-Host "Done. Server reports total Patient count = $total"
Write-Host "Now refresh your dashboard / socioeconomic endpoint to inspect K-Means clusters."
