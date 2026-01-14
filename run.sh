#!/bin/bash

set -e

# Load environment variables
if [ -f .env ]; then
  export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
fi

# Find the JAR
JAR_FILE=$(find target -name "obp-rabbit-cats-adapter*.jar" -not -name "*-sources.jar" | head -1)

if [ -z "$JAR_FILE" ]; then
  echo "❌ No JAR file found. Run ./build_and_run.sh first"
  exit 1
fi

echo "🚀 Starting OBP Rabbit Cats Adapter..."
echo "📍 Using: $JAR_FILE"
echo ""

java -jar "$JAR_FILE"
