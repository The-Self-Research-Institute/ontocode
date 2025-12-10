/**
 * ============================================================================
 * HIERARCHY DEBUG SCRIPT
 * ============================================================================
 * 
 * This script traces the exact hierarchy logic used by the graph view plugin
 * to identify why nodes like "Circulatory" are not appearing in the graph.
 * 
 * Usage: node scripts/debug-hierarchy.js
 */

const http = require('http');

// ============================================================================
// HIERARCHY LOGIC (copied from HierarchicalLazyLoading.ts)
// ============================================================================

function getRootNodes(nodes, edges) {
  const childIds = new Set(
    edges
      .filter(e => e.type === 'subClassOf' || e.type === 'instanceOf')
      .map(e => e.from)
  );

  const rootIds = nodes
    .filter(node => !childIds.has(node.id))
    .map(node => node.id);

  return rootIds;
}

function getChildren(nodeId, edges) {
  const children = edges
    .filter(edge => edge.to === nodeId && edge.type === 'subClassOf')
    .map(edge => edge.from);
  
  return children;
}

function getParents(nodeId, edges) {
  return edges
    .filter(edge => edge.from === nodeId && edge.type === 'subClassOf')
    .map(edge => edge.to);
}

function toggleNodeExpansion(nodeId, expandedNodeIds, visibleNodeIds, edges, nodes) {
  if (expandedNodeIds.has(nodeId)) {
    // COLLAPSE logic (not needed for this test)
    return { newExpandedIds: expandedNodeIds, newVisibleIds: visibleNodeIds, action: 'collapsed' };
  } else {
    // EXPAND: Add immediate children only
    const children = getChildren(nodeId, edges);
    const newVisibleIds = new Set([...visibleNodeIds, ...children]);
    const newExpandedIds = new Set([...expandedNodeIds, nodeId]);

    return {
      newExpandedIds,
      newVisibleIds,
      action: 'expanded'
    };
  }
}

// ============================================================================
// MAIN DEBUG FUNCTION
// ============================================================================

