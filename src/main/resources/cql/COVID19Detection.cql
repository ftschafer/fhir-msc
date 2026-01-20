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

// COVID-19 Vital Signs Pattern (ALL must match):
// HR >100, BP >120/>80, RR >20, Temp >37.5°C, SpO₂ <90%

define "COVID Tachycardia":
  "Heart Rate" is not null and "Heart Rate" > 100

define "COVID Hypertension":
  ("Systolic BP" is not null and "Systolic BP" > 120) or
  ("Diastolic BP" is not null and "Diastolic BP" > 80)

define "COVID Tachypnea":
  "Respiratory Rate" is not null and "Respiratory Rate" > 20

define "COVID Fever":
  "Temperature" is not null and "Temperature" > 37.5

define "COVID Hypoxia":
  "SpO2" is not null and "SpO2" < 90

define "COVID19 Detected":
  "COVID Tachycardia" and "COVID Hypertension" and "COVID Tachypnea" and 
  "COVID Fever" and "COVID Hypoxia"
