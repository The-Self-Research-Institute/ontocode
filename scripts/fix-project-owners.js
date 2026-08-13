

const { MongoClient } = require('mongodb');

const MONGO_URL = process.env.MONGO_URL || 'mongodb://localhost:27017';
const DB_NAME = 'ontology_db';
const COLLECTION_NAME = 'projectDocuments';

const DEFAULT_OWNER_EMAIL = process.argv[2] || 'sindhujap14012000@gmail.com';

async function fixProjectOwners() {
    console.log('🔧 Fixing project owners...');
    console.log(`📧 Default owner email: ${DEFAULT_OWNER_EMAIL}`);

    const client = new MongoClient(MONGO_URL);

    try {
        await client.connect();
        console.log('✅ Connected to MongoDB');

        const db = client.db(DB_NAME);
        const collection = db.collection(COLLECTION_NAME);

        const unownedProjects = await collection.find({
            $or: [
                { ownerEmail: { $exists: false } },
                { ownerEmail: null },
                { ownerEmail: '' }
            ]
        }).toArray();

        console.log(`📊 Found ${unownedProjects.length} projects without owners`);

        if (unownedProjects.length === 0) {
            console.log('✨ All projects already have owners!');
            return;
        }

        console.log('\n📋 Projects to update:');
        unownedProjects.forEach((project, index) => {
            console.log(`  ${index + 1}. ${project.id || project._id} (${project.filename || 'no filename'})`);
        });

        console.log(`\n🔄 Assigning these projects to: ${DEFAULT_OWNER_EMAIL}`);

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

        console.log(`\n✅ Updated ${result.modifiedCount} projects`);

        const verifyCount = await collection.countDocuments({
            ownerEmail: DEFAULT_OWNER_EMAIL
        });

        console.log(`✅ Total projects owned by ${DEFAULT_OWNER_EMAIL}: ${verifyCount}`);

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
        console.log('\n✨ Done!');
        process.exit(0);
    })
    .catch((error) => {
        console.error('\n❌ Failed:', error);
        process.exit(1);
    });
