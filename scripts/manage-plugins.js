#!/usr/bin/env node
/**
 * =============================================================================
 * PLUGIN MANAGER - Unified Script for Managing OntoCode Plugins
 * =============================================================================
 * 
 * This single script handles all plugin management tasks:
 * - Build plugins
 * - Insert plugin metadata into MongoDB
 * - Upload bundles to GridFS
 * - Create version records
 * 
 * Usage:
 *   node manage-plugins.js [command] [options]
 * 
 * Commands:
 *   all       - Build, insert metadata, and upload bundles (default)
 *   build     - Build all plugins
 *   install   - Insert metadata and upload bundles (no build)
 *   list      - List installed plugins
 *   clean     - Remove all plugins from database
 * 
 * Options:
 *   --plugin <id>   - Process only specified plugin
 *   --force         - Force overwrite existing data
 *   --skip-build    - Skip building plugins
 * 
 * Examples:
 *   node manage-plugins.js
 *   node manage-plugins.js all
 *   node manage-plugins.js build
 *   node manage-plugins.js install --plugin fuzzy-ontology-plugin
 *   node manage-plugins.js list
 *   node manage-plugins.js clean
 */

const { MongoClient, GridFSBucket } = require('mongodb');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// Load environment variables from root .env when available
try {
  const dotenvPath = path.resolve(__dirname, '..', '.env');
  if (fs.existsSync(dotenvPath)) {
    require('dotenv').config({ path: dotenvPath });
  } else {
    require('dotenv').config();
  }
} catch (error) {
  console.warn('⚠ Could not load .env file:', error.message);
}

// =============================================================================
// CONFIGURATION
// =============================================================================

const MONGO_URL = process.env.MONGODB_URI || process.env.MONGO_URL || 'mongodb://localhost:27017';
const MONGO_USERNAME = process.env.MONGODB_USERNAME || process.env.MONGO_USERNAME || process.env.MONGO_USER || "admin";
const MONGO_PASSWORD = process.env.MONGODB_PASSWORD || process.env.MONGO_PASSWORD || process.env.MONGO_PASS || "changeme123";
const MONGO_AUTH_SOURCE = process.env.MONGODB_AUTH_SOURCE || process.env.MONGO_AUTH_SOURCE || 'admin';
const DB_NAME = process.env.MONGODB_DATABASE || process.env.MONGO_DB_NAME || 'ontology';
// Use /app/plugins in Docker, otherwise use local path
const PLUGINS_DIR = fs.existsSync('/app/plugins') ? '/app/plugins' : path.resolve(__dirname, '..', 'plugins');

// =============================================================================
// PLUGIN DEFINITIONS - Single source of truth
// =============================================================================

