/**
 * =============================================================================
 * UPLOAD DEFAULT PLUGINS TO BACKEND
 * =============================================================================
 * 
 * This script packages and uploads the default plugins to the plugin service:
 * 1. Fuzzy Ontology Plugin
 * 2. Graph View Plugin  
 * 3. SWRL Editor Plugin
 * 
 * Prerequisites:
 * - Plugin service running on localhost:8087
 * - MongoDB running
 * - Valid JWT token (or use default admin credentials)
 */

const fs = require('fs');
const path = require('path');
const FormData = require('form-data');
const axios = require('axios');
const { execSync } = require('child_process');

// Configuration
const PLUGIN_SERVICE_URL = 'http://localhost:8087/api/plugins';
const PLUGINS_DIR = path.join(__dirname, '..', 'plugins');

// Default plugins to upload
const DEFAULT_PLUGINS = [
  {
    pluginId: 'fuzzy-ontology-plugin',
    name: 'Fuzzy Ontology Advanced Plugin',
    description: 'Advanced fuzzy ontology support with membership degrees, fuzzy reasoning, and interactive visualization - beyond Protege capabilities',
    version: '1.0.0',
    category: 'Reasoning',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['fuzzy', 'ontology', 'membership', 'reasoning', 'fuzzy-logic', 'owl', 'visualization'],
    icon: null,
    screenshots: []
  },
  {
    pluginId: 'graph-view-plugin',
    name: 'Ontology Graph View',
    description: 'Interactive graph visualization for ontology classes, properties, and relationships using network diagrams',
    version: '1.0.0',
    category: 'Visualization',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['ontology', 'graph', 'visualization', 'network', 'diagram', 'owl', 'rdf', 'knowledge graph'],
    icon: null,
    screenshots: []
  },
  {
    pluginId: 'swrl-editor-plugin',
    name: 'SWRL Editor',
    description: 'Semantic Web Rule Language editor and validator for ontologies. Create, edit, and execute SWRL rules with syntax validation.',
    version: '1.0.0',
    category: 'Editor',
    author: 'OntoCode Team',
    authorEmail: 'admin@ontocode.com',
    keywords: ['swrl', 'rules', 'reasoning', 'semantic-web', 'owl', 'ontology'],
    icon: null,
    screenshots: []
  }
];

/**
 * Get or create JWT token for admin user
 * For now, we'll create plugins without authentication (adjust CORS on backend)
 */
async function getAuthToken() {
  // TODO: Implement proper authentication
  // For development, return null and configure backend to allow plugin upload without auth
  return null;
}

/**
 * Build plugin using webpack and return path to bundle
 */
function buildPlugin(pluginDir, pluginId) {
  const sourceDir = path.join(PLUGINS_DIR, pluginDir);
  const packageJsonPath = path.join(sourceDir, 'package.json');
  const bundlePath = path.join(sourceDir, 'dist', 'index.js');
  
  if (!fs.existsSync(packageJsonPath)) {
    throw new Error(`package.json not found for ${pluginId}`);
  }

  console.log(`   Building plugin with webpack...`);
  
  try {
    // Install dependencies if node_modules doesn't exist
    if (!fs.existsSync(path.join(sourceDir, 'node_modules'))) {
      console.log(`   Installing dependencies...`);
      execSync('npm install', { 
        cwd: sourceDir, 
        stdio: 'inherit',
        shell: true 
      });
    }

    // Build with webpack
    execSync('npx webpack --mode production', { 
      cwd: sourceDir,
      stdio: 'pipe',
      shell: true
    });

    if (!fs.existsSync(bundlePath)) {
      throw new Error(`Bundle not found at ${bundlePath}`);
    }

    console.log(`   ✅ Built successfully: ${bundlePath}`);
    return bundlePath;
  } catch (error) {
    throw new Error(`Failed to build ${pluginId}: ${error.message}`);
  }
}

