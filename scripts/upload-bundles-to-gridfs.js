
/**
 * =============================================================================
 * UPLOAD PLUGIN BUNDLES TO GRIDFS
 * =============================================================================
 * 
 * This script uploads the built plugin bundles directly to MongoDB GridFS,
 * bypassing authentication. Use after running insert-default-plugins.js.
 */

const { MongoClient, GridFSBucket } = require('mongodb');
const fs = require('fs');
const path = require('path');

// Configuration
const MONGO_URL = 'mongodb://localhost:27017';
const DB_NAME = 'ontology';
const PLUGINS_COLLECTION = 'plugins';
const PLUGIN_VERSIONS_COLLECTION = 'plugin_versions';

const PLUGINS_DIR = path.join(__dirname, '..', 'plugins');

// Plugin bundles to upload
const PLUGIN_BUNDLES = [
  {
    pluginId: 'fuzzy-ontology-plugin',
    version: '1.1.0',
    bundlePath: path.join(PLUGINS_DIR, 'fuzzy-ontology-plugin', 'dist', 'index.js')
  },
  {
    pluginId: 'graph-view-plugin',
    version: '2.0.0',
    bundlePath: path.join(PLUGINS_DIR, 'graph-view-plugin', 'dist', 'index.js')
  },
  {
    pluginId: 'swrl-editor-plugin',
    version: '1.1.0',
    bundlePath: path.join(PLUGINS_DIR, 'swrl-editor-plugin', 'dist', 'index.js')
  },
  {
    pluginId: 'change-assistant-plugin',
    version: '1.0.0',
    bundlePath: path.join(PLUGINS_DIR, 'change-assistant-plugin', 'dist', 'index.js')
  }
];

/**
 * Upload bundle to GridFS
 */
async function uploadBundle(bucket, plugin) {
  console.log(`\n📦 Uploading ${plugin.pluginId} v${plugin.version}...`);

  if (!fs.existsSync(plugin.bundlePath)) {
    console.error(`   ❌ Bundle not found: ${plugin.bundlePath}`);
    return null;
  }

  const fileName = `${plugin.pluginId}-${plugin.version}.js`;
  
  // Check if file already exists
  const existingFiles = await bucket.find({ filename: fileName }).toArray();
  if (existingFiles.length > 0) {
    console.log(`   ⚠️  File already exists in GridFS, deleting old version...`);
    for (const file of existingFiles) {
      await bucket.delete(file._id);
    }
  }

  return new Promise((resolve, reject) => {
    const uploadStream = bucket.openUploadStream(fileName, {
      contentType: 'application/javascript',
      metadata: {
        pluginId: plugin.pluginId,
        version: plugin.version,
        uploadedAt: new Date()
      }
    });

    const readStream = fs.createReadStream(plugin.bundlePath);

    readStream.pipe(uploadStream)
      .on('error', reject)
      .on('finish', () => {
        console.log(`   ✅ Uploaded: ${fileName}`);
        console.log(`   File ID: ${uploadStream.id}`);
        resolve(uploadStream.id);
      });
  });
}

/**
 * Update plugin document with file reference and create version document
 */
async function updatePluginAndVersion(db, pluginId, version, fileId, bundlePath) {
  // Update main plugin document
  const pluginsCollection = db.collection(PLUGINS_COLLECTION);
  
  await pluginsCollection.updateOne(
    { pluginId },
    { 
      $set: {
        latestVersion: version,
        fileId: fileId.toString(),
        updatedAt: new Date()
      }
    }
  );
  console.log(`   ✅ Updated plugin document`);

  // Create or update plugin version document
  const versionsCollection = db.collection(PLUGIN_VERSIONS_COLLECTION);
  
  const fileStats = fs.statSync(bundlePath);
  
  const versionDoc = {
    pluginId,
    version,
    vsixFileId: fileId.toString(),
    releaseNotes: 'Initial release with UMD bundle support',
    downloads: 0,
    fileSize: fileStats.size,
    publishedAt: new Date(),
    updatedAt: new Date()
  };

  const existingVersion = await versionsCollection.findOne({ pluginId, version });
  
  if (existingVersion) {
    await versionsCollection.updateOne(
      { pluginId, version },
      { $set: versionDoc }
    );
    console.log(`   ✅ Updated version document`);
  } else {
    await versionsCollection.insertOne(versionDoc);
    console.log(`   ✅ Created version document`);
  }
}

/**
 * Main execution
 */
async function main() {
  console.log('=============================================================================');
  console.log('UPLOAD PLUGIN BUNDLES TO GRIDFS');
  console.log('=============================================================================\n');

  const client = new MongoClient(MONGO_URL);
  
  try {
    await client.connect();
    console.log('✅ Connected to MongoDB');

    const db = client.db(DB_NAME);
    const bucket = new GridFSBucket(db, { bucketName: 'plugins' }); // Match Spring GridFsTemplate config

    let successCount = 0;
    let failCount = 0;

    for (const plugin of PLUGIN_BUNDLES) {
      try {
        const fileId = await uploadBundle(bucket, plugin);
        if (fileId) {
          await updatePluginAndVersion(db, plugin.pluginId, plugin.version, fileId, plugin.bundlePath);
          successCount++;
        } else {
          failCount++;
        }
      } catch (error) {
        console.error(`   ❌ Error: ${error.message}`);
        failCount++;
      }
    }

    console.log('\n=============================================================================');
    console.log('UPLOAD SUMMARY');
    console.log('=============================================================================');
    console.log(`✅ Success: ${successCount}/${PLUGIN_BUNDLES.length}`);
    console.log(`❌ Failed: ${failCount}/${PLUGIN_BUNDLES.length}`);
    
    if (successCount > 0) {
      console.log('\n🎉 Plugin bundles are now available for download!');
      console.log('   Test by installing plugins from the marketplace');
    }

  } catch (error) {
    console.error('\n❌ Error:', error.message);
    
    if (error.message.includes('ECONNREFUSED')) {
      console.error('\n⚠️  MongoDB is not running!');
      console.error('   Please start MongoDB first');
    }
    
    process.exit(1);
  } finally {
    await client.close();
    console.log('\nDisconnected from MongoDB');
  }
}

// Run the script
main().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
