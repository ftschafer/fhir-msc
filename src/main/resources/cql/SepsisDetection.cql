library SepsisDetection version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'
codesystem "SNOMED": 'http://snomed.info/sct'

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

// qSOFA Criteria (Quick Sequential Organ Failure Assessment)
define "qSOFA Altered Mentation":
  false // Would need GCS observation

define "qSOFA Low Blood Pressure":
  "Systolic BP" is not null and "Systolic BP" <= 100

define "qSOFA High Respiratory Rate":
  "Respiratory Rate" is not null and "Respiratory Rate" >= 22

define "qSOFA Score":
  (if "qSOFA Low Blood Pressure" then 1 else 0) +
  (if "qSOFA High Respiratory Rate" then 1 else 0)

// SIRS Criteria (Systemic Inflammatory Response Syndrome)
define "SIRS Tachycardia":
  "Heart Rate" is not null and "Heart Rate" > 90

define "SIRS Tachypnea":
  "Respiratory Rate" is not null and "Respiratory Rate" > 20

define "SIRS Temperature Abnormal":
  "Temperature" is not null and 
  ("Temperature" > 38.3 or "Temperature" < 36.0)

define "SIRS Count":
  (if "SIRS Tachycardia" then 1 else 0) +
  (if "SIRS Tachypnea" then 1 else 0) +
  (if "SIRS Temperature Abnormal" then 1 else 0)

// Hypoxia
define "Has Hypoxia":
  "SpO2" is not null and "SpO2" < 95

// Sepsis Risk Assessment
define "Possible Sepsis":
  "qSOFA Score" >= 2 or "SIRS Count" >= 2

define "High Risk Sepsis":
  "qSOFA Score" >= 2 and "Has Hypoxia" and "SIRS Count" >= 3

define "Sepsis Match Score":
  if "High Risk Sepsis" then 90
  else if "Possible Sepsis" then 70
  else if "qSOFA Score" >= 1 or "SIRS Count" >= 1 then 40
  else 0

define "Sepsis Risk Level":
  if "High Risk Sepsis" then 'high'
  else if "Possible Sepsis" then 'moderate'
  else 'low'

define "Sepsis Detected":
  "Sepsis Match Score" >= 60

define "Sepsis Recommendation":
  if "High Risk Sepsis" then 
    'URGENT: High risk for sepsis. Consider immediate evaluation, blood cultures, and empiric antibiotics.'
  else if "Possible Sepsis" then
    'WARNING: Possible sepsis. Monitor closely and consider workup for infection.'
  else
    null
