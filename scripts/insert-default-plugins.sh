#!/bin/bash

################################################################################
# INSERT DEFAULT PLUGINS INTO MONGODB
################################################################################
#
# This script inserts default plugins into MongoDB.
# Used in Docker initialization.
#
# Prerequisites:
# - MongoDB running and accessible
# - Node.js installed
# - insert-default-plugins.js in same directory

set -e

echo "============================================================================"
echo "INSERT DEFAULT PLUGINS INTO MONGODB"
echo "============================================================================"
echo

# Default values
MONGODB_URI="${MONGODB_URI:-mongodb://admin:changeme@localhost:27017/ontology?authSource=admin}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "Error: Node.js is not installed!"
    exit 1
fi

# Install dependencies if needed
if [ ! -d "$SCRIPT_DIR/node_modules/mongodb" ]; then
    echo "Installing MongoDB driver..."
    cd "$SCRIPT_DIR"
    npm install mongodb --silent
fi

# Run the insert script
echo "Inserting plugins into database..."
cd "$SCRIPT_DIR"

# Pass MongoDB URI to the script
MONGODB_URI="$MONGODB_URI" node insert-default-plugins.js

if [ $? -eq 0 ]; then
    echo
    echo "✓ Plugins inserted successfully!"
    echo "You can now see them in the Plugin Marketplace!"
else
    echo
    echo "✗ Failed to insert plugins!"
    echo "Please check if MongoDB is running at $MONGODB_URI"
    exit 1
fi

echo