const PLUGINS = [
  {
    pluginId: 'fuzzy-ontology-plugin',
    name: 'Fuzzy Ontology',
    shortDescription: 'Fuzzy ontology editor with modifiers, membership functions, and visual canvas',
    description: 'Fuzzy ontology editor with 5 fuzzy modifiers (very, more_or_less, slightly, extremely, somewhat), 5 membership functions (singleton, triangular, trapezoidal, Gaussian, sigmoid), visual canvas editor with real-time curve rendering, fuzzy rules with T-norms/T-conorms, and comprehensive SPARQL integration.',
    version: '1.1.0',
    category: 'Reasoning',
    keywords: ['fuzzy', 'ontology', 'membership', 'reasoning', 'fuzzy-logic', 'owl', 'modifiers', 'canvas', 'visualization'],
    featured: true
  },
  {
    pluginId: 'graph-view-plugin',
    name: 'Ontology Graph Visualization',
    shortDescription: 'D3.js graph visualization with hierarchical lazy loading',
    description: 'D3.js-powered graph visualization with hierarchical lazy loading: Shows only root nodes initially, expand/collapse on demand, smart search, drag-and-drop, multi-select, property panel, SVG/PNG export, and physics simulation.',
    version: '2.0.0',
    category: 'Visualization',
    keywords: ['ontology', 'graph', 'visualization', 'network', 'diagram', 'd3', 'knowledge-graph'],
    featured: true
  },
  {
    pluginId: 'swrl-editor-plugin',
    name: 'SWRL Rule Editor',
    shortDescription: 'Create and execute Semantic Web Rule Language rules',
    description: 'SWRL editor with syntax validation, rule templates, built-in functions reference, batch execution, SQWRL queries, and inference results visualization.',
    version: '1.2.0',
    category: 'Reasoning',
    keywords: ['swrl', 'rules', 'reasoning', 'semantic-web', 'owl', 'inference'],
    featured: true
  },
  {
    pluginId: 'change-assistant-plugin',
    name: 'Ontology Change Assistant',
    shortDescription: 'Track and manage collaborative ontology edits',
    description: 'Change tracking and collaboration tool with real-time monitoring, conflict detection, approval workflows, diff visualization, and version control integration.',
    version: '1.0.0',
    category: 'Collaboration',
    keywords: ['change-tracking', 'collaboration', 'version-control', 'history', 'diff', 'conflict-detection'],
    featured: true
  },
  {
    pluginId: 'sparql-query-plugin',
    name: 'SPARQL Query Editor',
    shortDescription: 'Execute SPARQL queries with syntax highlighting',
    description: 'Full-featured SPARQL editor with syntax highlighting, query execution against GraphDB, formatted results display, query history, and namespace management.',
    version: '1.0.0',
    category: 'Query',
    keywords: ['sparql', 'query', 'graphdb', 'rdf', 'triplestore', 'database'],
    featured: true
  },
  {
    pluginId: 'reasoner-plugin',
    name: 'OWL Reasoner',
    shortDescription: 'Advanced OWL 2 reasoning with HermiT, ELK, and Pellet',
    description: 'Advanced OWL 2 reasoning plugin providing Protégé-style interface. Supports HermiT, ELK, Pellet, and Openllet reasoners. Features include consistency checking, classification, inferred hierarchy visualization, unsatisfiable class detection with explanations, and real-time synchronization with the ontology editor.',
    version: '1.0.0',
    category: 'Reasoning',
    keywords: ['reasoner', 'owl', 'hermit', 'elk', 'pellet', 'inference', 'consistency', 'classification'],
    featured: true
  }
];

// =============================================================================
// UTILITIES
// =============================================================================

const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m'
};

const log = {
  info: (msg) => console.log(`${colors.blue}ℹ${colors.reset} ${msg}`),
  success: (msg) => console.log(`${colors.green}✓${colors.reset} ${msg}`),
  warn: (msg) => console.log(`${colors.yellow}⚠${colors.reset} ${msg}`),
  error: (msg) => console.log(`${colors.red}✗${colors.reset} ${msg}`),
  header: (msg) => console.log(`\n${colors.bright}${colors.cyan}${msg}${colors.reset}\n${'─'.repeat(60)}`)
};

function parseArgs() {
  const args = process.argv.slice(2);
  const result = {
    command: 'all',
    plugin: null,
    force: false,
    skipBuild: false
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--plugin' && args[i + 1]) {
      result.plugin = args[++i];
    } else if (arg === '--force') {
      result.force = true;
    } else if (arg === '--skip-build') {
      result.skipBuild = true;
    } else if (!arg.startsWith('-')) {
      result.command = arg;
    }
  }

  return result;
}

// =============================================================================
// BUILD PLUGINS
// =============================================================================

async function buildPlugin(pluginId) {
  const pluginDir = path.join(PLUGINS_DIR, pluginId);
  
  if (!fs.existsSync(pluginDir)) {
    log.error(`Plugin directory not found: ${pluginDir}`);
    return false;
  }

  const packageJson = path.join(pluginDir, 'package.json');
  if (!fs.existsSync(packageJson)) {
    log.error(`package.json not found for ${pluginId}`);
    return false;
  }

  try {
    log.info(`Building ${pluginId}...`);
    
    // Install dependencies if needed
    const nodeModules = path.join(pluginDir, 'node_modules');
    if (!fs.existsSync(nodeModules)) {
      log.info(`  Installing dependencies...`);
      execSync('npm install', { cwd: pluginDir, stdio: 'pipe' });
    }

    // Build
    execSync('npm run build', { cwd: pluginDir, stdio: 'pipe' });
    
    const bundlePath = path.join(pluginDir, 'dist', 'index.js');
    if (fs.existsSync(bundlePath)) {
      const stats = fs.statSync(bundlePath);
      const sizeKB = (stats.size / 1024).toFixed(1);
      log.success(`  Built ${pluginId} (${sizeKB} KB)`);
      return true;
    } else {
      log.error(`  Bundle not created for ${pluginId}`);
      return false;
    }
  } catch (error) {
    log.error(`  Build failed: ${error.message}`);
    return false;
  }
}

