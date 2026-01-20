library SepsisDetection version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'

// Vital sign codes
code "Heart Rate": '8867-4' from "LOINC"
code "Respiratory Rate": '9279-1' from "LOINC"
code "Systolic BP": '8480-6' from "LOINC"
code "Body Temperature": '8310-5' from "LOINC"
code "Oxygen Saturation": '59408-5' from "LOINC"

context Patient

// Get recent vital sign observations (last 24 hours)
define "Recent Observations":
  [Observation: category in "vital-signs"] O
    where O.status = 'final'
      and O.effective.value during Interval[Now() - 24 hours, Now()]

define "Heart Rate Observation":
  Last("Recent Observations" O where O.code ~ "Heart Rate")

define "Respiratory Rate Observation":
  Last("Recent Observations" O where O.code ~ "Respiratory Rate")

define "Systolic BP Observation":
  Last("Recent Observations" O where O.code ~ "Systolic BP")

define "Temperature Observation":
  Last("Recent Observations" O where O.code ~ "Body Temperature")

define "SpO2 Observation":
  Last("Recent Observations" O where O.code ~ "Oxygen Saturation")

// Extract values
define "Heart Rate":
  ("Heart Rate Observation".value as FHIR.Quantity).value

define "Respiratory Rate":
  ("Respiratory Rate Observation".value as FHIR.Quantity).value

define "Systolic BP":
  ("Systolic BP Observation".value as FHIR.Quantity).value

define "Temperature":
  ("Temperature Observation".value as FHIR.Quantity).value

define "SpO2":
  ("SpO2 Observation".value as FHIR.Quantity).value

// Sepsis Criteria (ALL must match):
// HR >90, SBP ≤90, RR ≥20, Temp >38.3°C or <36°C

define "Sepsis Tachycardia":
  "Heart Rate" is not null and "Heart Rate" > 90

define "Sepsis Hypotension":
  "Systolic BP" is not null and "Systolic BP" <= 90

define "Sepsis Tachypnea":
  "Respiratory Rate" is not null and "Respiratory Rate" >= 20

define "Sepsis Temperature Abnormal":
  "Temperature" is not null and ("Temperature" > 38.3 or "Temperature" < 36.0)

define "Sepsis Detected":
  "Sepsis Tachycardia" and "Sepsis Hypotension" and "Sepsis Tachypnea" and "Sepsis Temperature Abnormal"
