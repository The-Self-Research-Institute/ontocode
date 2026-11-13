#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const { newPool, waitForSchema, newObjectId, blobsRoot, storeFile, deleteStoredFile } = require('./lib/db');

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

const BLOBS_DIR = blobsRoot();
const PLUGINS_DIR = fs.existsSync('/app/plugins') ? '/app/plugins' : path.resolve(__dirname, '..', 'plugins');

const PLUGINS = [
  {
    pluginId: 'fuzzy-ontology-plugin',
    name: 'Fuzzy Ontology',
    shortDescription: 'Fuzzy ontology editor with modifiers, membership functions, and visual canvas',
    description: 'Fuzzy ontology editor with 5 fuzzy modifiers (extremely, very, slightly, more_or_less, somewhat), 5 membership functions (singleton, triangular, trapezoidal, Gaussian, sigmoid), visual canvas editor with real-time curve rendering, fuzzy rules with T-norms/T-conorms, and comprehensive SPARQL integration.',
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
    shortDescription: 'Advanced OWL 2 reasoning with HermiT, ELK, and JFact',
    description: 'Advanced OWL 2 reasoning plugin providing Protégé-style interface. Supports HermiT, ELK, JFact, and structural reasoners. Features include consistency checking, classification, inferred hierarchy visualization, unsatisfiable class detection with explanations, and real-time synchronization with the ontology editor.',
    version: '1.0.0',
    category: 'Reasoning',
    keywords: ['reasoner', 'owl', 'hermit', 'elk', 'jfact', 'inference', 'consistency', 'classification'],
    featured: true
  },
  {
    pluginId: 'spreadsheet-import-plugin',
    name: 'Spreadsheet Import',
    shortDescription: 'Import spreadsheet rows into the ontology with Cellfie-style rules',
    description: 'Turn xlsx, xls and csv rows into classes, individuals and property values with transformation rules, preview the axioms, and import them as one undoable change.',
    version: '1.0.0',
    category: 'Import/Export',
    keywords: ['spreadsheet', 'excel', 'csv', 'import', 'cellfie'],
    featured: true
  }
];

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

    const nodeModules = path.join(pluginDir, 'node_modules');
    if (!fs.existsSync(nodeModules)) {
      log.info(`  Installing dependencies...`);
      execSync('npm install', { cwd: pluginDir, stdio: 'pipe' });
    }

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

async function insertPluginMetadata(pool, plugin) {
  const existing = await pool.query('select id from plugin where plugin_id = $1', [plugin.pluginId]);
  if (existing.rowCount > 0) {
    await pool.query(
      'update plugin set name = $2, description = $3, latest_version = $4, category = $5, author = $6, author_email = $7, ' +
      'keywords = $8, verified = true, deprecated = false, updated_at = now() where plugin_id = $1',
      [plugin.pluginId, plugin.name, plugin.description, plugin.version, plugin.category, 'OntoCode Team', 'admin@ontocode.com', plugin.keywords]
    );
    log.info(`  Updated metadata for ${plugin.pluginId}`);
    return;
  }
  await pool.query(
    'insert into plugin(id, plugin_id, name, description, author, author_email, latest_version, category, keywords, screenshots, ' +
    'total_downloads, average_rating, review_count, verified, deprecated, created_at, updated_at) ' +
    "values ($1, $2, $3, $4, $5, $6, $7, $8, $9, '{}', 0, 0, 0, true, false, now(), now())",
    [newObjectId(), plugin.pluginId, plugin.name, plugin.description, 'OntoCode Team', 'admin@ontocode.com', plugin.version, plugin.category, plugin.keywords]
  );
  log.info(`  Inserted metadata for ${plugin.pluginId}`);
}

async function uploadBundle(pool, plugin) {
  const bundlePath = path.join(PLUGINS_DIR, plugin.pluginId, 'dist', 'index.js');
  if (!fs.existsSync(bundlePath)) {
    log.error(`  Bundle not found: ${bundlePath}`);
    return null;
  }

  const fileName = `${plugin.pluginId}-${plugin.version}.js`;
  const old = await pool.query("select id from stored_file where bucket = 'plugins' and filename = $1", [fileName]);
  for (const row of old.rows) {
    await deleteStoredFile(pool, BLOBS_DIR, row.id);
  }

  const stored = await storeFile(pool, BLOBS_DIR, {
    bucket: 'plugins',
    filename: fileName,
    contentType: 'application/javascript',
    metadata: {
      pluginId: plugin.pluginId,
      version: plugin.version,
      uploadedAt: new Date().toISOString()
    },
    sourcePath: bundlePath
  });
  log.success(`  Stored bundle: ${fileName}`);
  return stored;
}

