/**
 * =============================================================================
 * INSERT DEFAULT PLUGINS DIRECTLY INTO MONGODB
 * =============================================================================
 * 
 * This script inserts the default plugins directly into MongoDB, bypassing
 * the need for authentication. This is useful for initial setup.
 * 
 * Plugins:
 * 1. Fuzzy Ontology Plugin
 * 2. Graph View Plugin
 * 3. SWRL Editor Plugin
 */

const { MongoClient } = require('mongodb');

// Configuration - Use environment variable if available (for Docker), otherwise use local default
const MONGO_URL = process.env.MONGODB_URI || 'mongodb://localhost:27017';
const DB_NAME = process.env.MONGODB_DATABASE || 'ontology';
const PLUGINS_COLLECTION = 'plugins';

// Default plugins
const DEFAULT_PLUGINS = [
  {
    pluginId: 'fuzzy-ontology-plugin',
    name: 'Fuzzy Ontology',
    shortDescription: 'Fuzzy ontology editor with modifiers, membership functions, and visual canvas',
    description: 'Fuzzy ontology editor with 5 fuzzy modifiers (very, more_or_less, slightly, extremely, somewhat), 5 membership functions (singleton, triangular, trapezoidal, Gaussian, sigmoid), visual canvas editor with real-time curve rendering, fuzzy rules with T-norms/T-conorms, and comprehensive SPARQL integration. Features 0.01-precision degree control, effective degree preview, parameter markers, and dark theme UI.',
    latestVersion: '1.1.0',
    category: 'Reasoning',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['fuzzy', 'ontology', 'membership', 'reasoning', 'fuzzy-logic', 'owl', 'protege', 'modifiers', 'functions', 'canvas', 'visualization'],
    icon: null,
    screenshots: [],
    verified: true,
    active: true,
    featured: true,
    totalDownloads: 0,
    totalInstalls: 0,
    averageRating: 0.0,
    totalRatings: 0,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    pluginId: 'graph-view-plugin',
    name: 'Ontology Graph Visualization',
    shortDescription: 'D3.js graph visualization with hierarchical lazy loading - show root nodes first, expand on demand',
    description: 'Next-generation D3.js-powered graph visualization with hierarchical lazy loading: Shows only root nodes initially for instant load times, expand/collapse nodes on demand, smart search that shows paths to results with all children visible, visual +/− indicators, dashed borders for expandable nodes, and enterprise-grade 60 FPS performance handling 100,000+ nodes. Includes drag-and-drop, multi-select, property panel, SVG/PNG export, physics simulation, and auto-updates.',
    latestVersion: '2.0.0',
    category: 'Visualization',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['ontology', 'graph', 'visualization', 'network', 'diagram', 'owl', 'rdf', 'knowledge graph', 'reasoning', 'AI', 'ML', 'provenance', 'temporal', 'spatial', 'collaboration', 'sparql', 'cypher', 'graph-rag'],
    icon: null,
    screenshots: [],
    verified: true,
    active: true,
    featured: true,
    totalDownloads: 0,
    totalInstalls: 0,
    averageRating: 0.0,
    totalRatings: 0,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    pluginId: 'swrl-editor-plugin',
    name: 'SWRL Editor',
    shortDescription: 'Semantic Web Rule Language editor and validator',
    description: 'Semantic Web Rule Language editor and validator for ontologies. Create, edit, and execute SWRL rules with syntax validation. Includes built-in functions, rule templates, and execution engine integration.',
    latestVersion: '1.1.0', // Updated version for testing
    category: 'Editor',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['swrl', 'rules', 'reasoning', 'semantic-web', 'owl', 'ontology'],
    icon: null,
    screenshots: [],
    verified: true,
    active: true,
    featured: true,
    totalDownloads: 0,
    totalInstalls: 0,
    averageRating: 0.0,
    totalRatings: 0,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    pluginId: 'change-assistant-plugin',
    name: 'Ontology Change Assistant',
    shortDescription: 'Track and manage collaborative ontology edits with change history and conflict detection',
    description: 'Comprehensive change tracking and collaboration tool for ontology development. Features real-time change monitoring, conflict detection, approval workflows, diff visualization, team comments, version control integration, change history timeline, and rollback support. Track changes across classes, properties, individuals, axioms, annotations, and imports with detailed analytics and insights.',
    latestVersion: '1.0.0',
    category: 'Collaboration',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['change-tracking', 'collaboration', 'ontology', 'version-control', 'history', 'owl', 'diff', 'conflict-detection', 'review', 'approval'],
    icon: null,
    screenshots: [],
    verified: true,
    active: true,
    featured: true,
    totalDownloads: 0,
    totalInstalls: 0,
    averageRating: 0.0,
    totalRatings: 0,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    pluginId: 'sparql-query-plugin',
    name: 'SPARQL Query Editor',
    shortDescription: 'Full-featured SPARQL query editor with syntax highlighting and query execution',
    description: `The SPARQL Query Editor plugin provides a powerful interface for querying ontologies using the SPARQL query language.

Features:
- **Syntax Highlighting**: Full SPARQL syntax highlighting for better readability
- **Query Execution**: Execute queries directly against GraphDB
- **Results Display**: View query results in a formatted table
- **Query History**: Access previously executed queries
- **Auto-completion**: Smart auto-completion for SPARQL keywords and prefixes

Perfect for users who need to:
- Query ontologies using SPARQL
- Analyze ontology data with complex queries
- Debug and test SPARQL queries
- Export query results`,
    latestVersion: '1.0.0',
    category: 'Query',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['sparql', 'query', 'ontology', 'graphdb', 'rdf', 'owl', 'semantic-web', 'triplestore', 'database'],
    icon: null,
    screenshots: [],
    verified: true,
    active: true,
    featured: true,
    totalDownloads: 0,
    totalInstalls: 0,
    averageRating: 0.0,
    totalRatings: 0,
    createdAt: new Date(),
    updatedAt: new Date()
  }
];

