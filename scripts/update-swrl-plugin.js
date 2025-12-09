const { MongoClient, GridFSBucket } = require('mongodb');
const fs = require('fs');
const path = require('path');

const MONGO_URL = process.env.MONGODB_URI || 'mongodb://localhost:27017';
const DB_NAME = process.env.MONGODB_DATABASE || 'ontology';
const PLUGIN_ID = 'swrl-editor-plugin';
const VERSION = '1.1.0';
// Upload the actual JavaScript bundle, not the VSIX
const JS_BUNDLE_PATH = path.join(__dirname, '../plugins/swrl-editor-plugin/dist/index.js');

async function updatePlugin() {
  const client = new MongoClient(MONGO_URL);
  
  try {
    await client.connect();
    console.log('Connected to MongoDB');
    
    const db = client.db(DB_NAME);
    const bucket = new GridFSBucket(db, { bucketName: 'plugins' }); // Match Spring GridFsTemplate config
    
    // Check if file exists
    if (!fs.existsSync(JS_BUNDLE_PATH)) {
      throw new Error(`JS bundle not found at ${JS_BUNDLE_PATH}`);
    }
    
    const fileSize = fs.statSync(JS_BUNDLE_PATH).size;
    const filename = `swrl-editor-plugin-${VERSION}.js`;
    
    // Upload to GridFS
    console.log(`Uploading ${filename} to GridFS...`);
    const uploadStream = bucket.openUploadStream(filename, {
      metadata: {
        pluginId: PLUGIN_ID,
        version: VERSION,
        author: 'admin@ontocode.com',
        uploadedAt: new Date().toISOString(),
        contentType: 'application/javascript'
      }
    });
    
    const fileId = uploadStream.id;
    
    await new Promise((resolve, reject) => {
      fs.createReadStream(JS_BUNDLE_PATH)
        .pipe(uploadStream)
        .on('error', reject)
        .on('finish', resolve);
    });
    
    console.log(`Uploaded file with ID: ${fileId}`);
    
    // Update plugin_versions collection
    const versionsCollection = db.collection('plugin_versions');
    
    // Check if version exists
    const existingVersion = await versionsCollection.findOne({ pluginId: PLUGIN_ID, version: VERSION });
    
    if (existingVersion) {
      console.log('Updating existing version entry...');
      await versionsCollection.updateOne(
        { _id: existingVersion._id },
        { 
          $set: {
            vsixFileId: fileId.toString(),
            fileSize: fileSize,
            publishedAt: new Date(),
            entryPoint: 'index.js'
          }
        }
      );
    } else {
      console.log('Creating new version entry...');
      await versionsCollection.insertOne({
        pluginId: PLUGIN_ID,
        version: VERSION,
        changelog: 'Fix UI rendering issues for inferred axioms',
        vsixFileId: fileId.toString(),
        fileSize: fileSize,
        dependencies: {},
        engines: {},
        entryPoint: 'index.js',
        deprecated: false,
        downloads: 0,
        publishedAt: new Date()
      });
    }
    
    // Update or create plugins collection entry
    const pluginsCollection = db.collection('plugins');
    console.log('Updating plugin metadata...');
    
    const existingPlugin = await pluginsCollection.findOne({ pluginId: PLUGIN_ID });
    
    if (existingPlugin) {
      await pluginsCollection.updateOne(
        { pluginId: PLUGIN_ID },
        {
          $set: {
            latestVersion: VERSION,
            updatedAt: new Date()
          }
        }
      );
    } else {
      console.log('Creating new plugin entry...');
      await pluginsCollection.insertOne({
        pluginId: PLUGIN_ID,
        name: 'SWRL Editor',
        shortDescription: 'Semantic Web Rule Language editor and validator',
        description: 'Create, edit, and execute SWRL rules with syntax validation. Includes built-in functions, rule templates, and execution engine integration.',
        latestVersion: VERSION,
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
      });
    }
    
    console.log('Plugin updated successfully!');
    
  } catch (error) {
    console.error('Error updating plugin:', error);
  } finally {
    await client.close();
  }
}

updatePlugin();