async function debugHierarchy() {
  console.log('\n' + '='.repeat(80));
  console.log('HIERARCHY DEBUG - Tracing Graph View Logic');
  console.log('='.repeat(80) + '\n');

  try {
    // Step 1: Fetch graph data from API
    console.log('📡 [Step 1] Fetching graph data from API...');
    
    const data = await new Promise((resolve, reject) => {
      http.get('http://localhost:8083/api/ontology/test/graph', (res) => {
        let body = '';
        res.on('data', chunk => body += chunk);
        res.on('end', () => resolve(JSON.parse(body)));
      }).on('error', reject);
    });

    const allNodes = data.nodes || [];
    const allEdges = data.edges || [];

    console.log(`✅ Loaded ${allNodes.length} nodes and ${allEdges.length} edges\n`);

    // Step 2: Find focus nodes
    console.log('🔍 [Step 2] Finding focus nodes...');
    const measurement = allNodes.find(n => n.label === 'Measurement');
    const circulatory = allNodes.find(n => n.label === 'Circulatory');
    const cardiovascular = allNodes.find(n => n.label === 'Cardiovascular');

    if (!measurement) {
      console.error('❌ ERROR: Measurement node not found!');
      return;
    }
    if (!circulatory) {
      console.error('❌ ERROR: Circulatory node not found!');
      return;
    }
    if (!cardiovascular) {
      console.error('❌ ERROR: Cardiovascular node not found!');
      return;
    }

    console.log(`✅ Found focus nodes:`);
    console.log(`   - Measurement: ${measurement.id}`);
    console.log(`   - Circulatory: ${circulatory.id}`);
    console.log(`   - Cardiovascular: ${cardiovascular.id}\n`);

    // Step 3: Find edges connecting these nodes
    console.log('🔗 [Step 3] Finding edges connecting focus nodes...');
    const focusIds = new Set([measurement.id, circulatory.id, cardiovascular.id]);
    const focusEdges = allEdges.filter(e => 
      focusIds.has(e.from) || focusIds.has(e.to)
    );

    console.log(`Found ${focusEdges.length} edges:`);
    focusEdges.forEach(edge => {
      const fromNode = allNodes.find(n => n.id === edge.from);
      const toNode = allNodes.find(n => n.id === edge.to);
      console.log(`   ${fromNode?.label || edge.from} --[${edge.type}]--> ${toNode?.label || edge.to}`);
    });
    console.log();

    // Step 4: Identify root nodes
    console.log('🌳 [Step 4] Identifying root nodes (UI initial state)...');
    const rootIds = getRootNodes(allNodes, allEdges);
    const rootLabels = rootIds.map(id => allNodes.find(n => n.id === id)?.label || id);
    
    console.log(`Found ${rootIds.length} root nodes: ${rootLabels.join(', ')}`);
    console.log(`Is Measurement a root? ${rootIds.includes(measurement.id) ? '✅ YES' : '❌ NO'}`);
    console.log(`Is Circulatory a root? ${rootIds.includes(circulatory.id) ? '✅ YES' : '❌ NO'}\n`);

    // Step 5: Check parent-child relationships
    console.log('👨‍👧‍👦 [Step 5] Analyzing parent-child relationships...');
    
    const measurementChildren = getChildren(measurement.id, allEdges);
    const measurementParents = getParents(measurement.id, allEdges);
    const circulatoryChildren = getChildren(circulatory.id, allEdges);
    const circulatoryParents = getParents(circulatory.id, allEdges);
    const cardiovascularParents = getParents(cardiovascular.id, allEdges);
    
    console.log(`\nMeasurement:`);
    console.log(`   Parents: ${measurementParents.map(id => allNodes.find(n => n.id === id)?.label || id).join(', ') || 'NONE (ROOT)'}`);
    console.log(`   Children: ${measurementChildren.map(id => allNodes.find(n => n.id === id)?.label || id).join(', ') || 'NONE'}`);
    
    console.log(`\nCirculatory:`);
    console.log(`   Parents: ${circulatoryParents.map(id => allNodes.find(n => n.id === id)?.label || id).join(', ') || 'NONE (ROOT)'}`);
    console.log(`   Children: ${circulatoryChildren.map(id => allNodes.find(n => n.id === id)?.label || id).join(', ') || 'NONE'}`);
    
    console.log(`\nCardiovascular:`);
    console.log(`   Parents: ${cardiovascularParents.map(id => allNodes.find(n => n.id === id)?.label || id).join(', ') || 'NONE (ROOT)'}`);
    console.log();

    // Step 6: Simulate UI expansion
    console.log('🎬 [Step 6] Simulating UI expansion flow...\n');
    
    // Initial state: only roots visible
    let visibleNodeIds = new Set(rootIds);
    let expandedNodeIds = new Set();
    
    console.log('Initial state (roots only):');
    console.log(`   Visible: ${Array.from(visibleNodeIds).map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
    console.log(`   Expanded: ${Array.from(expandedNodeIds).map(id => allNodes.find(n => n.id === id)?.label).join(', ') || 'NONE'}`);
    console.log(`   Circulatory visible? ${visibleNodeIds.has(circulatory.id) ? '✅ YES' : '❌ NO'}\n`);

    // User clicks Measurement to expand
    console.log('User action: Click to expand "Measurement"');
    const result1 = toggleNodeExpansion(measurement.id, expandedNodeIds, visibleNodeIds, allEdges, allNodes);
    visibleNodeIds = result1.newVisibleIds;
    expandedNodeIds = result1.newExpandedIds;
    
    console.log(`After expanding Measurement:`);
    console.log(`   Visible (${visibleNodeIds.size}): ${Array.from(visibleNodeIds).map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
    console.log(`   Expanded: ${Array.from(expandedNodeIds).map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
    console.log(`   Circulatory visible? ${visibleNodeIds.has(circulatory.id) ? '✅ YES' : '❌ NO'}\n`);

    // If Circulatory is visible, try expanding it
    if (visibleNodeIds.has(circulatory.id)) {
      console.log('User action: Click to expand "Circulatory"');
      const result2 = toggleNodeExpansion(circulatory.id, expandedNodeIds, visibleNodeIds, allEdges, allNodes);
      visibleNodeIds = result2.newVisibleIds;
      expandedNodeIds = result2.newExpandedIds;
      
      console.log(`After expanding Circulatory:`);
      console.log(`   Visible (${visibleNodeIds.size}): ${Array.from(visibleNodeIds).map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
      console.log(`   Cardiovascular visible? ${visibleNodeIds.has(cardiovascular.id) ? '✅ YES' : '❌ NO'}\n`);
    } else {
      console.log('⚠️  Cannot expand Circulatory - it is not visible!\n');
    }

    // Step 7: Check if issue is in the data or logic
    console.log('🔬 [Step 7] Root cause analysis...\n');
    
    if (!rootIds.includes(measurement.id)) {
      console.log('❌ ISSUE: Measurement is not detected as a root node!');
      console.log(`   This means Measurement has a parent in the edges.`);
      if (measurementParents.length > 0) {
        console.log(`   Parents found: ${measurementParents.map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
      }
    } else {
      console.log('✅ Measurement is correctly identified as a root node.');
    }

    if (measurementChildren.length === 0) {
      console.log('❌ ISSUE: Measurement has no children in the edges!');
      console.log(`   This means no edges exist with Measurement as 'to' (parent).`);
    } else if (!measurementChildren.includes(circulatory.id)) {
      console.log('❌ ISSUE: Circulatory is NOT a child of Measurement!');
      console.log(`   Measurement's children: ${measurementChildren.map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
      console.log(`   Expected: Circulatory should be in this list.`);
    } else {
      console.log('✅ Circulatory is correctly identified as a child of Measurement.');
    }

    if (circulatoryParents.length === 0) {
      console.log('⚠️  WARNING: Circulatory has no parents (it\'s a root).');
    } else if (!circulatoryParents.includes(measurement.id)) {
      console.log('❌ ISSUE: Measurement is NOT a parent of Circulatory!');
      console.log(`   Circulatory's parents: ${circulatoryParents.map(id => allNodes.find(n => n.id === id)?.label).join(', ')}`);
      console.log(`   Expected: Measurement should be in this list.`);
    } else {
      console.log('✅ Measurement is correctly identified as a parent of Circulatory.');
    }

    console.log('\n' + '='.repeat(80));
    console.log('Debug Complete');
    console.log('='.repeat(80) + '\n');

  } catch (error) {
    console.error('❌ Error:', error.message);
    console.error(error.stack);
  }
}

// Run the debug function
debugHierarchy();
