/**
 * Database Cleanup Script
 * Clears both MongoDB and GraphDB
 *
 * Run with: node clear-databases.js
 */

const MongoClient = require('mongodb').MongoClient;
const axios = require('axios');

// Configuration
const MONGODB_URI = 'mongodb://localhost:27017';
const MONGODB_DB = 'ontocode';
const GRAPHDB_URL = 'http://localhost:7200';
const GRAPHDB_REPO = 'ontocode';

async function clearMongoDB() {
    console.log('\n=== Clearing MongoDB ===');
    console.log(`Connecting to: ${MONGODB_URI}/${MONGODB_DB}`);

    const client = new MongoClient(MONGODB_URI);

    try {
        await client.connect();
        console.log('✓ Connected to MongoDB');

        const db = client.db(MONGODB_DB);
        const collections = await db.listCollections().toArray();

        console.log(`\nFound ${collections.length} collections:`);

        for (const collection of collections) {
            const count = await db.collection(collection.name).countDocuments();
            console.log(`  - ${collection.name}: ${count} documents`);
        }

        console.log('\nDropping collections...');
        for (const collection of collections) {
            await db.collection(collection.name).drop();
            console.log(`  ✓ Dropped: ${collection.name}`);
        }

        console.log('\n✓ MongoDB cleanup complete!');

    } catch (error) {
        console.error('✗ MongoDB cleanup failed:', error.message);
        throw error;
    } finally {
        await client.close();
    }
}

async function clearGraphDB() {
    console.log('\n=== Clearing GraphDB ===');
    console.log(`Repository: ${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}`);

    try {
        // Check if GraphDB is accessible
        await axios.get(`${GRAPHDB_URL}/rest/repositories`);
        console.log('✓ GraphDB is accessible');

        // Get current triple count
        const countQuery = 'SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }';
        const countResponse = await axios.post(
            `${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}`,
            `query=${encodeURIComponent(countQuery)}`,
            {
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Accept': 'application/sparql-results+json'
                }
            }
        );

        const count = countResponse.data?.results?.bindings?.[0]?.count?.value || 0;
        console.log(`\nCurrent triple count: ${count}`);

        if (count > 0) {
            // Clear all triples
            console.log('\nClearing all triples...');
            const deleteQuery = 'DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }';

            await axios.post(
                `${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}/statements`,
                `update=${encodeURIComponent(deleteQuery)}`,
                {
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded'
                    }
                }
            );

            console.log('✓ All triples cleared');
        } else {
            console.log('✓ GraphDB is already empty');
        }

        console.log('\n✓ GraphDB cleanup complete!');

    } catch (error) {
        if (error.code === 'ECONNREFUSED') {
            console.error('✗ Cannot connect to GraphDB - is it running?');
        } else {
            console.error('✗ GraphDB cleanup failed:', error.message);
        }
        throw error;
    }
}

async function main() {
    console.log('╔════════════════════════════════════════╗');
    console.log('║   Database Cleanup Script              ║');
    console.log('╚════════════════════════════════════════╝');

    try {
        await clearMongoDB();
        await clearGraphDB();

        console.log('\n╔════════════════════════════════════════╗');
        console.log('║   ✓ All databases cleared successfully ║');
        console.log('╚════════════════════════════════════════╝\n');

    } catch (error) {
        console.log('\n╔════════════════════════════════════════╗');
        console.log('║   ✗ Cleanup failed                     ║');
        console.log('╚════════════════════════════════════════╝\n');
        process.exit(1);
    }
}

main();
