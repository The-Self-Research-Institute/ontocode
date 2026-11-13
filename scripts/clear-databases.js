

const { newPool } = require('./lib/db');
const axios = require('axios');

const GRAPHDB_URL = 'http://localhost:7200';
const GRAPHDB_REPO = 'ontocode';

async function clearPostgres() {
    console.log('\n=== Clearing PostgreSQL ===');

    const pool = newPool();

    try {
        const tables = (await pool.query(
            "select table_name from information_schema.tables where table_schema = 'public' " +
            "and table_type = 'BASE TABLE' and table_name not like 'flyway%' order by table_name"
        )).rows.map(r => r.table_name);
        console.log('✓ Connected to PostgreSQL');

        console.log(`\nFound ${tables.length} tables:`);

        for (const table of tables) {
            const count = (await pool.query('select count(*)::bigint as n from "' + table + '"')).rows[0].n;
            console.log(`  - ${table}: ${count} rows`);
        }

        if (tables.length > 0) {
            console.log('\nEmptying tables...');
            await pool.query('truncate table ' + tables.map(t => '"' + t + '"').join(', ') + ' restart identity cascade');
            console.log('  ✓ Emptied all tables (schema kept)');
        }

        console.log('\n✓ PostgreSQL cleanup complete! Files in the blob folder are not removed.');

    } catch (error) {
        console.error('✗ PostgreSQL cleanup failed:', error.message);
        throw error;
    } finally {
        await pool.end();
    }
}

async function clearGraphDB() {
    console.log('\n=== Clearing GraphDB ===');
    console.log(`Repository: ${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}`);

    try {

        await axios.get(`${GRAPHDB_URL}/rest/repositories`);
        console.log('✓ GraphDB is accessible');

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

        if (count > 102) {

            console.log('\nClearing user data triples...');
            console.log('(Note: ~102 system triples will remain - these are GraphDB RDF/RDFS/OWL metadata)');

            const deleteUpdate = 'CLEAR ALL';

            await axios.post(
                `${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}/statements`,
                `update=${encodeURIComponent(deleteUpdate)}`,
                {
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'Accept': 'application/json'
                    },
                    timeout: 30000 // 30 second timeout
                }
            );

            console.log('✓ User data cleared');
            console.log('✓ System triples remain (RDF/RDFS/OWL vocabulary - this is normal)');
        } else if (count >= 100 && count <= 105) {

            console.log('✓ GraphDB contains only system triples (RDF/RDFS/OWL vocabulary)');
            console.log('✓ No user data to clear - database is clean');
        } else if (count > 0) {
            console.log(`✓ GraphDB contains ${count} triples`);
            console.log('✓ No user ontology data found');
        } else {
            console.log('✓ GraphDB is completely empty');
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
        await clearPostgres();
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
