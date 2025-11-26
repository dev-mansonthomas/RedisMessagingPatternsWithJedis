# DLQ Configuration Guide

## Overview

The DLQ Configuration component allows you to dynamically configure the Dead Letter Queue parameters, including the **Max Retry** (maxDeliveries) setting, without restarting the Spring Boot application.

## Features

✅ **Dynamic Configuration**: Update DLQ parameters at runtime  
✅ **Max Retry Control**: Configure how many delivery attempts before sending to DLQ  
✅ **Stream Configuration**: Set stream names, consumer groups, and consumer names  
✅ **Batch Processing**: Configure batch size and idle time thresholds  
✅ **Persistent Settings**: Configuration is stored in memory and applied to all future operations  

## Configuration Parameters

### Stream Configuration

| Parameter | Description | Default Value |
|-----------|-------------|---------------|
| **Main Stream Name** | Name of the primary Redis Stream | `test-stream` |
| **DLQ Stream Name** | Name of the Dead Letter Queue stream | `test-stream:dlq` |
| **Consumer Group** | Consumer group name for coordinated consumption | `test-group` |
| **Consumer Name** | Identifier for this consumer instance | `consumer-1` |

### DLQ Parameters

| Parameter | Description | Default Value | Range |
|-----------|-------------|---------------|-------|
| **Max Retry (Max Deliveries)** | Number of delivery attempts before routing to DLQ | `2` | 1-100 |
| **Min Idle Time (ms)** | Minimum time a message must be idle before reclaim | `5000` (5s) | 0+ |
| **Batch Size** | Maximum messages to process per claim operation | `100` | 1-1000 |

## How It Works

### Architecture

```
User Interface (Angular)
    ↓ HTTP POST /api/dlq/config
DLQController
    ↓
DLQConfigService (stores config in memory)
    ↓
Future claim_or_dlq operations use the new maxDeliveries value
```

### Max Retry Behavior

The **Max Retry** parameter controls when messages are sent to the DLQ:

1. **Message is consumed** but not acknowledged (delivery count = 1)
2. **Message becomes idle** (exceeds minIdleMs threshold)
3. **claim_or_dlq is called**:
   - If `deliveryCount < maxDeliveries`: Message is **reclaimed** for reprocessing
   - If `deliveryCount >= maxDeliveries`: Message is **sent to DLQ** and acknowledged

**Example with maxDeliveries = 3:**
- Delivery 1: Message consumed, not ACKed → becomes pending
- Delivery 2: Message reclaimed (2 < 3) → still pending
- Delivery 3: Message reclaimed (3 >= 3) → **sent to DLQ**

## Usage

### Via Web Interface

1. Navigate to the **DLQ** page in the Angular application
2. Locate the **DLQ Configuration** card
3. Adjust the **Max Retry** slider or input field
4. Configure other parameters as needed
5. Click **Save Configuration**
6. Confirmation message will appear

### Via REST API

**Save Configuration:**
```bash
curl -X POST http://localhost:8080/api/dlq/config \
  -H "Content-Type: application/json" \
  -d '{
    "streamName": "test-stream",
    "dlqStreamName": "test-stream:dlq",
    "consumerGroup": "test-group",
    "consumerName": "consumer-1",
    "minIdleMs": 5000,
    "count": 100,
    "maxDeliveries": 3
  }'
```

**Get Configuration:**
```bash
curl http://localhost:8080/api/dlq/config?streamName=test-stream
```

**Get All Configurations:**
```bash
curl http://localhost:8080/api/dlq/config/all
```

## Testing the Configuration

### Step 1: Set Max Retry to 3

Update the configuration to allow 3 delivery attempts:

```bash
curl -X POST http://localhost:8080/api/dlq/config \
  -H "Content-Type: application/json" \
  -d '{"streamName":"test-stream","dlqStreamName":"test-stream:dlq","consumerGroup":"test-group","consumerName":"consumer-1","minIdleMs":5000,"count":100,"maxDeliveries":3}'
```

### Step 2: Add a Test Message

```bash
redis-cli XADD test-stream * type order order_id 12345 status pending
```

### Step 3: Simulate Failures

Consume the message without acknowledging it 3 times, and it will be sent to the DLQ on the 3rd attempt.

## Implementation Details

### Backend Components

- **`DLQConfigRequest.java`**: DTO for configuration requests
- **`DLQConfigService.java`**: Service that stores configurations in memory
- **`DLQController.java`**: REST endpoints for config management

### Frontend Components

- **`dlq-config.component.ts`**: Angular component with form UI
- **`dlq.component.ts`**: Main DLQ page that includes the config component

### Configuration Storage

Configurations are stored in a `ConcurrentHashMap` in memory:
- **Key**: Stream name
- **Value**: `DLQConfigRequest` object

⚠️ **Note**: Configurations are **not persisted** to disk. They will be lost on application restart.

## Future Enhancements

- [ ] Persist configurations to Redis or database
- [ ] Configuration history and audit log
- [ ] Per-consumer configuration overrides
- [ ] Configuration validation with real-time feedback
- [ ] Import/export configuration profiles

