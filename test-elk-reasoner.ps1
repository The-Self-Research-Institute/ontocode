# Test Script for ELK Reasoner
# This script tests ELK reasoner functionality with sample ontologies

$ErrorActionPreference = "Stop"

# Color output
function Write-Success { Write-Host $args -ForegroundColor Green }
function Write-Error { Write-Host $args -ForegroundColor Red }
function Write-Warn { Write-Host $args -ForegroundColor Yellow }
function Write-Info { Write-Host $args -ForegroundColor Cyan }

# Configuration
$PLUGIN_SERVICE_URL = "http://localhost:8081"
$PROJECT_ID = "test-elk-$(Get-Random -Minimum 1000 -Maximum 9999)"
$REASONER_TYPE = "ELK"

Write-Info "======================================="
Write-Info "ELK Reasoner Test Script"
Write-Info "======================================="

# Test 1: Check if plugin service is running
Write-Info "`n[Test 1] Checking plugin service availability..."
try {
    $response = Invoke-WebRequest -Uri "$PLUGIN_SERVICE_URL/api/reasoner/$PROJECT_ID/stats" `
        -Method GET `
        -ErrorAction SilentlyContinue `
        -WarningAction SilentlyContinue
    Write-Warn "Plugin service is running but no ontology loaded yet (expected)"
} catch {
    Write-Warn "Plugin service may not be running at $PLUGIN_SERVICE_URL"
    Write-Info "Make sure to start the plugin-service: mvn spring-boot:run -pl ontology-plugin-service"
}

# Test 2: Create a simple EL-profile compliant ontology in RDF/XML
Write-Info "`n[Test 2] Creating sample EL-profile compliant ontology..."

$sampleOntologyPath = "c:\temp\test-ontology-el.owl"
New-Item -ItemType Directory -Path "c:\temp" -Force > $null

$eloContent = @"
<?xml version="1.0" encoding="UTF-8"?>
<rdf:RDF 
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:test="http://example.org/test-ontology/">
    
    <owl:Ontology rdf:about="http://example.org/test-ontology/">
        <rdfs:label>Test EL Profile Ontology</rdfs:label>
        <rdfs:comment>Sample ontology compatible with ELK (EL profile)</rdfs:comment>
    </owl:Ontology>
    
    <!-- Classes -->
    <owl:Class rdf:about="http://example.org/test-ontology/Animal">
        <rdfs:label>Animal</rdfs:label>
    </owl:Class>
    
    <owl:Class rdf:about="http://example.org/test-ontology/Mammal">
        <rdfs:label>Mammal</rdfs:label>
        <rdfs:subClassOf rdf:resource="http://example.org/test-ontology/Animal"/>
    </owl:Class>
    
    <owl:Class rdf:about="http://example.org/test-ontology/Dog">
        <rdfs:label>Dog</rdfs:label>
        <rdfs:subClassOf rdf:resource="http://example.org/test-ontology/Mammal"/>
    </owl:Class>
    
    <owl:Class rdf:about="http://example.org/test-ontology/Cat">
        <rdfs:label>Cat</rdfs:label>
        <rdfs:subClassOf rdf:resource="http://example.org/test-ontology/Mammal"/>
    </owl:Class>
    
    <owl:Class rdf:about="http://example.org/test-ontology/Pet">
        <rdfs:label>Pet</rdfs:label>
    </owl:Class>
    
    <!-- Object Properties -->
    <owl:ObjectProperty rdf:about="http://example.org/test-ontology/hasOwner">
        <rdfs:label>has owner</rdfs:label>
        <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#ObjectProperty"/>
    </owl:ObjectProperty>
    
    <!-- Simple restriction using existential quantification (EL-compatible) -->
    <owl:Class rdf:about="http://example.org/test-ontology/OwnedAnimal">
        <rdfs:label>Owned Animal</rdfs:label>
        <owl:equivalentClass>
            <owl:Class>
                <owl:intersectionOf rdf:parseType="Collection">
                    <rdf:Description rdf:about="http://example.org/test-ontology/Animal"/>
                    <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/test-ontology/hasOwner"/>
                        <owl:someValuesFrom rdf:resource="http://www.w3.org/2002/07/owl#Thing"/>
                    </owl:Restriction>
                </owl:intersectionOf>
            </owl:Class>
        </owl:equivalentClass>
    </owl:Class>
    
    <!-- More specific class -->
    <owl:Class rdf:about="http://example.org/test-ontology/DomesticPet">
        <rdfs:label>Domestic Pet</rdfs:label>
        <owl:equivalentClass>
            <owl:Class>
                <owl:intersectionOf rdf:parseType="Collection">
                    <rdf:Description rdf:about="http://example.org/test-ontology/Pet"/>
                    <rdf:Description rdf:about="http://example.org/test-ontology/OwnedAnimal"/>
                </owl:intersectionOf>
            </owl:Class>
        </owl:equivalentClass>
    </owl:Class>
    
</rdf:RDF>
"@

$eloContent | Set-Content -Path $sampleOntologyPath -Encoding UTF8
Write-Success "Sample EL ontology created at: $sampleOntologyPath"

# Test 3: Create alternative OWL 2 DL ontology (for comparison)
Write-Info "`n[Test 3] Creating sample OWL 2 DL ontology (may not work with ELK)..."

$owl2dlPath = "c:\temp\test-ontology-owl2.owl"

$owl2Content = @"
<?xml version="1.0" encoding="UTF-8"?>
<rdf:RDF 
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:test="http://example.org/test-ontology2/">
    
    <owl:Ontology rdf:about="http://example.org/test-ontology2/">
        <rdfs:label>Test OWL 2 DL Ontology</rdfs:label>
        <rdfs:comment>Sample ontology with full OWL 2 DL features</rdfs:comment>
    </owl:Ontology>
    
    <!-- Classes -->
    <owl:Class rdf:about="http://example.org/test-ontology2/Vehicle"/>
    <owl:Class rdf:about="http://example.org/test-ontology2/Car">
        <rdfs:subClassOf rdf:resource="http://example.org/test-ontology2/Vehicle"/>
    </owl:Class>
    
    <!-- Properties -->
    <owl:ObjectProperty rdf:about="http://example.org/test-ontology2/hasEngine"/>
    <owl:DataProperty rdf:about="http://example.org/test-ontology2/hasWheels"/>
    
    <!-- Disjoint classes (OWL 2 DL specific) -->
    <owl:Class rdf:about="http://example.org/test-ontology2/Bicycle">
        <rdfs:subClassOf rdf:resource="http://example.org/test-ontology2/Vehicle"/>
        <owl:disjointWith rdf:resource="http://example.org/test-ontology2/Car"/>
    </owl:Class>
    
    <!-- Property restrictions (OWL 2 DL) -->
    <owl:Class rdf:about="http://example.org/test-ontology2/EngineVehicle">
        <rdfs:subClassOf>
            <owl:Restriction>
                <owl:onProperty rdf:resource="http://example.org/test-ontology2/hasEngine"/>
                <owl:minQualifiedCardinality rdf:datatype="http://www.w3.org/2001/XMLSchema#nonNegativeInteger">1</owl:minQualifiedCardinality>
                <owl:onClass rdf:resource="http://www.w3.org/2002/07/owl#Thing"/>
            </owl:Restriction>
        </rdfs:subClassOf>
    </owl:Class>
    
</rdf:RDF>
"@

$owl2Content | Set-Content -Path $owl2dlPath -Encoding UTF8
Write-Success "Sample OWL 2 DL ontology created at: $owl2dlPath"

# Test 4: Create API test for classification
Write-Info "`n[Test 4] Creating test request for ELK classification..."

$testPayload = @{
    reasonerType = "ELK"
} | ConvertTo-Json

Write-Info "Test payload:"
Write-Info $testPayload

# Test 5: Display instructions for manual testing
Write-Info "`n[Test 5] Manual testing instructions:"
Write-Info "========================================="
Write-Info ""
Write-Info "To test ELK reasoner with these sample ontologies:"
Write-Info ""
Write-Info "1. Start the plugin-service (if not already running):"
Write-Info "   cd ontology-plugin-service"
Write-Info "   mvn spring-boot:run"
Write-Info ""
Write-Info "2. Upload one of the test ontologies through the editor UI"
Write-Info ""
Write-Info "3. Use curl to test classification with ELK:"
Write-Info ""
Write-Info "   For EL Profile ontology (should work):"
Write-Info "   curl -X POST http://localhost:8081/api/reasoner/your-project-id/classify \"
Write-Info "     -H 'Content-Type: application/json' \"
Write-Info "     -d '{""reasonerType"": ""ELK""}'"
Write-Info ""
Write-Info "4. Check logs for detailed information about:"
Write-Info "   - Class hierarchy generation"
Write-Info "   - Property hierarchies"
Write-Info "   - Any unsupported construct warnings"
Write-Info ""
Write-Info "Expected behavior:"
Write-Info "   - EL Profile ontology (test-ontology-el.owl): Should work"
Write-Info "   - OWL 2 DL ontology (test-ontology-owl2.owl): May show warnings"
Write-Info ""

# Test 6: Create Turtle format test ontology
Write-Info "`n[Test 6] Creating Turtle format test ontology..."

$turtlePath = "c:\temp\test-ontology-el.ttl"

$turtleContent = @"
@prefix : <http://example.org/test-ontology/> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

<http://example.org/test-ontology/> a owl:Ontology ;
    rdfs:label "Test EL Profile Ontology"@en ;
    rdfs:comment "Sample ontology compatible with ELK (EL profile)"@en .

# Classes
:Animal a owl:Class ;
    rdfs:label "Animal"@en .

:Mammal a owl:Class ;
    rdfs:label "Mammal"@en ;
    rdfs:subClassOf :Animal .

:Dog a owl:Class ;
    rdfs:label "Dog"@en ;
    rdfs:subClassOf :Mammal .

:Cat a owl:Class ;
    rdfs:label "Cat"@en ;
    rdfs:subClassOf :Mammal .

# Object Properties
:hasOwner a owl:ObjectProperty ;
    rdfs:label "has owner"@en .

:hasPart a owl:ObjectProperty ;
    rdfs:label "has part"@en ;
    a owl:TransitiveProperty .

# EL-compliant complex class
:OwnedAnimal owl:equivalentClass [
    a owl:Class ;
    owl:intersectionOf (:Animal [ 
        a owl:Restriction ;
        owl:onProperty :hasOwner ;
        owl:someValuesFrom owl:Thing
    ])
] .

:DomesticPet a owl:Class ;
    rdfs:label "Domestic Pet"@en ;
    owl:intersectionOf (:Animal :OwnedAnimal) .
"@

$turtleContent | Set-Content -Path $turtlePath -Encoding UTF8
Write-Success "Sample Turtle ontology created at: $turtlePath"

# Test 7: Curl commands for testing
Write-Info "`n[Test 7] Creating curl test commands..."

$curlTestPath = "c:\temp\test-elk-requests.sh"

$curlCommands = @"
#!/bin/bash

# ELK Classification Test Commands

echo "Testing ELK Reasoner Classification"
echo "===================================="

PROJECT_ID="test-project-1"
PLUGIN_SERVICE="http://localhost:8081"

# Test 1: Classify with ELK
echo ""
echo "Test 1: ELK Reasoner Classification"
curl -X POST "$PLUGIN_SERVICE/api/reasoner/$PROJECT_ID/classify" \
  -H "Content-Type: application/json" \
  -d '{"reasonerType":"ELK"}' | jq .

# Test 2: Get classification stats
echo ""
echo "Test 2: Get Reasoner Stats"
curl -X GET "$PLUGIN_SERVICE/api/reasoner/$PROJECT_ID/stats?reasonerType=ELK" | jq .

# Test 3: Test with HermiT for comparison
echo ""
echo "Test 3: HermiT Reasoner Classification (for comparison)"
curl -X POST "$PLUGIN_SERVICE/api/reasoner/$PROJECT_ID/classify" \
  -H "Content-Type: application/json" \
  -d '{"reasonerType":"HERMIT"}' | jq . 2>/dev/null || echo "HermiT may not be installed"

# Test 4: Consistency check
echo ""
echo "Test 4: Consistency Check with ELK"
curl -X POST "$PLUGIN_SERVICE/api/reasoner/$PROJECT_ID/consistency" \
  -H "Content-Type: application/json" \
  -d '{"reasonerType":"ELK"}' | jq .

echo ""
echo "Tests completed!"
"@

$curlCommands | Set-Content -Path $curlTestPath -Encoding UTF8
Write-Success "Curl test commands saved to: $curlTestPath"

# Test 8: Summary
Write-Info "`n[Test Summary]"
Write-Info "=============="
Write-Success "✓ EL Profile ontologies: $sampleOntologyPath"
Write-Success "✓ OWL 2 DL ontology: $owl2dlPath"
Write-Success "✓ Turtle format ontology: $turtlePath"
Write-Success "✓ Curl test script: $curlTestPath"
Write-Info ""
Write-Info "Next steps:"
Write-Info "1. Make sure plugin-service is running: mvn spring-boot:run"
Write-Info "2. Upload test ontologies through the editor"
Write-Info "3. Test ELK classification endpoint"
Write-Info "4. Check logs for warnings about unsupported constructs"
Write-Info ""
