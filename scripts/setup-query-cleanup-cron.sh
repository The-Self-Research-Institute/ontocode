#!/bin/bash
# Setup cron job to run query cleanup every 15 minutes (Linux/Mac)

echo ""
echo "╔════════════════════════════════════════════════════╗"
echo "║   Setup Automatic Query Cleanup (Every 15 min)    ║"
echo "╚════════════════════════════════════════════════════╝"
echo ""

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SCRIPT_PATH="$SCRIPT_DIR/cleanup-long-queries.js"

# Create cron entry
CRON_ENTRY="*/15 * * * * cd $SCRIPT_DIR && node cleanup-long-queries.js >> /var/log/ontocode-query-cleanup.log 2>&1"

# Check if cron entry already exists
if crontab -l 2>/dev/null | grep -q "cleanup-long-queries.js"; then
    echo "⚠️  Cron job already exists. Updating..."
    # Remove old entry
    crontab -l 2>/dev/null | grep -v "cleanup-long-queries.js" | crontab -
fi

# Add new cron entry
(crontab -l 2>/dev/null; echo "$CRON_ENTRY") | crontab -

if [ $? -eq 0 ]; then
    echo ""
    echo "╔════════════════════════════════════════════════════╗"
    echo "║   ✓ Cron Job Created Successfully                 ║"
    echo "╚════════════════════════════════════════════════════╝"
    echo ""
    echo "Cron Entry:"
    echo "  $CRON_ENTRY"
    echo ""
    echo "To manage this cron job:"
    echo "  - View all: crontab -l"
    echo "  - Edit: crontab -e"
    echo "  - Remove: crontab -l | grep -v 'cleanup-long-queries.js' | crontab -"
    echo "  - Logs: tail -f /var/log/ontocode-query-cleanup.log"
    echo ""
else
    echo ""
    echo "╔════════════════════════════════════════════════════╗"
    echo "║   ✗ Failed to Create Cron Job                     ║"
    echo "╚════════════════════════════════════════════════════╝"
    echo ""
fi
