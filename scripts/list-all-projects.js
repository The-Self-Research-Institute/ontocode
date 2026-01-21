/**
 * Script to list all projects in the database with their owner info
 */

const { MongoClient } = require('mongodb');

const MONGO_URL = process.env.MONGO_URL || 'mongodb://localhost:27017';
const DB_NAME = 'ontology_db';
const COLLECTION_NAME = 'projectDocuments';

async function listProjects() {
    console.log('📋 Listing all projects...\n');
    
    const client = new MongoClient(MONGO_URL);
    
    try {
        await client.connect();
        console.log('✅ Connected to MongoDB\n');
        
        const db = client.db(DB_NAME);
        const collection = db.collection(COLLECTION_NAME);
        
        const projects = await collection.find({}).sort({ updatedAt: -1 }).toArray();
        
        console.log(`📊 Total projects: ${projects.length}\n`);
        
        if (projects.length === 0) {
            console.log('⚠️ No projects found in database!');
            return;
        }
        
        console.log('Projects:');
        console.log('─'.repeat(100));
        
        projects.forEach((project, index) => {
            const id = project.id || project._id.toString();
            const filename = project.filename || 'no filename';
            const owner = project.ownerEmail || '(no owner)';
            const status = project.status || 'unknown';
            const updated = project.updatedAt ? new Date(project.updatedAt).toLocaleString() : 'unknown';
            
            console.log(`${index + 1}. ID: ${id}`);
            console.log(`   Filename: ${filename}`);
            console.log(`   Owner: ${owner}`);
            console.log(`   Status: ${status}`);
            console.log(`   Updated: ${updated}`);
            console.log('');
        });
        
        console.log('─'.repeat(100));
        
        // Summary by owner
        const ownerCounts = {};
        projects.forEach(p => {
            const owner = p.ownerEmail || '(no owner)';
            ownerCounts[owner] = (ownerCounts[owner] || 0) + 1;
        });
        
        console.log('\n📊 Projects by owner:');
        Object.entries(ownerCounts).forEach(([owner, count]) => {
            console.log(`   ${owner}: ${count} project(s)`);
        });
        
    } catch (error) {
        console.error('❌ Error:', error);
        throw error;
    } finally {
        await client.close();
        console.log('\n🔒 Database connection closed');
    }
}

listProjects()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error('\n❌ Failed:', error);
        process.exit(1);
    });