async function buildAllPlugins(plugins) {
  log.header('Building Plugins');
  
  let success = 0;
  let failed = 0;

  for (const plugin of plugins) {
    if (await buildPlugin(plugin.pluginId)) {
      success++;
    } else {
      failed++;
    }
  }

  console.log('');
  log.info(`Build complete: ${success} success, ${failed} failed`);
  return failed === 0;
}

// =============================================================================
// DATABASE OPERATIONS
// =============================================================================

async function connectToMongo() {
  const options = {};

  if (MONGO_USERNAME && MONGO_PASSWORD) {
    options.auth = {
      username: MONGO_USERNAME,
      password: MONGO_PASSWORD
    };
    options.authSource = MONGO_AUTH_SOURCE;
  }

  const client = new MongoClient(MONGO_URL, options);
  await client.connect();

  if (MONGO_USERNAME) {
    log.info(`Authenticated as ${MONGO_USERNAME} (authSource: ${options.authSource})`);
  } else {
    log.warn('Proceeding without MongoDB credentials');
  }

  return client;
}

async function insertPluginMetadata(db, plugin, force = false) {
  const collection = db.collection('plugins');
  
  const existing = await collection.findOne({ pluginId: plugin.pluginId });
  
  const doc = {
    pluginId: plugin.pluginId,
    name: plugin.name,
    shortDescription: plugin.shortDescription,
    description: plugin.description,
    latestVersion: plugin.version,
    category: plugin.category,
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: plugin.keywords,
    icon: null,
    screenshots: [],
    verified: true,
    active: true,
    featured: plugin.featured || false,
    totalDownloads: existing?.totalDownloads || 0,
    totalInstalls: existing?.totalInstalls || 0,
    averageRating: existing?.averageRating || 0.0,
    totalRatings: existing?.totalRatings || 0,
    updatedAt: new Date()
  };

  if (existing) {
    if (!force) {
      log.info(`  Updating metadata for ${plugin.pluginId}`);
    }
    await collection.updateOne(
      { pluginId: plugin.pluginId },
      { $set: doc }
    );
  } else {
    doc.createdAt = new Date();
    await collection.insertOne(doc);
    log.info(`  Inserted metadata for ${plugin.pluginId}`);
  }

  return true;
}

async function uploadBundleToGridFS(db, bucket, plugin) {
  log.warn(`\n[BUNDLE PATH DEBUG] PLUGINS_DIR = "${PLUGINS_DIR}"`);
  log.warn(`[BUNDLE PATH DEBUG] plugin.pluginId = "${plugin.pluginId}"`);
  const bundlePath = path.join(PLUGINS_DIR, plugin.pluginId, 'dist', 'index.js');
  log.warn(`[BUNDLE PATH DEBUG] bundlePath = "${bundlePath}"`);
  log.warn(`[BUNDLE PATH DEBUG] exists = ${fs.existsSync(bundlePath)}`);
  
  // Also check alternate paths
  const altPath1 = path.join('/app/plugins', plugin.pluginId, 'dist', 'index.js');
  const altPath2 = path.join('plugins', plugin.pluginId, 'dist', 'index.js');
  log.warn(`[BUNDLE PATH DEBUG] altPath1 (/app/plugins) = "${altPath1}" exists = ${fs.existsSync(altPath1)}`);
  log.warn(`[BUNDLE PATH DEBUG] altPath2 (plugins) = "${altPath2}" exists = ${fs.existsSync(altPath2)}\n`);
  
  if (!fs.existsSync(bundlePath)) {
    log.error(`  Bundle not found: ${bundlePath}`);
    return null;
  }

  const fileName = `${plugin.pluginId}-${plugin.version}.js`;

  // Remove existing file if present
  const existingFiles = await bucket.find({ filename: fileName }).toArray();
  for (const file of existingFiles) {
    await bucket.delete(file._id);
  }

  // Upload new file
  return new Promise((resolve, reject) => {
    const uploadStream = bucket.openUploadStream(fileName, {
      contentType: 'application/javascript',
      metadata: {
        pluginId: plugin.pluginId,
        version: plugin.version,
        uploadedAt: new Date()
      }
    });

    const readStream = fs.createReadStream(bundlePath);
    readStream.pipe(uploadStream)
      .on('error', reject)
      .on('finish', () => {
        log.success(`  Uploaded bundle: ${fileName}`);
        resolve(uploadStream.id);
      });
  });
}