/**
 * Connect to MongoDB
 */
async function connectToMongo() {
  const client = new MongoClient(MONGO_URL);
  await client.connect();
  console.log('✅ Connected to MongoDB');
  return client;
}

/**
 * Insert plugins into database
 */
async function insertPlugins(client) {
  const db = client.db(DB_NAME);
  const pluginsCollection = db.collection(PLUGINS_COLLECTION);

  console.log('\n📦 Inserting plugins into database...\n');

  for (const plugin of DEFAULT_PLUGINS) {
    try {
      // Check if plugin already exists
      const existing = await pluginsCollection.findOne({ pluginId: plugin.pluginId });
      
      if (existing) {
        console.log(`⚠️  Plugin "${plugin.name}" already exists. Updating...`);
        await pluginsCollection.updateOne(
          { pluginId: plugin.pluginId },
          { 
            $set: {
              ...plugin,
              updatedAt: new Date(),
              // Preserve existing stats
              totalDownloads: existing.totalDownloads || 0,
              totalInstalls: existing.totalInstalls || 0,
              averageRating: existing.averageRating || 0.0,
              totalRatings: existing.totalRatings || 0
            }
          }
        );
        console.log(`   ✅ Updated ${plugin.pluginId}`);
      } else {
        await pluginsCollection.insertOne(plugin);
        console.log(`✅ Inserted: ${plugin.name}`);
        console.log(`   Plugin ID: ${plugin.pluginId}`);
        console.log(`   Category: ${plugin.category}`);
      }
    } catch (error) {
      console.error(`❌ Failed to insert ${plugin.name}:`, error.message);
    }
  }
}

/**
 * Verify insertion
 */
async function verifyPlugins(client) {
  const db = client.db(DB_NAME);
  const pluginsCollection = db.collection(PLUGINS_COLLECTION);

  console.log('\n🔍 Verifying plugins in database...\n');

  const count = await pluginsCollection.countDocuments();
  console.log(`Total plugins in database: ${count}`);

  const plugins = await pluginsCollection.find({}).toArray();
  plugins.forEach(plugin => {
    console.log(`  - ${plugin.name} (${plugin.pluginId}) v${plugin.latestVersion}`);
  });
}

/**
 * Main execution
 */
async function main() {
  console.log('=============================================================================');
  console.log('INSERT DEFAULT PLUGINS INTO MONGODB');
  console.log('=============================================================================\n');

  let client;
  
  try {
    // Connect to MongoDB
    client = await connectToMongo();

    // Insert plugins
    await insertPlugins(client);

    // Verify
    await verifyPlugins(client);

    console.log('\n=============================================================================');
    console.log('✅ DEFAULT PLUGINS SUCCESSFULLY INSERTED!');
    console.log('=============================================================================');
    console.log('\n🎉 Plugins are now available in the marketplace!');
    console.log('   Open the VS Code extension and check the Plugin Marketplace\n');

  } catch (error) {
    console.error('\n❌ Error:', error.message);
    
    if (error.message.includes('ECONNREFUSED')) {
      console.error('\n⚠️  MongoDB is not running!');
      console.error('   Please start MongoDB first');
    }
    
    process.exit(1);
  } finally {
    if (client) {
      await client.close();
      console.log('Disconnected from MongoDB');
    }
  }
}

// Run the script
main().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
