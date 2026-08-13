const axios = require('axios');

const PROJECT_ID = process.argv[2];
const BASE_URL = 'http://localhost:8081';

if (!PROJECT_ID) {
  console.error('Usage: node check-object-properties.js <projectId>');
  process.exit(1);
}

async function checkObjectProperties() {
  try {
    console.log('============================================');
    console.log('Checking Object Properties for:', PROJECT_ID);
    console.log('============================================\n');

    console.log('1. Fetching ASSERTED object properties...');
    const assertedResponse = await axios.get(
      `${BASE_URL}/api/ontology/${PROJECT_ID}/object-properties`
    );
    const assertedProps = assertedResponse.data.objectProperties || [];
    console.log(`   Found ${assertedProps.length} asserted object properties`);
    assertedProps.forEach(prop => {
      console.log(`   - ${prop.label || prop.iri}`);
    });

    console.log('\n2. Fetching INFERRED object property hierarchy...');
    try {
      const inferredResponse = await axios.get(
        `${BASE_URL}/api/ontology/${PROJECT_ID}/reasoner/inferred-object-property-hierarchy?reasonerType=HERMIT`
      );
      const hierarchy = inferredResponse.data.hierarchy || [];
      console.log(`   Response success: ${inferredResponse.data.success}`);
      console.log(`   Reasoner type: ${inferredResponse.data.reasonerType}`);
      console.log(`   Hierarchy nodes: ${hierarchy.length}`);

      if (hierarchy.length > 0) {
        const root = hierarchy[0];
        console.log(`   Root node: ${root.label || root.id}`);
        console.log(`   Children count: ${root.children ? root.children.length : 0}`);

        if (root.children && root.children.length > 0) {
          console.log('   Properties in hierarchy:');
          root.children.forEach(child => {
            console.log(`   - ${child.label || child.id}`);
          });
        } else {
          console.log('   ⚠️  No children found in root node');
        }
      } else {
        console.log('   ⚠️  No hierarchy returned');
      }
    } catch (error) {
      console.error('   ❌ Error fetching inferred hierarchy:', error.response?.data || error.message);
    }

    console.log('\n3. Checking reasoner status...');
    try {
      const statsResponse = await axios.get(
        `${BASE_URL}/api/ontology/${PROJECT_ID}/reasoner/stats?reasonerType=HERMIT`
      );
      console.log('   Reasoner stats:', JSON.stringify(statsResponse.data, null, 2));
    } catch (error) {
      console.log('   Could not fetch stats (reasoner may not be initialized)');
    }

    console.log('\n============================================');
    console.log('Diagnosis Complete');
    console.log('============================================');

  } catch (error) {
    console.error('Error:', error.response?.data || error.message);
    process.exit(1);
  }
}

checkObjectProperties();