async function createVersionRecord(db, plugin, fileId, bundlePath) {
  const collection = db.collection('plugin_versions');
  const stats = fs.statSync(bundlePath);

  const existing = await collection.findOne({
    pluginId: plugin.pluginId,
    version: plugin.version
  });

  const doc = {
    pluginId: plugin.pluginId,
    version: plugin.version,
    vsixFileId: fileId.toString(),  // Spring expects vsixFileId as string
    bundleSize: stats.size,
    changelog: `Version ${plugin.version}`,
    minExtensionVersion: '1.0.0',
    downloadCount: existing?.downloadCount || 0,
    downloads: existing?.downloads || 0,
    active: true,
    releaseDate: new Date(),
    publishedAt: new Date(),
    updatedAt: new Date()
  };

  if (existing) {
    await collection.updateOne(
      { pluginId: plugin.pluginId, version: plugin.version },
      { $set: doc }
    );
  } else {
    doc.createdAt = new Date();
    await collection.insertOne(doc);
  }

  return true;
}

async function installPlugin(db, bucket, plugin, force = false) {
  log.info(`Installing ${plugin.pluginId} v${plugin.version}...`);

  try {
    // 1. Insert/update metadata
    await insertPluginMetadata(db, plugin, force);

    // 2. Upload bundle to GridFS
    const fileId = await uploadBundleToGridFS(db, bucket, plugin);
    if (!fileId) {
      return false;
    }

    // 3. Create version record
    const bundlePath = path.join(PLUGINS_DIR, plugin.pluginId, 'dist', 'index.js');
    await createVersionRecord(db, plugin, fileId, bundlePath);

    return true;
  } catch (error) {
    log.error(`Fatal error: ${error.message}`);
    if (typeof error.message === 'string' && error.message.toLowerCase().includes('requires authentication')) {
      log.error('MongoDB rejected the request. Provide MONGO_USERNAME/MONGO_PASSWORD (or MONGODB_USERNAME/MONGODB_PASSWORD).');
      log.error('Optional: MONGO_AUTH_SOURCE (default "admin"), MONGO_DB_NAME, MONGO_URL');
    }
    throw error;
  }
}

async function listPlugins(db) {
  log.header('Installed Plugins');

  const plugins = await db.collection('plugins').find({}).toArray();
  
  if (plugins.length === 0) {
    log.info('No plugins installed');
    return;
  }

  console.log('');
  for (const p of plugins) {
    const status = p.active ? `${colors.green}●${colors.reset}` : `${colors.red}○${colors.reset}`;
    console.log(`  ${status} ${colors.bright}${p.name}${colors.reset} (${p.pluginId})`);
    console.log(`    Version: ${p.latestVersion} | Category: ${p.category}`);
    console.log(`    ${p.shortDescription}`);
    console.log('');
  }

  log.info(`Total: ${plugins.length} plugins`);
}

async function cleanPlugins(db) {
  log.header('Cleaning Plugins');

  const pluginsResult = await db.collection('plugins').deleteMany({});
  const versionsResult = await db.collection('plugin_versions').deleteMany({});
  
  // Clean GridFS
  const bucket = new GridFSBucket(db, { bucketName: 'plugins' });
  const files = await bucket.find({}).toArray();
  for (const file of files) {
    await bucket.delete(file._id);
  }

  log.success(`Deleted ${pluginsResult.deletedCount} plugins`);
  log.success(`Deleted ${versionsResult.deletedCount} versions`);
  log.success(`Deleted ${files.length} bundle files from GridFS`);
}

