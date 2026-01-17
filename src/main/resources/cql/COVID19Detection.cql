library COVID19Detection version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'

code "Heart Rate": '8867-4' from "LOINC"
code "Respiratory Rate": '9279-1' from "LOINC"
code "Systolic BP": '8480-6' from "LOINC"
code "Diastolic BP": '8462-4' from "LOINC"
code "Body Temperature": '8310-5' from "LOINC"
code "Oxygen Saturation": '59408-5' from "LOINC"

context Patient

define "Recent Observations":
  [Observation: category in "vital-signs"] O
    where O.status = 'final'
      and O.effective.value during Interval[Now() - 24 hours, Now()]

define "Heart Rate":
  (Last("Recent Observations" O where O.code ~ "Heart Rate").value as FHIR.Quantity).value

define "Respiratory Rate":
  (Last("Recent Observations" O where O.code ~ "Respiratory Rate").value as FHIR.Quantity).value

define "Systolic BP":
  (Last("Recent Observations" O where O.code ~ "Systolic BP").value as FHIR.Quantity).value

define "Diastolic BP":
  (Last("Recent Observations" O where O.code ~ "Diastolic BP").value as FHIR.Quantity).value

define "Temperature":
  (Last("Recent Observations" O where O.code ~ "Body Temperature").value as FHIR.Quantity).value

define "SpO2":
  (Last("Recent Observations" O where O.code ~ "Oxygen Saturation").value as FHIR.Quantity).value

// COVID-19 Pattern Scoring
define "Tachycardia Score":
  if "Heart Rate" is not null and "Heart Rate" > 100 then 20 else 0

define "Tachypnea Score":
  if "Respiratory Rate" is not null and "Respiratory Rate" > 20 then 20 else 0

define "Fever Score":
  if "Temperature" is not null and "Temperature" > 37.5 then 20 else 0

define "Hypoxia Score":
  if "SpO2" is not null and "SpO2" < 90 then 30 else 0

define "Hypertension Score":
  if ("Systolic BP" is not null and "Systolic BP" > 120) or 
     ("Diastolic BP" is not null and "Diastolic BP" > 80) then 10 else 0

define "COVID-19 Match Score":
  "Tachycardia Score" + 
  "Tachypnea Score" + 
  "Fever Score" + 
  "Hypoxia Score" + 
  "Hypertension Score"

define "High Risk COVID-19":
  "COVID-19 Match Score" >= 80 and "SpO2" is not null and "SpO2" < 90

define "Possible COVID-19":
  "COVID-19 Match Score" >= 60

define "COVID-19 Risk Level":
  if "High Risk COVID-19" then 'high'
  else if "Possible COVID-19" then 'moderate'
  else 'low'

define "COVID-19 Detected":
  "COVID-19 Match Score" >= 60

define "COVID-19 Recommendation":
  if "High Risk COVID-19" then
    'URGENT: High suspicion for COVID-19 with respiratory compromise. Consider PCR test, isolation, and oxygen support.'
  else if "Possible COVID-19" then
    'Consider COVID-19 testing and isolation precautions.'
  else
    null
