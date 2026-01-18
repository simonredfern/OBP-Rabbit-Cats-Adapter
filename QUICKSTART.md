# Quick Start Guide

## Prerequisites

- Java 11+
- Docker (for RabbitMQ) OR RabbitMQ installed locally
- Maven 3.6+ (for building from source)

## Quick Start (Using Scripts)

The **easiest way** to get started:

```bash
# 1. Start RabbitMQ in Docker
./start_rabbitmq.sh

# 2. Build and run the adapter (in a new terminal)
./build_and_run.sh

# 3. Open browser
http://localhost:8090

# 4. Click "Send Get Adapter Info" button
# Watch the logs to see the message being processed!
```

That's it! The scripts handle everything automatically.

---

## Manual Setup (Alternative)

If you prefer to do things manually or want more control:

### Step 1: Start RabbitMQ

#### Option A: Using Docker (recommended):

```bash
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management
```

This starts RabbitMQ with:
- AMQP port: 5672
- Management UI: http://localhost:15672 (guest/guest)

#### Option B: Using local installation:

```bash
# Start RabbitMQ service
sudo service rabbitmq-server start

# Or on macOS:
brew services start rabbitmq
```

### Step 2: Build the Adapter

```bash
mvn clean package -DskipTests
```

This creates: `target/obp-rabbit-cats-adapter-1.0.0-SNAPSHOT.jar` (~37MB)

### Step 3: Configure

Create a `.env` file (or copy from `.env.example`):

```bash
# HTTP Server (Discovery Page)
HTTP_HOST=0.0.0.0
HTTP_PORT=8090
HTTP_ENABLED=true

# RabbitMQ Configuration
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_VIRTUAL_HOST=/
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_REQUEST_QUEUE=obp.request
RABBITMQ_RESPONSE_QUEUE=obp.response
RABBITMQ_PREFETCH_COUNT=10

# CBS Configuration (Mock)
CBS_BASE_URL=http://localhost:9000
CBS_AUTH_TYPE=none
CBS_TIMEOUT=30
CBS_MAX_RETRIES=3

# Telemetry
ENABLE_METRICS=true
LOG_LEVEL=INFO
```

### Step 4: Run the Adapter

```bash
# Export environment variables
export $(cat .env | xargs)

# Run the adapter
java -jar target/obp-rabbit-cats-adapter-1.0.0-SNAPSHOT.jar
```

You should see:

```
===============================================================
     OBP Rabbit Cats Adapter                              
     Version 1.0.0-SNAPSHOT                               
===============================================================

[CONFIG] Loading configuration...
[OK] Configuration loaded
   HTTP Server: 0.0.0.0:8090
   RabbitMQ: localhost:5672
   Request Queue: obp.request
   Response Queue: obp.response
   CBS Base URL: http://localhost:9000

[CONFIG] Validating configuration...
[OK] Configuration valid

[TELEMETRY] Initialized (Console mode)

[CBS] Initializing CBS connector...
[OK] CBS Connector: Mock-CBS-Connector v1.0.0

[HEALTH] Checking CBS health...
[OK] CBS is healthy

[STARTUP] Starting services...

[HTTP] Discovery server started at http://0.0.0.0:8090
[INFO] Visit http://localhost:8090 to see service info

[RabbitMQ] Connected to localhost:5672
[Queue] Request: obp.request
[Queue] Response: obp.response
[OK] Consuming from queue: obp.request
```

## Step 5: Test the Adapter

### Option A: Use the Discovery Web UI

1. Open your browser to http://localhost:8090

2. You'll see the OBP Adapter Discovery page with:
   - Health & Status endpoints
   - RabbitMQ connection info
   - CBS configuration
   - Test Messages section

3. Click the **"Send Get Adapter Info"** button in the Test Messages card

4. You should see:
   - Success message with correlation ID
   - Message processed in the adapter logs
   - Response returned from Mock CBS

5. Check the adapter console logs:

```
[TEST] Sent message: obp.getAdapterInfo with correlation ID: 123e4567-e89b-12d3-a456-426614174000
[123e4567-e89b-12d3-a456-426614174000] Processing: obp.getAdapterInfo
[123e4567-e89b-12d3-a456-426614174000] [OK] Completed in 45ms
```

### Option B: Use RabbitMQ Management UI

1. Open http://localhost:15672 (guest/guest)

2. Go to **Queues** tab

3. You should see:
   - `obp.request` queue
   - `obp.response` queue

4. Click on `obp.request` queue

5. Under **Publish message**, paste this JSON:

```json
{
  "messageType": "obp.getAdapterInfo",
  "data": {},
  "outboundAdapterCallContext": {
    "correlationId": "test-123",
    "sessionId": "test-session",
    "generalContext": {}
  }
}
```

