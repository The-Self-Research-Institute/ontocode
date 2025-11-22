/**
 * Inspect what triples are in GraphDB
 * Shows the first 20 triples to see what they are
 */

const axios = require('axios');

const GRAPHDB_URL = 'http://localhost:7200';
const GRAPHDB_REPO = 'ontocode';

async function inspectTriples() {
    console.log('╔════════════════════════════════════════╗');
    console.log('║   GraphDB Triple Inspector             ║');
    console.log('╚════════════════════════════════════════╝\n');

    try {
        // Get total count
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
        console.log(`Total triple count: ${count}\n`);

        if (count === 0) {
            console.log('✓ No triples in repository\n');
            return;
        }

        // Get sample triples
        console.log('Sample triples (first 20):\n');
        const sampleQuery = 'SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 20';
        const sampleResponse = await axios.post(
            `${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}`,
            `query=${encodeURIComponent(sampleQuery)}`,
            {
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Accept': 'application/sparql-results+json'
                }
            }
        );

        const bindings = sampleResponse.data?.results?.bindings || [];
        bindings.forEach((binding, i) => {
            const s = binding.s?.value || '';
            const p = binding.p?.value || '';
            const o = binding.o?.value || '';

            console.log(`${i + 1}. Subject: ${s}`);
            console.log(`   Predicate: ${p}`);
            console.log(`   Object: ${o}\n`);
        });

        // Check for specific namespaces
        console.log('\n=== Namespace Analysis ===\n');

        const namespaceQuery = `
            SELECT ?namespace (COUNT(*) as ?count)
            WHERE {
                ?s ?p ?o .
                BIND(REPLACE(STR(?s), "(.*[/#])[^/#]*$", "$1") AS ?namespace)
            }
            GROUP BY ?namespace
            ORDER BY DESC(?count)
        `;

        const namespaceResponse = await axios.post(
            `${GRAPHDB_URL}/repositories/${GRAPHDB_REPO}`,
            `query=${encodeURIComponent(namespaceQuery)}`,
            {
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Accept': 'application/sparql-results+json'
                }
            }
        );

        const namespaces = namespaceResponse.data?.results?.bindings || [];
        console.log('Triples by namespace:');
        namespaces.forEach(ns => {
            const namespace = ns.namespace?.value || 'unknown';
            const count = ns.count?.value || 0;
            console.log(`  ${namespace}: ${count} triples`);
        });

    } catch (error) {
        if (error.code === 'ECONNREFUSED') {
            console.error('✗ Cannot connect to GraphDB - is it running?');
        } else {
            console.error('✗ Error:', error.message);
            if (error.response) {
                console.error('Response:', error.response.data);
            }
        }
    }
}

inspectTriples();
