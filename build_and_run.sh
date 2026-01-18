#!/bin/bash

set -e

echo "======================================"
echo "OBP Rabbit Cats Adapter - Build & Run"
echo "======================================"
echo ""

# Check if .env exists
if [ ! -f .env ]; then
  echo "[WARNING] No .env file found. Creating from .env.example..."
  cp .env.example .env
  echo "[OK] Created .env file. Please review and customize if needed."
  echo ""
fi

# Load environment variables
if [ -f .env ]; then
  echo "[CONFIG] Loading environment variables from .env..."
  export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
  echo "[OK] Environment loaded"
  echo ""
fi

# Check Java version
echo "[INFO] Checking Java version..."
java -version
echo ""

# Check RabbitMQ connection
echo "[RabbitMQ] Checking RabbitMQ connection..."
RABBITMQ_HOST=${RABBITMQ_HOST:-localhost}
RABBITMQ_PORT=${RABBITMQ_PORT:-5672}

if command -v nc > /dev/null; then
  if nc -z $RABBITMQ_HOST $RABBITMQ_PORT 2>/dev/null; then
    echo "[OK] RabbitMQ is reachable at $RABBITMQ_HOST:$RABBITMQ_PORT"
  else
    echo "[WARNING] Cannot reach RabbitMQ at $RABBITMQ_HOST:$RABBITMQ_PORT"

    # Check if Docker is available
    if command -v docker > /dev/null; then
      echo "[INFO] Docker is available. Checking for RabbitMQ container..."

      # Check if rabbitmq container exists
      if docker ps -a --format '{{.Names}}' | grep -q '^rabbitmq$'; then
        # Container exists, check if it's running
        if docker ps --format '{{.Names}}' | grep -q '^rabbitmq$'; then
          echo "[INFO] RabbitMQ container is running but not reachable yet. Waiting..."
          sleep 5
        else
          echo "[INFO] Starting existing RabbitMQ container..."
          docker start rabbitmq
          echo "[INFO] Waiting for RabbitMQ to be ready..."
          sleep 10
        fi
      else
        # Container doesn't exist, create and start it
        echo "[INFO] Creating and starting RabbitMQ container..."
        docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
        echo "[INFO] Waiting for RabbitMQ to be ready..."
        sleep 15
      fi

      # Check connection again
      if nc -z $RABBITMQ_HOST $RABBITMQ_PORT 2>/dev/null; then
        echo "[OK] RabbitMQ is now reachable at $RABBITMQ_HOST:$RABBITMQ_PORT"
        echo "[INFO] Management UI available at http://localhost:15672 (guest/guest)"
      else
        echo "[WARNING] RabbitMQ container started but still not reachable"
        echo "   You may need to wait a bit longer for RabbitMQ to initialize"
        echo ""
        read -p "Continue anyway? (y/n) " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
          exit 1
        fi
      fi
    else
      echo "[ERROR] Docker is not available"
      echo "   Please install Docker or start RabbitMQ manually"
      echo "   Manual installation: https://www.rabbitmq.com/download.html"
      echo ""
      read -p "Continue anyway? (y/n) " -n 1 -r
      echo ""
      if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
      fi
    fi
  fi
else
  echo "[INFO] netcat (nc) not available, skipping connection check"
fi
echo ""

# Check Redis connection
echo "[Redis] Checking Redis connection..."
REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_ENABLED=${REDIS_ENABLED:-true}

if [ "$REDIS_ENABLED" = "true" ]; then
  if command -v nc > /dev/null; then
    if nc -z $REDIS_HOST $REDIS_PORT 2>/dev/null; then
      echo "[OK] Redis is reachable at $REDIS_HOST:$REDIS_PORT"
    else
      echo "[WARNING] Cannot reach Redis at $REDIS_HOST:$REDIS_PORT"

      if command -v docker > /dev/null; then
        echo "[INFO] Docker is available. Checking for Redis container..."

        if docker ps -a --format '{{.Names}}' | grep -q '^redis$'; then
          if docker ps --format '{{.Names}}' | grep -q '^redis$'; then
            echo "[INFO] Redis container is running but not reachable yet. Waiting..."
            sleep 2
          else
            echo "[INFO] Starting existing Redis container..."
            docker start redis
            echo "[INFO] Waiting for Redis to be ready..."
            sleep 3
          fi
        else
          echo "[INFO] Creating and starting Redis container..."
          docker run -d --name redis -p 6379:6379 redis:7-alpine
          echo "[INFO] Waiting for Redis to be ready..."
          sleep 5
        fi

        if nc -z $REDIS_HOST $REDIS_PORT 2>/dev/null; then
          echo "[OK] Redis is now reachable at $REDIS_HOST:$REDIS_PORT"
        else
          echo "[WARNING] Redis container started but still not reachable"
          echo "   You may need to wait a bit longer for Redis to initialize"
        fi
      else
        echo "[WARNING] Docker is not available, Redis will not be started"
      fi
    fi
  else
    echo "[INFO] netcat (nc) not available, skipping Redis connection check"
  fi
else
  echo "[INFO] Redis is disabled"
fi
echo ""

# Clean and compile
echo "[BUILD] Cleaning previous build..."
mvn clean -q
echo "[OK] Clean complete"
echo ""

echo "[BUILD] Compiling project..."
mvn compile
if [ $? -ne 0 ]; then
  echo "[ERROR] Compilation failed!"
  exit 1
fi
echo "[OK] Compilation successful"
echo ""

# Package
echo "[BUILD] Packaging JAR..."
mvn package -DskipTests
if [ $? -ne 0 ]; then
  echo "[ERROR] Packaging failed!"
  exit 1
fi
echo "[OK] Package complete"
echo ""

# Find the JAR
JAR_FILE=$(find target -name "obp-rabbit-cats-adapter*.jar" -not -name "*-sources.jar" | head -1)

if [ -z "$JAR_FILE" ]; then
  echo "[ERROR] Could not find JAR file in target/"
  exit 1
fi

echo "[INFO] JAR file: $JAR_FILE"
echo ""

# Show configuration
echo "======================================"
echo "Configuration:"
echo "======================================"
echo "HTTP Server:        ${HTTP_HOST:-0.0.0.0}:${HTTP_PORT:-8090}"
echo "RabbitMQ Host:      ${RABBITMQ_HOST}"
echo "RabbitMQ Port:      ${RABBITMQ_PORT}"
echo "Request Queue:      ${RABBITMQ_REQUEST_QUEUE:-obp.request}"
echo "Response Queue:     ${RABBITMQ_RESPONSE_QUEUE:-obp.response}"
echo "CBS Connector:      ${CBS_CONNECTOR_TYPE:-mock}"
echo "Telemetry:          ${TELEMETRY_TYPE:-console}"
echo "Log Level:          ${LOG_LEVEL:-INFO}"
echo "======================================"
echo ""

echo "[STARTUP] Starting adapter..."
echo ""
echo "Discovery UI: http://localhost:${HTTP_PORT:-8090}"
echo "Press Ctrl+C to stop"
echo ""

# Run the adapter
java -jar "$JAR_FILE"
