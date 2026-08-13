

const { MongoClient } = require('mongodb');

const MONGO_URL = process.env.MONGO_URL || 'mongodb://localhost:27017';

async function listDatabasesAndCollections() {
    console.log('🔍 Discovering MongoDB structure...\n');

    const client = new MongoClient(MONGO_URL);

    try {
        await client.connect();
        console.log('✅ Connected to MongoDB\n');

        const adminDb = client.db().admin();
        const dbList = await adminDb.listDatabases();

        console.log('📚 Databases:');
        console.log('═'.repeat(80));

        for (const dbInfo of dbList.databases) {
            const dbName = dbInfo.name;
            const sizeInMB = (dbInfo.sizeOnDisk / (1024 * 1024)).toFixed(2);

            console.log(`\n📁 ${dbName} (${sizeInMB} MB)`);

            if (dbName === 'admin' || dbName === 'config' || dbName === 'local') {
                console.log('   (system database, skipping collections)');
                continue;
            }

            const db = client.db(dbName);
            const collections = await db.listCollections().toArray();

            if (collections.length === 0) {
                console.log('   (no collections)');
            } else {
                console.log('   Collections:');
                for (const coll of collections) {
                    const collectionName = coll.name;
                    const count = await db.collection(collectionName).countDocuments();
                    console.log(`   - ${collectionName} (${count} documents)`);
                }
            }
        }

        console.log('\n' + '═'.repeat(80));

    } catch (error) {
        console.error('❌ Error:', error);
        throw error;
    } finally {
        await client.close();
        console.log('\n🔒 Database connection closed');
    }
}

listDatabasesAndCollections()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error('\n❌ Failed:', error);
        process.exit(1);
    });
