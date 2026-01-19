/**
 * Script to fix project documents that are missing ownerEmail field
 * CORRECTED to use the right database and collection names
 */

const { MongoClient } = require('mongodb');

const MONGO_URL = process.env.MONGO_URL || 'mongodb://localhost:27017';
const DB_NAME = 'ontocode';  // CORRECTED
const COLLECTION_NAME = 'projects';  // CORRECTED

// User email to assign unowned projects to
const DEFAULT_OWNER_EMAIL = process.argv[2] || 'sindhujap14012000@gmail.com';

async function fixProjectOwners() {
    console.log('🔧 Fixing project owners...');
    console.log(`📧 Default owner email: ${DEFAULT_OWNER_EMAIL}\n`);
    
    const client = new MongoClient(MONGO_URL);
    
    try {
        await client.connect();
        console.log('✅ Connected to MongoDB\n');
        
        const db = client.db(DB_NAME);
        const collection = db.collection(COLLECTION_NAME);
        
        // Find all projects without ownerEmail or with empty ownerEmail
        const unownedProjects = await collection.find({
            $or: [
                { ownerEmail: { $exists: false } },
                { ownerEmail: null },
                { ownerEmail: '' }
            ]
        }).toArray();
        
        console.log(`📊 Found ${unownedProjects.length} projects without owners\n`);
        
        if (unownedProjects.length === 0) {
            console.log('✨ All projects already have owners!');
            return;
        }
        
        // Display first 10 projects that will be updated
        console.log('📋 Projects to update (showing first 10):');
        unownedProjects.slice(0, 10).forEach((project, index) => {
            const id = project.id || project._id.toString();
            const filename = project.filename || project.name || 'no filename';
            console.log(`  ${index + 1}. ${id} - ${filename}`);
        });
        
        if (unownedProjects.length > 10) {
            console.log(`  ... and ${unownedProjects.length - 10} more`);
        }
        
        console.log(`\n🔄 Assigning these projects to: ${DEFAULT_OWNER_EMAIL}\n`);
        
        // Update all unowned projects
        const result = await collection.updateMany(
            {
                $or: [
                    { ownerEmail: { $exists: false } },
                    { ownerEmail: null },
                    { ownerEmail: '' }
                ]
            },
            {
                $set: { 
                    ownerEmail: DEFAULT_OWNER_EMAIL,
                    updatedAt: new Date()
                }
            }
        );
        
        console.log(`✅ Updated ${result.modifiedCount} projects\n`);
        
        // Verify the changes
        const verifyCount = await collection.countDocuments({
            ownerEmail: DEFAULT_OWNER_EMAIL
        });
        
        console.log(`✅ Total projects now owned by ${DEFAULT_OWNER_EMAIL}: ${verifyCount}`);
        
    } catch (error) {
        console.error('❌ Error:', error);
        throw error;
    } finally {
        await client.close();
        console.log('\n🔒 Database connection closed');
    }
}

fixProjectOwners()
    .then(() => {
        console.log('\n✨ Done! Refresh the OntoCode dashboard to see your files.');
        process.exit(0);
    })
    .catch((error) => {
        console.error('\n❌ Failed:', error);
        process.exit(1);
    });
