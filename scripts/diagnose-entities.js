#!/usr/bin/env node
/**
 * Complete diagnostic tool for entity loading issues
 * Checks if all entity types are being loaded correctly from the backend
 */

const axios = require('axios');

const PROJECT_ID = process.argv[2];
const BASE_URL = 'http://localhost:8081';

if (!PROJECT_ID) {
  console.error('Usage: node diagnose-entities.js <projectId>');
  console.error('Example: node diagnose-entities.js my-ontology');
  process.exit(1);
}

async function diagnoseEntities() {
  console.log('='.repeat(80));
  console.log(' ONTOLOGY ENTITIES DIAGNOSTIC TOOL');
  console.log('='.repeat(80));
  console.log(`Project ID: ${PROJECT_ID}`);
  console.log(`Backend URL: ${BASE_URL}`);
  console.log('='.repeat(80));
  console.log('');

  try {
    // 1. Check metadata
    console.log('1️⃣  CHECKING METADATA...');
    try {
      const metaRes = await axios.get(`${BASE_URL}/api/ontology/metadata/${PROJECT_ID}`);
      const meta = metaRes.data;
      console.log(`   ✅ Metadata loaded`);
      console.log(`   📊 Classes: ${meta.classCount || 0}`);
      console.log(`   📊 Object Properties: ${meta.objectPropertyCount || 0}`);
      console.log(`   📊 Data Properties: ${meta.dataPropertyCount || 0}`);
      console.log(`   📊 Individuals: ${meta.individualCount || 0}`);
      console.log(`   📊 Annotation Properties: ${meta.annotationPropertyCount || 0}`);
      console.log(`   📊 Datatypes: ${meta.datatypeCount || 0}`);
      console.log('');
    } catch (error) {
      console.error(`   ❌ Failed to load metadata:`, error.response?.data || error.message);
      console.log('');
    }

    // 2. Check Classes
    console.log('2️⃣  CHECKING CLASSES...');
    try {
      const classesRes = await axios.get(`${BASE_URL}/api/ontology/classes/top-level/${PROJECT_ID}`);
      const classes = classesRes.data.classes || classesRes.data || [];
      console.log(`   ✅ Top-level classes: ${classes.length}`);
      if (classes.length > 0) {
        console.log(`   First 3 classes:`);
        classes.slice(0, 3).forEach(c => console.log(`      - ${c.label || c.localName} (${c.id})`));
      }
      console.log('');
    } catch (error) {
      console.error(`   ❌ Failed to load classes:`, error.response?.data || error.message);
      console.log('');
    }

    // 3. Check ALL Properties
    console.log('3️⃣  CHECKING ALL PROPERTIES...');
    try {
      const propsRes = await axios.get(`${BASE_URL}/api/ontology/properties/${PROJECT_ID}`);
      const allProps = propsRes.data.data || propsRes.data.properties || propsRes.data || [];
      console.log(`   ✅ Total properties returned: ${allProps.length}`);
      
      const objectProps = allProps.filter(p => p.type === 'ObjectProperty');
      const dataProps = allProps.filter(p => p.type === 'DatatypeProperty');
      const otherProps = allProps.filter(p => p.type !== 'ObjectProperty' && p.type !== 'DatatypeProperty');
      
      console.log(`   📊 Object Properties: ${objectProps.length}`);
      if (objectProps.length > 0) {
        console.log(`      First 3 Object Properties:`);
        objectProps.slice(0, 3).forEach(p => console.log(`         - ${p.label || p.localName} (type: ${p.type})`));
      }
      
      console.log(`   📊 Data Properties: ${dataProps.length}`);
      if (dataProps.length > 0) {
        console.log(`      First 3 Data Properties:`);
        dataProps.slice(0, 3).forEach(p => console.log(`         - ${p.label || p.localName} (type: ${p.type})`));
      }
      
      if (otherProps.length > 0) {
        console.log(`   ⚠️  Other property types found: ${otherProps.length}`);
        otherProps.forEach(p => console.log(`      - ${p.label} (type: ${p.type})`));
      }
      console.log('');
    } catch (error) {
      console.error(`   ❌ Failed to load properties:`, error.response?.data || error.message);
      console.log('');
    }

    // 4. Check Individuals
    console.log('4️⃣  CHECKING INDIVIDUALS...');
    try {
      const indsRes = await axios.get(`${BASE_URL}/api/ontology/individuals/${PROJECT_ID}`);
      const individuals = indsRes.data.data || indsRes.data.individuals || indsRes.data || [];
      console.log(`   ✅ Individuals: ${individuals.length}`);
      if (individuals.length > 0) {
        console.log(`   First 3 individuals:`);
        individuals.slice(0, 3).forEach(i => console.log(`      - ${i.label || i.localName}`));
      }
      console.log('');
    } catch (error) {
      console.error(`   ❌ Failed to load individuals:`, error.response?.data || error.message);
      console.log('');
    }

    // 5. Check Annotation Properties
    console.log('5️⃣  CHECKING ANNOTATION PROPERTIES...');
    try {
      const annPropsRes = await axios.get(`${BASE_URL}/api/ontology/annotation-properties/${PROJECT_ID}`);
      const annProps = annPropsRes.data.data || annPropsRes.data.annotationProperties || annPropsRes.data || [];
      console.log(`   ✅ Annotation Properties: ${annProps.length}`);
      if (annProps.length > 0) {
        console.log(`   First 3 annotation properties:`);
        annProps.slice(0, 3).forEach(a => console.log(`      - ${a.label || a.localName}`));
      }
      console.log('');
    } catch (error) {
      console.error(`   ❌ Failed to load annotation properties:`, error.response?.data || error.message);
      console.log('');
    }

    // 6. Check Datatypes
    console.log('6️⃣  CHECKING DATATYPES...');
    try {
      const datatypesRes = await axios.get(`${BASE_URL}/api/ontology/datatypes/${PROJECT_ID}`);
      const datatypes = datatypesRes.data.data || datatypesRes.data.datatypes || datatypesRes.data || [];
      console.log(`   ✅ Datatypes: ${datatypes.length}`);
      if (datatypes.length > 0) {
        console.log(`   First 3 datatypes:`);
        datatypes.slice(0, 3).forEach(d => console.log(`      - ${d.label || d.localName}`));
      }
      console.log('');
    } catch (error) {
      console.error(`   ❌ Failed to load datatypes:`, error.response?.data || error.message);
      console.log('');
    }

    // 7. Summary
    console.log('='.repeat(80));
    console.log(' DIAGNOSIS COMPLETE');
    console.log('='.repeat(80));
    console.log('');
    console.log('📝 NEXT STEPS:');
    console.log('');
    console.log('If all entities show 0 counts:');
    console.log('  1. Check if the ontology file was uploaded successfully');
    console.log('  2. Check GraphDB is running: http://localhost:7200');
    console.log('  3. Verify the repository "ontocode" exists in GraphDB');
    console.log('  4. Re-import your ontology file');
    console.log('');
    console.log('If metadata shows counts but endpoints return 0:');
    console.log('  1. GraphDB sync might have failed during import');
    console.log('  2. Check backend logs for SPARQL query errors');
    console.log('  3. Try clearing the cache: POST /plugin-service/api/reasoner/clear-cache');
    console.log('');
    console.log('If only some entity types are missing:');
    console.log('  1. Check if your ontology actually has those entity types defined');
    console.log('  2. Check browser console for frontend errors');
    console.log('  3. Restart the backend service');
    console.log('');

  } catch (error) {
    console.error('💥 FATAL ERROR:', error.message);
    console.error('Stack:', error.stack);
    process.exit(1);
  }
}

diagnoseEntities().catch(error => {
  console.error('Unhandled error:', error);
  process.exit(1);
});
