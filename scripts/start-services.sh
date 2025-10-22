#!/bin/bash

echo "🚀 Starting all services..."

# Start each service in background
cd gateway && mvn spring-boot:run &
PID_GATEWAY=$!

cd ../owl-editor-service && mvn spring-boot:run &
PID_OWL=$!

cd ../swrl-service && mvn spring-boot:run &
PID_SWRL=$!

cd ..

echo ""
echo "✅ Services started:"
echo "  Gateway:     http://localhost:8082"
echo "  OWL Editor:  http://localhost:8083"
echo "  SWRL:        http://localhost:8084"
echo ""
echo "Press Ctrl+C to stop all"

trap "kill $PID_GATEWAY $PID_OWL $PID_SWRL 2>/dev/null; exit 0" INT

wait