/**
 * Upload plugin to backend
 */
async function uploadPlugin(plugin) {
  console.log(`\n📦 Uploading ${plugin.name}...`);
  
  try {
    // Build plugin to get UMD bundle
    const bundlePath = buildPlugin(plugin.pluginId, plugin.pluginId);
    
    // Create form data
    const form = new FormData();
    
    // Add metadata as JSON
    const metadata = {
      pluginId: plugin.pluginId,
      name: plugin.name,
      description: plugin.description,
      version: plugin.version,
      category: plugin.category,
      author: plugin.author,
      authorEmail: plugin.authorEmail,
      keywords: plugin.keywords,
      icon: plugin.icon,
      screenshots: plugin.screenshots
    };
    
    form.append('metadata', JSON.stringify(metadata), {
      contentType: 'application/json'
    });
    
    // Add UMD bundle as the plugin file
    form.append('vsixFile', fs.createReadStream(bundlePath), {
      filename: `${plugin.pluginId}-${plugin.version}.js`,
      contentType: 'application/javascript'
    });

    // Get auth token
    const token = await getAuthToken();
    const headers = {
      ...form.getHeaders()
    };
    
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    // Upload to backend
    const response = await axios.post(PLUGIN_SERVICE_URL, form, {
      headers,
      maxContentLength: Infinity,
      maxBodyLength: Infinity
    });

    console.log(`✅ Successfully uploaded ${plugin.name}`);
    console.log(`   Plugin ID: ${response.data.pluginId}`);
    console.log(`   Version: ${response.data.latestVersion}`);
    
    return response.data;
  } catch (error) {
    console.error(`❌ Failed to upload ${plugin.name}:`);
    if (error.response) {
      console.error(`   Status: ${error.response.status}`);
      console.error(`   Error: ${JSON.stringify(error.response.data, null, 2)}`);
    } else {
      console.error(`   Error: ${error.message}`);
    }
    throw error;
  }
}

/**
 * Check if backend is reachable
 */
async function checkBackend() {
  try {
    console.log('🔍 Checking plugin service...');
    const response = await axios.get(`${PLUGIN_SERVICE_URL}?size=1`);
    console.log('✅ Plugin service is running');
    console.log(`   Current plugins count: ${response.data.totalElements || 0}`);
    return true;
  } catch (error) {
    console.error('❌ Plugin service is not reachable!');
    console.error('   Make sure the service is running on localhost:8087');
    return false;
  }
}

/**
 * Main execution
 */
async function main() {
  console.log('=============================================================================');
  console.log('UPLOAD DEFAULT PLUGINS TO BACKEND');
  console.log('=============================================================================\n');

  // Check if backend is running
  const isBackendRunning = await checkBackend();
  if (!isBackendRunning) {
    console.log('\n⚠️  Please start the plugin service first:');
    console.log('   cd ontology-plugin-service');
    console.log('   mvn spring-boot:run\n');
    process.exit(1);
  }

  // Upload each plugin
  console.log('\n📤 Starting plugin upload...\n');
  let successCount = 0;
  let failCount = 0;

  for (const plugin of DEFAULT_PLUGINS) {
    try {
      await uploadPlugin(plugin);
      successCount++;
    } catch (error) {
      failCount++;
      // Continue with next plugin
    }
  }

  // Summary
  console.log('\n=============================================================================');
  console.log('UPLOAD SUMMARY');
  console.log('=============================================================================');
  console.log(`✅ Success: ${successCount}/${DEFAULT_PLUGINS.length}`);
  console.log(`❌ Failed: ${failCount}/${DEFAULT_PLUGINS.length}`);
  
  if (successCount > 0) {
    console.log('\n🎉 Plugins are now available in the marketplace!');
    console.log('   Open the extension and check the Plugin Marketplace');
  }
  
  process.exit(failCount > 0 ? 1 : 0);
}

// Run the script
main().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
