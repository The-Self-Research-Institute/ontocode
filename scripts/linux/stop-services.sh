#!/bin/bash

echo "Stopping services..."
kill $(lsof -t -i:8082) 2>/dev/null || echo "Gateway not running"
kill $(lsof -t -i:8086) 2>/dev/null || echo "OWL Editor not running"
kill $(lsof -t -i:8083) 2>/dev/null || echo "Auth not running"
echo "✅ Stopped"