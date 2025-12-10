// MongoDB Cleanup Script
// Run with: mongosh mongodb://localhost:27017/ontocode clear-mongodb.js

const dbName = 'ontocode';

print('=== MongoDB Cleanup Script ===');
print(`Connecting to database: ${dbName}`);

// Switch to the database
db = db.getSiblingDB(dbName);

print('\nCollections before cleanup:');
db.getCollectionNames().forEach(function(collection) {
    const count = db[collection].countDocuments();
    print(`  - ${collection}: ${count} documents`);
});

print('\n=== Starting cleanup ===\n');

// Drop all collections
const collections = db.getCollectionNames();
collections.forEach(function(collection) {
    print(`Dropping collection: ${collection}`);
    db[collection].drop();
});

print('\n=== Cleanup complete ===');
print('All collections have been dropped.');

print('\nCollections after cleanup:');
const remainingCollections = db.getCollectionNames();
if (remainingCollections.length === 0) {
    print('  (no collections remaining)');
} else {
    remainingCollections.forEach(function(collection) {
        print(`  - ${collection}`);
    });
}
