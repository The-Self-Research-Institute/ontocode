#!/bin/bash

echo ""
echo "╔════════════════════════════════════════════════════╗"
echo "║   Setup Automatic Query Cleanup (Every 15 min)    ║"
echo "╚════════════════════════════════════════════════════╝"
echo ""

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SCRIPT_PATH="$SCRIPT_DIR/cleanup-long-queries.js"

CRON_ENTRY="*/15 * * * * cd $SCRIPT_DIR && node cleanup-long-queries.js >> /var/log/ontocode-query-cleanup.log 2>&1"

if crontab -l 2>/dev/null | grep -q "cleanup-long-queries.js"; then
    echo "⚠️  Cron job already exists. Updating..."

    crontab -l 2>/dev/null | grep -v "cleanup-long-queries.js" | crontab -
fi

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
