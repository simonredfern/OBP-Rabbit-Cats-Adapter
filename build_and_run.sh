#!/bin/bash

set -e

echo "======================================"
echo "OBP Rabbit Cats Adapter - Build & Run"
echo "======================================"
echo ""

# Check if .env exists
if [ ! -f .env ]; then
  echo "⚠️  No .env file found. Creating from .env.example..."
  cp .env.example .env
  echo "✅ Created .env file. Please review and customize if needed."
  echo ""
fi

# Load environment variables
if [ -f .env ]; then
  echo "📝 Loading environment variables from .env..."
  export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
  echo "✅ Environment loaded"
  echo ""
fi

# Check Java version
echo "☕ Checking Java version..."
java -version
echo ""

# Check RabbitMQ connection
echo "🐰 Checking RabbitMQ connection..."
RABBITMQ_HOST=${RABBITMQ_HOST:-localhost}
RABBITMQ_PORT=${RABBITMQ_PORT:-5672}

if command -v nc > /dev/null; then
  if nc -z $RABBITMQ_HOST $RABBITMQ_PORT 2>/dev/null; then
    echo "✅ RabbitMQ is reachable at $RABBITMQ_HOST:$RABBITMQ_PORT"
  else
    echo "⚠️  Cannot reach RabbitMQ at $RABBITMQ_HOST:$RABBITMQ_PORT"
    echo "   Make sure RabbitMQ is running:"
    echo "   docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management"
    echo ""
    echo "   Or install locally: https://www.rabbitmq.com/download.html"
    echo ""
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
      exit 1
    fi
  fi
else
  echo "ℹ️  netcat (nc) not available, skipping connection check"
fi
echo ""

# Clean and compile
echo "🧹 Cleaning previous build..."
mvn clean -q
echo "✅ Clean complete"
echo ""

echo "🔨 Compiling project..."
mvn compile
if [ $? -ne 0 ]; then
  echo "❌ Compilation failed!"
  exit 1
fi
echo "✅ Compilation successful"
echo ""

# Package
echo "📦 Packaging JAR..."
mvn package -DskipTests
if [ $? -ne 0 ]; then
  echo "❌ Packaging failed!"
  exit 1
fi
echo "✅ Package complete"
echo ""

# Find the JAR
JAR_FILE=$(find target -name "obp-rabbit-cats-adapter*.jar" -not -name "*-sources.jar" | head -1)

if [ -z "$JAR_FILE" ]; then
  echo "❌ Could not find JAR file in target/"
  exit 1
fi

echo "📍 JAR file: $JAR_FILE"
echo ""

# Show configuration
echo "======================================"
echo "Configuration:"
echo "======================================"
echo "RabbitMQ Host:      ${RABBITMQ_HOST}"
echo "RabbitMQ Port:      ${RABBITMQ_PORT}"
echo "Request Queue:      ${RABBITMQ_REQUEST_QUEUE:-obp.request}"
echo "Response Queue:     ${RABBITMQ_RESPONSE_QUEUE:-obp.response}"
echo "CBS Connector:      ${CBS_CONNECTOR_TYPE:-mock}"
echo "Telemetry:          ${TELEMETRY_TYPE:-console}"
echo "Log Level:          ${LOG_LEVEL:-INFO}"
echo "======================================"
echo ""

echo "🚀 Starting adapter..."
echo ""
echo "Press Ctrl+C to stop"
echo ""

# Run the adapter
java -jar "$JAR_FILE"