async function createVersionRecord(pool, plugin, stored) {
  const existing = await pool.query('select id from plugin_version where plugin_id = $1 and version = $2', [plugin.pluginId, plugin.version]);
  if (existing.rowCount > 0) {
    await pool.query(
      'update plugin_version set vsix_file_id = $3, file_size = $4, changelog = $5, deprecated = false, published_at = now() ' +
      'where plugin_id = $1 and version = $2',
      [plugin.pluginId, plugin.version, stored.id, stored.length, `Version ${plugin.version}`]
    );
    return;
  }
  await pool.query(
    'insert into plugin_version(id, plugin_id, version, changelog, vsix_file_id, file_size, deprecated, downloads, published_at) ' +
    'values ($1, $2, $3, $4, $5, $6, false, 0, now())',
    [newObjectId(), plugin.pluginId, plugin.version, `Version ${plugin.version}`, stored.id, stored.length]
  );
}

async function installPlugin(pool, plugin) {
  log.info(`Installing ${plugin.pluginId} v${plugin.version}...`);
  await insertPluginMetadata(pool, plugin);
  const stored = await uploadBundle(pool, plugin);
  if (!stored) {
    return false;
  }
  await createVersionRecord(pool, plugin, stored);
  return true;
}

async function listPlugins(pool) {
  log.header('Installed Plugins');

  const plugins = (await pool.query('select plugin_id, name, latest_version, category, verified from plugin order by plugin_id')).rows;

  if (plugins.length === 0) {
    log.info('No plugins installed');
    return;
  }

  console.log('');
  for (const p of plugins) {
    const status = p.verified ? `${colors.green}●${colors.reset}` : `${colors.red}○${colors.reset}`;
    console.log(`  ${status} ${colors.bright}${p.name}${colors.reset} (${p.plugin_id})`);
    console.log(`    Version: ${p.latest_version} | Category: ${p.category}`);
    console.log('');
  }

  log.info(`Total: ${plugins.length} plugins`);
}

async function cleanPlugins(pool) {
  log.header('Cleaning Plugins');

  const files = (await pool.query("select id from stored_file where bucket = 'plugins'")).rows;
  for (const file of files) {
    await deleteStoredFile(pool, BLOBS_DIR, file.id);
  }
  const versions = await pool.query('delete from plugin_version');
  const plugins = await pool.query('delete from plugin');

  log.success(`Deleted ${plugins.rowCount} plugins`);
  log.success(`Deleted ${versions.rowCount} versions`);
  log.success(`Deleted ${files.length} bundle files`);
}

async function main() {
  const args = parseArgs();

  console.log(`
${colors.bright}${colors.magenta}╔═══════════════════════════════════════════════════════════╗
║           OntoCode Plugin Manager                         ║
╚═══════════════════════════════════════════════════════════╝${colors.reset}
`);

  let plugins = PLUGINS;
  if (args.plugin) {
    plugins = PLUGINS.filter(p => p.pluginId === args.plugin);
    if (plugins.length === 0) {
      log.error(`Plugin not found: ${args.plugin}`);
      log.info(`Available plugins: ${PLUGINS.map(p => p.pluginId).join(', ')}`);
      process.exit(1);
    }
  }

  let pool = null;

  if (args.command !== 'build') {
    try {
      pool = newPool();
      log.info('Waiting for PostgreSQL and the plugin tables...');
      await waitForSchema(pool, 'plugin_version', { log: msg => log.info(msg) });
      log.success('Connected to PostgreSQL');
    } catch (error) {
      log.error(`Failed to connect to PostgreSQL: ${error.message}`);
      process.exit(1);
    }
  }

  try {
    switch (args.command) {
      case 'all':
        if (!args.skipBuild) {
          await buildAllPlugins(plugins);
        }
        log.header('Installing Plugins');
        for (const plugin of plugins) {
          await installPlugin(pool, plugin);
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
          await installPlugin(pool, plugin);
        }
        log.success(`Installed ${plugins.length} plugin(s)`);
        break;

      case 'list':
        await listPlugins(pool);
        break;

      case 'clean':
        await cleanPlugins(pool);
        break;

      default:
        log.error(`Unknown command: ${args.command}`);
        console.log(`
Usage: node manage-plugins.js [command] [options]

Commands:
  all       Build, insert metadata, and store bundles (default)
  build     Build all plugins
  install   Insert metadata and store bundles (no build)
  list      List installed plugins
  clean     Remove all plugins from database

Options:
  --plugin <id>   Process only specified plugin
  --force         Accepted for compatibility, existing rows are always updated
  --skip-build    Skip building plugins
`);
        process.exit(1);
    }
  } finally {
    if (pool) {
      await pool.end();
    }
  }
}

main().catch(error => {
  log.error(`Fatal error: ${error.message}`);
  console.error(error);
  process.exit(1);
});
