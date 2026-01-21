# Citation Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive citation management test cases for the cardiovascular measurement ontology.

## Table of Contents
1. [Basic Citation Management](#1-basic-citation-management)
2. [Medical Guidelines Citations](#2-medical-guidelines-citations)
3. [Research Paper Citations](#3-research-paper-citations)
4. [Clinical Standard Citations](#4-clinical-standard-citations)
5. [Platform Documentation Citations](#5-platform-documentation-citations)
6. [Citation Formats](#6-citation-formats)
7. [Bibliography Generation](#7-bibliography-generation)
8. [Citation Networks](#8-citation-networks)
9. [Version Tracking](#9-version-tracking)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Basic Citation Management

### Citation 1.1: Add Citation to Blood Pressure Class

**Source**: American Heart Association (AHA) Blood Pressure Guidelines

**Citation Data**:
```json
{
  "id": "AHA_BP_2020",
  "type": "clinical-guideline",
  "title": "2017 ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/ASPC/NMA/PCNA Guideline for the Prevention, Detection, Evaluation, and Management of High Blood Pressure in Adults",
  "authors": [
    "Whelton PK",
    "Carey RM",
    "Aronow WS",
    "et al."
  ],
  "organization": "American Heart Association",
  "year": 2020,
  "url": "https://www.ahajournals.org/doi/pdf/10.1161/CIR.0000000000000803",
  "doi": "10.1161/CIR.0000000000000803",
  "accessDate": "2025-10-15"
}
```

**Attach to**: `:BloodPressure` class via `:referenceSource` annotation

**Expected Result**: Citation linked and retrievable from BloodPressure class

### Citation 1.2: Add Citation to Heart Rate Variability

**Source**: Task Force of the European Society of Cardiology

**Citation Data**:
```json
{
  "id": "ESC_HRV_1996",
  "type": "clinical-standard",
  "title": "Heart rate variability: standards of measurement, physiological interpretation and clinical use",
  "authors": [
    "Task Force of the European Society of Cardiology"
  ],
  "journal": "Circulation",
  "volume": "93",
  "issue": "5",
  "pages": "1043-1065",
  "year": 1996,
  "url": "https://www.ahajournals.org/doi/10.1161/01.cir.93.5.1043",
  "doi": "10.1161/01.cir.93.5.1043",
  "pmid": "8598068"
}
```

**Attach to**: `:HeartRateVariability` class

### Citation 1.3: Bulk Import from BibTeX

**BibTeX File** (references.bib):
```bibtex
@article{whelton2018acc,
  title={2017 ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/ASPC/NMA/PCNA guideline for the prevention, detection, evaluation, and management of high blood pressure in adults},
  author={Whelton, Paul K and Carey, Robert M and Aronow, Wilbert S and others},
  journal={Journal of the American College of Cardiology},
  volume={71},
  number={19},
  pages={e127--e248},
  year={2018},
  publisher={American College of Cardiology Foundation}
}

@article{taskforce1996hrv,
  title={Heart rate variability: standards of measurement, physiological interpretation and clinical use},
  author={{Task Force of the European Society of Cardiology}},
  journal={Circulation},
  volume={93},
  number={5},
  pages={1043--1065},
  year={1996},
  publisher={Am Heart Assoc}
}

@misc{heart_foundation_bp,
  title={What is normal blood pressure by age?},
  author={{Heart Research Institute}},
  year={2024},
  url={https://www.hri.org.au/health/learn/risk-factors/what-is-normal-blood-pressure-by-age},
  note={Accessed: 2025-10-15}
}
```

**Import Command**: Import references.bib into citation database

**Expected Result**: 3 citations imported and available for linking

---

## 2. Medical Guidelines Citations

### Guideline 2.1: AHA Blood Pressure Guidelines

**Full Citation (APA)**:
```
Whelton, P. K., Carey, R. M., Aronow, W. S., Casey, D. E., Collins, K. J., 
Dennison Himmelfarb, C., ... & Wright, J. T. (2018). 2017 
ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/ASPC/NMA/PCNA guideline for the prevention, 
detection, evaluation, and management of high blood pressure in adults: a report 
of the American College of Cardiology/American Heart Association Task Force on 
Clinical Practice Guidelines. Journal of the American College of Cardiology, 
71(19), e127-e248.
```

**Linked to**:
- `:BloodPressure` class
- `:refLow` annotation (90/60 mmHg)
- `:refHigh` annotation (120/80 mmHg)

**Evidence Grade**: V1 (High confidence)

### Guideline 2.2: HRI Blood Pressure Age Ranges

**Full Citation (APA)**:
```
Heart Research Institute. (2024). What is normal blood pressure by age? 
Retrieved from https://www.hri.org.au/health/learn/risk-factors/what-is-normal-blood-pressure-by-age
```

**Linked to**:
- `:BloodPressure` class
- `:ageBand` annotation

### Guideline 2.3: AHA Heart Rate Guidelines

**Full Citation (APA)**:
```
American Heart Association. (2020). Target Heart Rates Chart. 
Retrieved from https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates
```

**Linked to**:
- `:HeartRate` class
- `:Pulse` class
- `:WalkingHeartRate` class

---

## 3. Research Paper Citations

### Paper 3.1: HRV Measurement Standards

**Full Citation (IEEE)**:
```
Task Force of the European Society of Cardiology and the North American Society 
of Pacing and Electrophysiology, "Heart rate variability: standards of measurement, 
physiological interpretation and clinical use," Circulation, vol. 93, no. 5, 
pp. 1043-1065, Mar. 1996, doi: 10.1161/01.cir.93.5.1043.
```

**Abstract**:
> This paper establishes international standards for HRV measurement, including 
> time-domain, frequency-domain, and non-linear methods. It defines SDNN, RMSSD, 
> pNN50, and other key metrics.

**Key Findings**:
- SDNN > 50ms indicates healthy autonomic function
- Ultra-short-term (1-3 min), short-term (5 min), and long-term (24h) measurements
- PPG and ECG method validation

**Linked to**:
- `:HeartRateVariability` class
- `:aggregationWindow` annotation
- `:minReadings` annotation

### Paper 3.2: HRV Ultra-Short-Term Analysis

**Full Citation (APA)**:
```
Baek, H. J., Cho, C. H., Cho, J., & Woo, J. M. (2015). Reliability of ultra-short-term 
analysis as a surrogate of standard 5-min analysis of heart rate variability. 
Telemedicine and e-Health, 21(5), 404-414.
```

**DOI**: 10.1089/tmj.2014.0104
**PubMed ID**: 25807067

**Linked to**:
- `:HeartRateVariability` class
- `:aggregationWindow` annotation (1-3 min validation)

### Paper 3.3: PPG-Based Heart Rate Accuracy

**Full Citation (MLA)**:
```
Bent, B., et al. "Investigating sources of inaccuracy in wearable optical heart rate 
sensors." NPJ digital medicine 3.1 (2020): 1-9.
```

**Linked to**:
- `:HeartRate` class
- `:Pulse` class
- `:device` annotation (PPG method)

---

## 4. Clinical Standard Citations

### Standard 4.1: LOINC Blood Pressure Codes

**Citation**:
```
Regenstrief Institute. (2024). LOINC Codes for Blood Pressure.
- 8480-6: Systolic blood pressure
- 8462-4: Diastolic blood pressure
- 85354-9: Blood pressure panel with all children optional
Retrieved from https://loinc.org
```

**Linked to**:
- `:BloodPressure` class via `:loincCode` annotation

### Standard 4.2: LOINC Heart Rate Codes

**Citation**:
```
Regenstrief Institute. (2024). LOINC Code 8867-4: Heart rate.
Retrieved from https://loinc.org/8867-4/
```

**Linked to**:
- `:HeartRate` class
- `:Pulse` class
- `:WalkingHeartRate` class

### Standard 4.3: SNOMED CT Cardiovascular Concepts

**Citation**:
```
SNOMED International. (2024). SNOMED CT Cardiovascular System Concepts.
- 271649006: Systolic blood pressure
- 271650006: Diastolic blood pressure
- 364075005: Heart rate
Retrieved from https://www.snomed.org/
```

**Usage**: Cross-reference with LOINC codes for interoperability

---

## 5. Platform Documentation Citations

### Platform 5.1: Apple HealthKit Documentation

**Citation**:
```
Apple Inc. (2024). HealthKit Framework - Heart Rate and Blood Pressure.
- HKQuantityTypeIdentifierHeartRate
- HKQuantityTypeIdentifierBloodPressureSystolic
- HKQuantityTypeIdentifierBloodPressureDiastolic
- HKQuantityTypeIdentifierHeartRateVariabilitySDNN
- HKQuantityTypeIdentifierWalkingHeartRateAverage

Apple Developer Documentation. 
Retrieved from https://developer.apple.com/documentation/healthkit
```

**White Paper**:
```
Apple Inc. (2024). Heart Rate, Calorimetry, and Activity on Apple Watch. 
Apple Health White Paper. November 2024.
URL: https://www.apple.com/health/pdf/Heart_Rate_Calorimetry_Activity_on_Apple_Watch_November_2024.pdf
```

**Linked to**:
- `:platformID_Apple` annotations on all measurement classes
- Device individuals (AppleWatch_Series8)

### Platform 5.2: Google Fit Data Types

**Citation**:
```
Google. (2024). Google Fit Data Types.
- com.google.blood_pressure_systolic
- com.google.blood_pressure_diastolic
- com.google.heart_rate.bpm
- com.google.heart_rate.walking.average

Google Developers Documentation.
Retrieved from https://developers.google.com/fit/datatypes
```

**Linked to**: `:platformID_GoogleFit` annotations

### Platform 5.3: Samsung Health SDK

**Citation**:
```
Samsung Electronics. (2024). Samsung Health SDK - Health Data Types.
- HealthConstants.BloodPressure.SYSTOLIC
- HealthConstants.BloodPressure.DIASTOLIC
- HealthConstants.HeartRate.HEART_RATE
- Interbeat Interval (IBI) at 1 Hz frequency

Samsung Health Developer Portal.
Retrieved from https://developer.samsung.com/health
```

**Linked to**: `:platformID_Samsung` annotations

### Platform 5.4: Garmin Connect IQ

**Citation**:
```
Garmin Ltd. (2024). Connect IQ API Documentation.
- Activity.Info.currentHeartRate
- Toybox.ActivityMonitor.HeartRateIterator
- Toybox.Sensor.HeartRateData
- COMPLICATION_TYPE_PULSE_OX

Garmin Connect IQ Developer Portal.
Retrieved from https://developer.garmin.com/connect-iq/api-docs/
```

**Linked to**: `:platformID_Garmin` annotations

---

## 6. Citation Formats

### Format 6.1: APA (7th Edition)

**Blood Pressure Guideline**:
```
Whelton, P. K., Carey, R. M., Aronow, W. S., Casey, D. E., Collins, K. J., 
Dennison Himmelfarb, C., DePalma, S. M., Gidding, S., Jamerson, K. A., 
Jones, D. W., MacLaughlin, E. J., Muntner, P., Ovbiagele, B., Smith, S. C., 
Spencer, C. C., Stafford, R. S., Taler, S. J., Thomas, R. J., Williams, K. A., 
... Wright, J. T. (2018). 2017 ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/ASPC/NMA/PCNA 
guideline for the prevention, detection, evaluation, and management of high 
blood pressure in adults: A report of the American College of Cardiology/American 
Heart Association Task Force on Clinical Practice Guidelines. Journal of the 
American College of Cardiology, 71(19), e127-e248. 
https://doi.org/10.1016/j.jacc.2017.11.006
```

### Format 6.2: IEEE

**HRV Standards**:
```
[1] Task Force of the European Society of Cardiology and the North American 
    Society of Pacing and Electrophysiology, "Heart rate variability: standards 
    of measurement, physiological interpretation and clinical use," Circulation, 
    vol. 93, no. 5, pp. 1043-1065, Mar. 1996, doi: 10.1161/01.cir.93.5.1043.
```

### Format 6.3: Vancouver

**Heart Rate Guidelines**:
```
American Heart Association. Target Heart Rates Chart [Internet]. Dallas (TX): 
American Heart Association; 2020 [cited 2025 Oct 15]. Available from: 
https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates
```

### Format 6.4: MLA (9th Edition)

**PPG Accuracy Study**:
```
Bent, Brinnae, et al. "Investigating sources of inaccuracy in wearable optical 
heart rate sensors." NPJ Digital Medicine, vol. 3, no. 1, 2020, pp. 1-9, 
doi:10.1038/s41746-020-0226-6.
```

### Format 6.5: BibTeX

```bibtex
@article{whelton20182017,
  title={2017 ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/ASPC/NMA/PCNA guideline for the prevention, detection, evaluation, and management of high blood pressure in adults: a report of the American College of Cardiology/American Heart Association Task Force on Clinical Practice Guidelines},
  author={Whelton, Paul K and Carey, Robert M and Aronow, Wilbert S and others},
  journal={Journal of the American College of Cardiology},
  volume={71},
  number={19},
  pages={e127--e248},
  year={2018},
  publisher={American College of Cardiology Foundation},
  doi={10.1016/j.jacc.2017.11.006}
}

@article{taskforce1996hrv,
  title={Heart rate variability: standards of measurement, physiological interpretation and clinical use},
  author={{Task Force of the European Society of Cardiology and the North American Society of Pacing and Electrophysiology}},
  journal={Circulation},
  volume={93},
  number={5},
  pages={1043--1065},
  year={1996},
  doi={10.1161/01.cir.93.5.1043},
  pmid={8598068}
}
```

---

## 7. Bibliography Generation

### Bibliography 7.1: Complete Ontology Bibliography

**Generate for**: Entire cardiovascular ontology

**Expected Sections**:

**1. Clinical Guidelines**
```
American Heart Association. (2020). Target Heart Rates Chart...
Heart Research Institute. (2024). What is normal blood pressure by age?...
Whelton, P. K., et al. (2018). 2017 ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/...
```

**2. Research Articles**
```
Baek, H. J., et al. (2015). Reliability of ultra-short-term analysis...
Bent, B., et al. (2020). Investigating sources of inaccuracy in wearable...
Task Force of the European Society of Cardiology. (1996). Heart rate variability...
```

**3. Clinical Standards**
```
Regenstrief Institute. (2024). LOINC Codes for Blood Pressure...
SNOMED International. (2024). SNOMED CT Cardiovascular System Concepts...
```

**4. Platform Documentation**
```
Apple Inc. (2024). HealthKit Framework - Heart Rate and Blood Pressure...
Garmin Ltd. (2024). Connect IQ API Documentation...
Google. (2024). Google Fit Data Types...
Samsung Electronics. (2024). Samsung Health SDK - Health Data Types...
```

**Total References**: 15+

### Bibliography 7.2: Class-Specific Bibliography

**Generate for**: `:BloodPressure` class only

**Expected References**:
1. AHA Blood Pressure Guidelines (2018)
2. Heart Research Institute BP Age Ranges (2024)
3. LOINC Code 8480-6, 8462-4
4. Apple HealthKit BP identifiers
5. Google Fit BP data types
6. Samsung Health BP constants
7. Garmin BP support documentation

**Format**: APA 7th edition

### Bibliography 7.3: Export Bibliography

**Export Formats**:
- **PDF**: Formatted bibliography document
- **BibTeX**: references.bib file
- **RIS**: For Mendeley, Zotero, EndNote
- **JSON**: Machine-readable format

**File Names**:
- `cardiovascular_ontology_bibliography.pdf`
- `cardiovascular_ontology_references.bib`
- `cardiovascular_ontology_citations.ris`
- `cardiovascular_ontology_citations.json`

---

## 8. Citation Networks

### Network 8.1: Visualize Citation Graph

**Graph Structure**:
```
[BloodPressure Class]
    ├── [AHA BP Guidelines 2018]
    │   └── Cited by: [CDC Guidelines 2020]
    ├── [HRI BP Age Ranges 2024]
    └── [LOINC Codes]

[HeartRateVariability Class]
    ├── [ESC Task Force 1996]
    │   ├── Cited by: [Shaffer & Ginsberg 2017]
    │   └── Cited by: [Baek et al. 2015]
    └── [HRV Ultra-Short Analysis 2015]
```

**Visualization**: Force-directed graph with classes and citations

### Network 8.2: Find Related Citations

**Query**: "Find all citations related to Heart Rate"

**Expected Results**:
- AHA Target Heart Rates (HeartRate, Pulse, WalkingHeartRate)
- Apple HealthKit HR documentation (HeartRate)
- ESC HRV Standards (HeartRateVariability)
- PPG accuracy studies (Pulse)

**Relationship**: All connected through "cardiovascular measurement" topic

### Network 8.3: Citation Timeline

**Visualization**: Timeline showing when references were published

```
1996 ─── [ESC HRV Standards]
         │
2015 ─── [Baek HRV Ultra-Short]
         │
2018 ─── [AHA BP Guidelines]
         │
2020 ─── [Apple HR White Paper]
         │
2024 ─── [HRI BP Age Ranges]
         │
         [Current]
```

---

## 9. Version Tracking

### Version 9.1: Citation Update History

**Citation**: AHA Blood Pressure Guidelines

**Version History**:
```
v1 (2017): Original guideline published
v2 (2018): Published in JACC
v3 (2020): Updated online version with corrections
```

**Ontology Link**: Currently links to v3 (2020)

**Change Log**:
- 2025-10-15: Updated to v3 with latest URL
- 2025-09-26: Initial citation added (v2)

### Version 9.2: Source Version Tracking

**BloodPressure Class**:
- `:sourceVersion`: "2020"
- `:validationInfo`: "Gideon Towett – 15 Oct 2025"

**Audit Trail**:
```
2025-10-15: Reference source updated to AHA 2020 version
2025-09-26: Initial validation completed
```

### Version 9.3: Deprecated Citations

**Old Citation** (deprecated):
```
American Heart Association. (2003). Seventh Report of the Joint National 
Committee on Prevention, Detection, Evaluation, and Treatment of High Blood 
Pressure (JNC 7).
```

**Replacement**:
```
Whelton, P. K., et al. (2018). 2017 ACC/AHA guideline... [Current]
```

**Deprecation Note**: "Replaced by 2017 ACC/AHA guideline; thresholds updated"

---

## 10. Testing Checklist

### Basic Citation Management
- [ ] Add citation to class successfully
- [ ] Add citation to annotation property
- [ ] Edit existing citation
- [ ] Delete citation
- [ ] Search citations by keyword

### Citation Import/Export
- [ ] Import BibTeX file (3+ citations)
- [ ] Import RIS file
- [ ] Export citations to BibTeX
- [ ] Export citations to RIS
- [ ] Export citations to PDF

### Citation Formats
- [ ] Generate APA format correctly
- [ ] Generate IEEE format correctly
- [ ] Generate Vancouver format correctly
- [ ] Generate MLA format correctly
- [ ] Switch between formats seamlessly

### Medical Citations
- [ ] Link clinical guidelines (AHA, ESC)
- [ ] Link research papers (PubMed IDs work)
- [ ] Link clinical standards (LOINC, SNOMED)
- [ ] Link platform documentation (Apple, Google, etc.)

### Bibliography Generation
- [ ] Generate full ontology bibliography (15+ refs)
- [ ] Generate class-specific bibliography
- [ ] Generate property-specific bibliography
- [ ] Sort by author/year/title
- [ ] Filter by citation type

### Citation Networks
- [ ] Visualize citation graph
- [ ] Find related citations
- [ ] Display citation timeline
- [ ] Show citation count per class
- [ ] Highlight most-cited sources

### Version Tracking
- [ ] Track citation version history
- [ ] Display source version on classes
- [ ] Show audit trail
- [ ] Flag deprecated citations
- [ ] Update citations to latest version

### Integration
- [ ] Citations linked to graph view nodes
- [ ] Citations searchable in ontology
- [ ] Citations exported with ontology
- [ ] DOI links resolve correctly
- [ ] PubMed IDs link correctly

### Performance
- [ ] Load 50+ citations quickly (< 1 sec)
- [ ] Search citations fast (< 200ms)
- [ ] Generate bibliography fast (< 2 sec)
- [ ] Export large bibliographies (< 5 sec)

### Error Handling
- [ ] Handle invalid BibTeX gracefully
- [ ] Detect duplicate citations
- [ ] Warn on missing required fields (author, year, title)
- [ ] Handle broken URLs/DOIs
- [ ] Validate PMID format

---

## Appendix A: Citation Metadata Fields

### Required Fields
- `id`: Unique identifier
- `type`: Citation type (guideline, research-paper, standard, documentation)
- `title`: Full title
- `authors`: Array of author names
- `year`: Publication year

### Optional Fields
- `journal`: Journal name
- `volume`: Volume number
- `issue`: Issue number
- `pages`: Page range
- `publisher`: Publisher name
- `organization`: Organization/institution
- `doi`: Digital Object Identifier
- `url`: Web URL
- `pmid`: PubMed ID
- `isbn`: Book ISBN
- `edition`: Edition number
- `accessDate`: Date accessed (for web sources)
- `notes`: Additional notes
- `keywords`: Array of keywords
- `abstract`: Abstract text

---

## Appendix B: Citation Types

- **clinical-guideline**: Medical guidelines (AHA, ESC, ACC)
- **research-paper**: Peer-reviewed journal articles
- **clinical-standard**: Standards (LOINC, SNOMED CT, HL7)
- **documentation**: Platform/API documentation (Apple, Google)
- **white-paper**: Technical white papers
- **book**: Medical textbooks
- **web**: Web resources
- **conference**: Conference proceedings
- **thesis**: Doctoral dissertations

---

## Appendix C: Example references.bib

Complete BibTeX file for cardiovascular ontology:

```bibtex
@article{whelton20182017,
  title={2017 ACC/AHA/AAPA/ABC/ACPM/AGS/APhA/ASH/ASPC/NMA/PCNA guideline for the prevention, detection, evaluation, and management of high blood pressure in adults},
  author={Whelton, Paul K and Carey, Robert M and Aronow, Wilbert S and others},
  journal={Journal of the American College of Cardiology},
  volume={71},
  number={19},
  pages={e127--e248},
  year={2018},
  doi={10.1016/j.jacc.2017.11.006}
}

@article{taskforce1996hrv,
  title={Heart rate variability: standards of measurement, physiological interpretation and clinical use},
  author={{Task Force of the European Society of Cardiology}},
  journal={Circulation},
  volume={93},
  number={5},
  pages={1043--1065},
  year={1996},
  doi={10.1161/01.cir.93.5.1043}
}

@article{baek2015reliability,
  title={Reliability of ultra-short-term analysis as a surrogate of standard 5-min analysis of heart rate variability},
  author={Baek, Hun-Joon and Cho, Chul-Ho and Cho, Jaehak and Woo, Jong-Min},
  journal={Telemedicine and e-Health},
  volume={21},
  number={5},
  pages={404--414},
  year={2015},
  doi={10.1089/tmj.2014.0104}
}

@misc{aha_target_hr,
  title={Target Heart Rates Chart},
  author={{American Heart Association}},
  year={2020},
  url={https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates}
}

@misc{hri_bp_age,
  title={What is normal blood pressure by age?},
  author={{Heart Research Institute}},
  year={2024},
  url={https://www.hri.org.au/health/learn/risk-factors/what-is-normal-blood-pressure-by-age}
}

@techreport{apple_hr_whitepaper,
  title={Heart Rate, Calorimetry, and Activity on Apple Watch},
  author={{Apple Inc.}},
  institution={Apple Health},
  year={2024},
  url={https://www.apple.com/health/pdf/Heart_Rate_Calorimetry_Activity_on_Apple_Watch_November_2024.pdf}
}
```

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0