// =============================================================================
// MAIN
// =============================================================================

async function main() {
  const args = parseArgs();
  
  // Debug logging FIRST
  console.log('=====================================');
  console.log('[DEBUG] __dirname =', __dirname);
  console.log('[DEBUG] PLUGINS_DIR =', PLUGINS_DIR);
  console.log('[DEBUG] PLUGINS_DIR exists =', fs.existsSync(PLUGINS_DIR));
  if (fs.existsSync(PLUGINS_DIR)) {
    console.log('[DEBUG] PLUGINS_DIR contents =', fs.readdirSync(PLUGINS_DIR).join(', '));
    // Test specific plugin path
    const testPath = path.join(PLUGINS_DIR, 'sparql-query-plugin', 'dist', 'index.js');
    console.log('[DEBUG] Test path =', testPath);
    console.log('[DEBUG] Test path exists =', fs.existsSync(testPath));
  }
  console.log('=====================================');
  
  console.log(`
${colors.bright}${colors.magenta}╔═══════════════════════════════════════════════════════════╗
║           OntoCode Plugin Manager                         ║
╚═══════════════════════════════════════════════════════════╝${colors.reset}
`);

  // Filter plugins if specified
  let plugins = PLUGINS;
  if (args.plugin) {
    plugins = PLUGINS.filter(p => p.pluginId === args.plugin);
    if (plugins.length === 0) {
      log.error(`Plugin not found: ${args.plugin}`);
      log.info(`Available plugins: ${PLUGINS.map(p => p.pluginId).join(', ')}`);
      process.exit(1);
    }
  }

  // Connect to MongoDB (except for build-only command)
  let client = null;
  let db = null;
  let bucket = null;

  if (args.command !== 'build') {
    try {
      log.info(`Connecting to MongoDB at ${MONGO_URL}...`);
      client = await connectToMongo();
      db = client.db(DB_NAME);
      bucket = new GridFSBucket(db, { bucketName: 'plugins' });
      log.success('Connected to MongoDB');
    } catch (error) {
      log.error(`Failed to connect to MongoDB: ${error.message}`);
      if (error.message && error.message.toLowerCase().includes('authentication')) {
        log.error('Authentication required. Set MONGO_USERNAME and MONGO_PASSWORD (or MONGODB_USERNAME / MONGODB_PASSWORD).');
        log.error('Optional: MONGO_AUTH_SOURCE (defaults to "admin"), MONGO_DB_NAME, MONGO_URL');
      }
      process.exit(1);
    }
  }

  try {
    switch (args.command) {
      case 'all':
        // Build + Install
        if (!args.skipBuild) {
          await buildAllPlugins(plugins);
        }
        log.header('Installing Plugins');
        for (const plugin of plugins) {
          await installPlugin(db, bucket, plugin, args.force);
        }
        log.header('Complete');
        log.success(`Successfully processed ${plugins.length} plugin(s)`);
        break;

      case 'build':
        await buildAllPlugins(plugins);
        break;

      case 'install':
        log.header('Installing Plugins');
        for (const plugin of plugins) {
          await installPlugin(db, bucket, plugin, args.force);
        }
        log.success(`Installed ${plugins.length} plugin(s)`);
        break;

      case 'list':
        await listPlugins(db);
        break;

      case 'clean':
        await cleanPlugins(db);
        break;

      default:
        log.error(`Unknown command: ${args.command}`);
        console.log(`
Usage: node manage-plugins.js [command] [options]

Commands:
  all       Build, insert metadata, and upload bundles (default)
  build     Build all plugins
  install   Insert metadata and upload bundles (no build)
  list      List installed plugins
  clean     Remove all plugins from database

Options:
  --plugin <id>   Process only specified plugin
  --force         Force overwrite existing data
  --skip-build    Skip building plugins
`);
        process.exit(1);
    }
  } finally {
    if (client) {
      await client.close();
    }
  }
}

main().catch(error => {
  log.error(`Fatal error: ${error.message}`);
  console.error(error);
  process.exit(1);
});
