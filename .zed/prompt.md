# OBP Rabbit Cats Adapter - AI Assistant Rules

## ⚠️ CRITICAL: Message Structure Source of Truth

**The OBP Message Docs API is the absolute SOURCE OF TRUTH for all message structures.**

### What This Means

The endpoint `/obp/v6.0.0/message-docs/rabbitmq_vOct2024` returns message schemas with:
- `example_outbound_message` - Messages FROM OBP-API TO this adapter
- `example_inbound_message` - Messages FROM this adapter TO OBP-API

These examples define the **correct and authoritative** message structure.

### Rules When Working With Messages

1. **ALWAYS** adapt adapter code to match the Message Docs schemas
2. **NEVER** suggest modifying message structures to fit the adapter
3. **NEVER** assume the adapter's current code is correct if it conflicts with Message Docs
4. If there's a discrepancy, the Message Docs API is right, the adapter code is wrong

### Where These Schemas Are Used

- **Displayed in UI**: `DiscoveryServer.scala` shows them as "Expected Outbound (source of truth)" and "Expected Inbound (source of truth)"
- **Fetched at**: `DiscoveryServer.scala` lines 220-222
  ```scala
  "outboundExample" -> msgSchema.hcursor.downField("example_outbound_message").focus
  "inboundExample" -> msgSchema.hcursor.downField("example_inbound_message").focus
  ```
- **JavaScript variables**: `expectedOutbound` and `expectedInbound` (lines 501-502)

### Key Files for Message Handling

- **`src/main/scala/com/tesobe/obp/adapter/models/OBPModels.scala`**
  - Defines message case classes (OutboundMessage, InboundMessage, etc.)
  - These MUST match the Message Docs schemas

- **`src/main/scala/com/tesobe/obp/adapter/messaging/RabbitMQConsumer.scala`**
  - Parses incoming messages (line 151: `parseOutboundMessage`)
  - Constructs response messages (line 164: `buildInboundMessage`)
  - Sends responses (line 193: `sendResponse`)

- **`src/main/scala/com/tesobe/obp/adapter/http/DiscoveryServer.scala`**
  - Fetches schemas from Message Docs API (line 147: `sendTestMessage`)
  - Displays schemas in web UI (lines 456-464)

### Debugging Workflow for Message Issues

1. **First**: Check what the Message Docs API actually says
2. **Second**: Compare adapter code against those schemas
3. **Third**: Fix the adapter code to match
4. **Never**: Suggest changing the message format or question the Message Docs

### Architecture Context

This adapter sits between:
- **OBP-API** (OpenBankProject) ← uses Message Docs format (SOURCE OF TRUTH)
- **CBS** (Core Banking System) ← adapter translates to CBS-specific format

The adapter's job is to translate between these systems. OBP's format is fixed and authoritative.

## Project Structure

- **Scala/Cats Effect project** using functional programming patterns
- **RabbitMQ messaging** for async communication with OBP-API
- **HTTP4s server** for discovery/testing UI at `http://localhost:8082`
- **JSON handling** via Circe library
- **Configuration** in `application.conf`

## Testing

- Web UI available at `http://localhost:8082` for testing message flows
- Test endpoint: `POST /test/adapter-info` sends a test message
- Messages can be monitored via RabbitMQ management UI

## Code Style

- Functional programming with IO monad (Cats Effect)
- Immutable case classes for data
- Pattern matching for flow control
- Circe for JSON encoding/decoding
- Avoid side effects outside IO