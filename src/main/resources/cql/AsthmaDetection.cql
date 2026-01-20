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

// Asthma Vital Signs Pattern (ALL must match):
// HR >120, RR >30, SpO₂ <90%

define "Asthma Tachycardia":
  "Heart Rate" is not null and "Heart Rate" > 120

define "Asthma Tachypnea":
  "Respiratory Rate" is not null and "Respiratory Rate" > 30

define "Asthma Hypoxia":
  "SpO2" is not null and "SpO2" < 90

define "Asthma Detected":
  "Asthma Tachycardia" and "Asthma Tachypnea" and "Asthma Hypoxia"