6. Click **Publish message**

7. Go to `obp.response` queue and click **Get messages**

8. You should see the response:

```json
{
  "status": {
    "errorCode": ""
  },
  "data": {
    "name": "Mock-CBS-Connector",
    "version": "1.0.0",
    "git_commit": "mock-commit-123",
    "date": "2025-01-14"
  },
  "inboundAdapterCallContext": {
    "correlationId": "test-123",
    "sessionId": "test-session"
  }
}
```

### Option C: Health Check Endpoints

```bash
# Health check
curl http://localhost:8090/health

# Readiness check
curl http://localhost:8090/ready

# Service info
curl http://localhost:8090/info
```

## Step 6: Monitor the Adapter

### Console Logs

The adapter logs all activity to the console with correlation IDs:

```
[INFO][CID: test-123] Message received: type=obp.getAdapterInfo
[INFO][CID: test-123] CBS operation started: getAdapterInfo
[INFO][CID: test-123] CBS operation success: getAdapterInfo duration=10ms
[INFO][CID: test-123] Response sent: type=response success=true
```

### RabbitMQ Management UI

Monitor queue metrics at http://localhost:15672:
- Message rates
- Queue depth
- Consumer count
- Connection status

## Understanding the Message Flow

```
Browser/Client
    |
    | POST /test/adapter-info
    v
Discovery Server (HTTP)
    |
    | Publish to obp.request queue
    v
RabbitMQ
    |
    | Consume from obp.request
    v
RabbitMQ Consumer
    |
    | Parse & route message
    v
CBS Connector (Mock)
    |
    | Process business logic
    v
CBS Response
    |
    | Publish to obp.response queue
    v
RabbitMQ
    |
    | (OBP-API would consume this)
    v
Response Queue
```

## What's the Difference?

### Using `build_and_run.sh`:
- ✅ Automatically loads `.env` file
- ✅ Checks if RabbitMQ is reachable
- ✅ Shows configuration before starting
- ✅ Handles environment variable export
- ✅ Finds the JAR automatically
- ✅ Cleaner output with no emojis

### Using `start_rabbitmq.sh`:
- ✅ Checks if Docker is installed and running
- ✅ Reuses existing RabbitMQ container if present
- ✅ Waits for RabbitMQ to be fully ready
- ✅ Shows all connection information
- ✅ Provides useful management commands

### Manual Steps:
- ⚠️ More control but more typing
- ⚠️ Need to remember all commands
- ⚠️ Must manually export environment variables
- ⚠️ Good for understanding what's happening under the hood

**Recommendation:** Use the scripts for daily work, use manual steps when learning or troubleshooting.

## Supported Test Messages

The Mock CBS Connector supports these message types:

- `obp.getAdapterInfo` - Get adapter information
- `obp.getBank` - Get bank details
- `obp.getBankAccount` - Get account information
- `obp.getTransaction` - Get transaction details
- `obp.getTransactions` - Get transaction list
- `obp.checkFundsAvailable` - Check available funds
- `obp.makePayment` - Make a payment

## Troubleshooting

### Connection refused to RabbitMQ

```
[ERROR] RabbitMQ error: Connection refused
```

**Solution:** Make sure RabbitMQ is running:
```bash
docker ps | grep rabbitmq
# or
sudo service rabbitmq-server status
```

### Port 8090 already in use

```
[ERROR] Failed to bind to 0.0.0.0:8090
```

**Solution:** Change the HTTP port:
```bash
export HTTP_PORT=8081
```

### Queues not declared

**Solution:** The adapter automatically declares queues on startup. Check RabbitMQ management UI to verify.

### Message not processed

Check:
1. Message JSON format is correct
2. `messageType` field exists
3. `outboundAdapterCallContext` has `correlationId`
4. Queue names match configuration

## Next Steps

1. **Implement Your CBS Connector**: Replace `MockCBSConnector` with your bank's implementation
   - See `HOW-BANKS-USE-THIS.md`
   - See `ARCHITECTURE.md`

2. **Add More Message Types**: Implement additional OBP message handlers in your connector

3. **Configure Production Settings**:
   - Change RabbitMQ credentials
   - Set up SSL/TLS
   - Configure retry policies
   - Set up proper logging

4. **Deploy**: See `README.md` for Docker and Kubernetes deployment options

## Support

- Architecture: `ARCHITECTURE.md`
- Bank Integration: `HOW-BANKS-USE-THIS.md`
- Full README: `README.md`
- OBP Wiki: https://github.com/OpenBankProject/OBP-API/wiki