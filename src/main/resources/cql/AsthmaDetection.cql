library AsthmaDetection version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'

code "Heart Rate": '8867-4' from "LOINC"
code "Respiratory Rate": '9279-1' from "LOINC"
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

define "SpO2":
  (Last("Recent Observations" O where O.code ~ "Oxygen Saturation").value as FHIR.Quantity).value

// Asthma Severity Classification
define "Severe Asthma":
  "Heart Rate" is not null and "Heart Rate" > 120 and
  "Respiratory Rate" is not null and "Respiratory Rate" > 30 and
  "SpO2" is not null and "SpO2" < 90

define "Moderate Asthma":
  "Heart Rate" is not null and "Heart Rate" >= 100 and "Heart Rate" <= 120 and
  "Respiratory Rate" is not null and "Respiratory Rate" >= 20 and "Respiratory Rate" <= 30 and
  "SpO2" is not null and "SpO2" >= 90 and "SpO2" <= 95

define "Mild Asthma":
  "Heart Rate" is not null and "Heart Rate" < 100 and
  "Respiratory Rate" is not null and "Respiratory Rate" >= 20 and "Respiratory Rate" <= 30 and
  "SpO2" is not null and "SpO2" > 95

define "Asthma Severity":
  if "Severe Asthma" then 'severe'
  else if "Moderate Asthma" then 'moderate'
  else if "Mild Asthma" then 'mild'
  else null

define "Asthma Match Score":
  if "Severe Asthma" then 95
  else if "Moderate Asthma" then 85
  else if "Mild Asthma" then 75
  else 0

define "Asthma Risk Level":
  if "Severe Asthma" then 'high'
  else if "Moderate Asthma" then 'moderate'
  else if "Mild Asthma" then 'low'
  else 'none'

define "Asthma Detected":
  "Asthma Match Score" >= 75

define "Asthma Recommendation":
  if "Severe Asthma" then
    'URGENT: Severe asthma exacerbation. Consider immediate bronchodilators, steroids, and possible intubation.'
  else if "Moderate Asthma" then
    'Moderate asthma exacerbation. Increase bronchodilator frequency and consider steroids.'
  else if "Mild Asthma" then
    'Mild asthma symptoms. Continue regular medications and monitor.'
  else
    null
