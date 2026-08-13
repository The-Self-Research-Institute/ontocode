

const axios = require('axios');

const GRAPHDB_URL = 'http://localhost:7200';
const GRAPHDB_REPO = 'ontocode';
const MAX_QUERY_TIME_MINUTES = 15;

async function cleanupLongQueries() {
    console.log('\n╔════════════════════════════════════════╗');
    console.log('║   GraphDB Query Cleanup                ║');
    console.log('╚════════════════════════════════════════╝\n');

    try {

        console.log(`Checking for queries running longer than ${MAX_QUERY_TIME_MINUTES} minutes...`);

        let response;
        try {
            response = await axios.get(`${GRAPHDB_URL}/rest/monitor/query`, {
                headers: {
                    'Accept': 'application/json'
                }
            });
        } catch (err) {

            if (err.response && err.response.status === 406) {
                console.log('\n⚠️  Query monitoring API not available in GraphDB Free edition');
                console.log('Alternative: Use GraphDB Workbench → Monitor → Queries');
                console.log('URL: http://localhost:7200/monitor/queries\n');
                return;
            }
            throw err;
        }

        const queries = response.data || [];
        console.log(`Found ${queries.length} active queries\n`);

        if (queries.length === 0) {
            console.log('✓ No queries to clean up');
            return;
        }

        const now = Date.now();
        const maxAgeMs = MAX_QUERY_TIME_MINUTES * 60 * 1000;
        let killedCount = 0;

        for (const query of queries) {
            const queryAge = now - query.timestamp;
            const ageMinutes = Math.floor(queryAge / 60000);

            console.log(`Query: ${query.query.substring(0, 50)}...`);
            console.log(`  Age: ${ageMinutes} minutes`);
            console.log(`  Repository: ${query.repository || 'unknown'}`);

            if (queryAge > maxAgeMs) {
                try {

                    await axios.delete(`${GRAPHDB_URL}/rest/monitor/query/${query.trackAlias}`, {
                        headers: {
                            'Accept': 'application/json'
                        }
                    });

                    console.log(`  ✓ Killed (exceeded ${MAX_QUERY_TIME_MINUTES} minutes)\n`);
                    killedCount++;
                } catch (killError) {
                    console.log(`  ✗ Failed to kill: ${killError.message}\n`);
                }
            } else {
                console.log(`  ○ Still within time limit\n`);
            }
        }

        if (killedCount > 0) {
            console.log(`\n✓ Killed ${killedCount} long-running queries`);
        } else {
            console.log('\n✓ All queries are within the time limit');
        }

    } catch (error) {
        if (error.code === 'ECONNREFUSED') {
            console.error('✗ Cannot connect to GraphDB - is it running?');
        } else {
            console.error('✗ Cleanup failed:', error.message);
        }
        process.exit(1);
    }
}

cleanupLongQueries();
