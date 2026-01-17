library NEWS2Scoring version '1.0.0'

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

// NEWS2 Scoring Components

// Respiratory Rate Score
define "Respiratory Rate Score":
  case
    when "Respiratory Rate" is null then 0
    when "Respiratory Rate" <= 8 then 3
    when "Respiratory Rate" <= 11 then 1
    when "Respiratory Rate" <= 20 then 0
    when "Respiratory Rate" <= 24 then 2
    else 3
  end

// SpO2 Score (Scale 1)
define "SpO2 Score":
  case
    when "SpO2" is null then 0
    when "SpO2" <= 91 then 3
    when "SpO2" <= 93 then 2
    when "SpO2" <= 95 then 1
    else 0
  end

// Systolic BP Score
define "Systolic BP Score":
  case
    when "Systolic BP" is null then 0
    when "Systolic BP" <= 90 then 3
    when "Systolic BP" <= 100 then 2
    when "Systolic BP" <= 110 then 1
    when "Systolic BP" >= 220 then 3
    else 0
  end

// Heart Rate Score
define "Heart Rate Score":
  case
    when "Heart Rate" is null then 0
    when "Heart Rate" <= 40 then 3
    when "Heart Rate" <= 50 then 1
    when "Heart Rate" <= 90 then 0
    when "Heart Rate" <= 110 then 1
    when "Heart Rate" <= 130 then 2
    else 3
  end

// Temperature Score
define "Temperature Score":
  case
    when "Temperature" is null then 0
    when "Temperature" <= 35.0 then 3
    when "Temperature" <= 36.0 then 1
    when "Temperature" <= 38.0 then 0
    when "Temperature" <= 39.0 then 1
    else 2
  end

// Total NEWS2 Score
define "NEWS2 Total Score":
  "Respiratory Rate Score" + 
  "SpO2 Score" + 
  "Systolic BP Score" + 
  "Heart Rate Score" + 
  "Temperature Score"

// Risk Level
define "NEWS2 Risk Level":
  case
    when "NEWS2 Total Score" = 0 then 'low'
    when "NEWS2 Total Score" <= 4 then 'low-medium'
    when "NEWS2 Total Score" <= 6 then 'medium'
    else 'high'
  end

// Clinical Response
define "NEWS2 Clinical Response":
  case
    when "NEWS2 Total Score" = 0 then 'Continue routine monitoring'
    when "NEWS2 Total Score" <= 4 then 'Increase monitoring frequency. Inform registered nurse.'
    when "NEWS2 Total Score" <= 6 then 'Urgent response. Inform medical team. Increase monitoring to minimum hourly.'
    else 'EMERGENCY: Immediate medical review. Continuous monitoring.'
  end

// Requires Escalation
define "Requires Escalation":
  "NEWS2 Total Score" >= 5

// Detected (trigger condition creation)
define "Clinical Deterioration Detected":
  "NEWS2 Total Score" >= 3
