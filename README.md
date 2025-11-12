# 📱 DTN Messenger - Delay-Tolerant Network Messenger for Android

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)

A Delay-Tolerant Networking (DTN) application for Android that enables peer-to-peer messaging through mesh networks using Wi-Fi Direct and Bluetooth.

<p align="center">
  <img src="screenshots/main_screen.png" width="250" />
  <img src="screenshots/chat_screen.png" width="250" />
  <img src="screenshots/connection.png" width="250" />
</p>

## 🎯 Overview
  This application creates opportunistic mesh networks between Android devices, allowing messages to be delivered even when direct connectivity isn't available. Messages hop through intermediate devices to reach their destination.

  Key Features
  -**Dual Transport**: Wi-Fi Direct and Bluetooth connectivity
  -**Mesh Networking**: Automatic multi-hop message forwarding
  -**Two Routing Protocols**: Epidemic Routing and Spray-and-Wait
  -**End-to-End Encryption**: AES-256 encryption with message authentication
  -**Automatic Discovery**: Continuous peer discovery and connection
  -**Message Acknowledgment**: Delivery confirmation with ACK mechanism



## 🎯 Use Cases

- **Disaster Recovery**: Emergency communication when infrastructure is destroyed
- **Remote Areas**: Communication in locations without network coverage
- **Privacy-Focused**: Direct device-to-device communication without intermediaries
- **Research**: DTN protocol implementation and performance analysis
- **Tactical Networks**: Military and emergency services communication

## 🏗️ Architecture

### Layer Overview
```
┌─────────────────────────────────────────────┐
│           User Interface Layer              │
│         (MainActivity + UI)                 │
└─────────────────────────────────────────────┘
                    ↓↑
┌─────────────────────────────────────────────┐
│         Routing Protocol Layer              │
│  (Epidemic Routing / Spray-and-Wait)        │
└─────────────────────────────────────────────┘
                    ↓↑
┌─────────────────────────────────────────────┐
│          Network Transport Layer            │
│    (Wi-Fi Direct / Bluetooth Mesh)          │
└─────────────────────────────────────────────┘
                    ↓↑
┌─────────────────────────────────────────────┐
│        Data Persistence Layer               │
│      (Room Database + SQLite)               │
└─────────────────────────────────────────────┘
```

### 📦 Core Components

1. Network Layer (com.example.dtn.network)
  Wi-Fi Direct
    - ServerThread: Accepts incoming Wi-Fi Direct connections
    - ClientThread: Initiates outgoing Wi-Fi Direct connections
    - WifiDirectBroadcastReceiver: Handles Wi-Fi Direct events

  Bluetooth Mesh
    - BluetoothServerThread: Accepts multiple simultaneous Bluetooth connections (up to 7)
    - BluetoothClientThread: Manages outgoing Bluetooth connections
    - BluetoothBroadcastReceiver: Handles Bluetooth discovery and pairing events

2. Routing Layer (com.example.dtn.routing)
  Epidemic Routing
    - Floods messages to all encountered peers
    - Maximizes delivery probability
    - Higher network overhead

  Spray-and-Wait Routing
    - Distributes limited message copies (initial count: 6)
    - Spray Phase: Splits copies among peers (binary spray)
    - Wait Phase: Holds message until destination is met
    - Lower network overhead, controlled replication

3. Data Layer (com.example.dtn.data)
  Entities
    - Message: Stores DTN messages with routing metadata
         - message_id, source_id, destination_id
         - encrypted_payload, checksum
         - hop_count, copy_count, ttl_timestamp
         - is_delivered (delivery tracking)

    - Friend: Stores trusted peers for routing decisions
         - device_id, friendly_name
         - last_encountered_timestamp

DAOs (Data Access Objects)
    - MessageDao: CRUD operations for messages
    - FriendDao: CRUD operations for friends

4. Security Layer (com.example.dtn.security)

  CryptoUtils:
      - AES-256-CBC encryption
      - HMAC-SHA256 message authentication
      - Checksum validation

5. Utilities (com.example.dtn.utils)
    - Logger: File-based logging with rotation (5MB per file, max 3 files)

## 🔄 Message Flow
**Sending a Message**
```
1. User types message in UI
          ↓
2. Encrypt plaintext (AES-256)
          ↓
3. Generate checksum (SHA-256)
          ↓
4. Create Message object
   - Set source_id (own device)
   - Set destination_id (recipient)
   - Set TTL (2 hours default)
   - Set copy_count (protocol-specific)
          ↓
5. Store in local database
          ↓
6. Transmit to connected peers
   - Via BluetoothServerThread (broadcast to all clients)
   - Via BluetoothClientThread (send to server)
          ↓
7. Update UI with delivery status
```
**Receiving a Message**
```
1. Message received from network thread
          ↓
2. Handler passes to main thread
          ↓
3. Validate checksum (detect tampering)
          ↓
4. Check for duplicates (by message_id)
          ↓
5. Check destination:
   
   If FOR ME:
     - Decrypt payload
     - Display in chat
     - Mark as delivered
     - Send ACK back to sender
   
   If NOT FOR ME:
     - Increment hop_count
     - Check hop limit (max 15)
     - Forward to connected peers
          ↓
6. Store/Update in database
```
**Forwarding Logic (Mesh Routing)**
```
1. Peer connection established
          ↓
2. Trigger forwarding logic (after 2s delay)
          ↓
3. Query database for messages to forward:
   - TTL not expired
   - Not yet delivered
          ↓
4. Apply routing protocol:
   
   EPIDEMIC:
     - Send ALL messages to peer
   
   SPRAY-AND-WAIT:
     - If copy_count > 1: Split copies (spray phase)
     - If copy_count = 1: Send only to destination (wait phase)
          ↓
5. Transmit messages
          ↓
6. Update hop_count and copy_count in database
```
### 🌐 Connection Management
**Bluetooth Mesh (Primary)**
```
Device A (Server + Client)  ←→  Device B (Server + Client)
    ↓                               ↓
Device C (Server + Client)  ←→  Device D (Server + Client)
```

**Wi-Fi Direct (Fallback)**
```
Group Owner (Server)  ←→  Client
```

###🔐 Security Architecture
**Encryption Flow**
```
Plaintext Message
      ↓
Generate random IV (16 bytes)
      ↓
Encrypt with AES-256-CBC
      ↓
Generate HMAC-SHA256 (32 bytes)
      ↓
Combine: [IV | Ciphertext | HMAC]
      ↓
Store/Transmit
```

**Decryption Flow**
```
Received: [IV | Ciphertext | HMAC]
      ↓
Extract components
      ↓
Validate HMAC (prevent tampering)
      ↓
Decrypt with AES-256-CBC using IV
      ↓
Return plaintext
```
### Technology Stack

- **Language**: Java
- **Minimum SDK**: API 24 (Android 7.0 Nougat)
- **Target SDK**: API 34 (Android 14)
- **Build System**: Gradle
- **Database**: Room (SQLite wrapper)
- **Networking**: Wi-Fi Direct (Wi-Fi Peer-to-Peer)
- **Architecture**: MVVM-inspired with Repository pattern

###📊 Database Schema

#### Messages Table
```
CREATE TABLE messages (
    message_id TEXT PRIMARY KEY,
    message_type INTEGER,           -- 0=DATA, 1=ACK
    source_id TEXT,
    destination_id TEXT,
    encrypted_payload BLOB,
    checksum TEXT,
    priority INTEGER,               -- 0=NORMAL, 1=HIGH
    ttl_timestamp INTEGER,
    hop_count INTEGER,
    copy_count INTEGER,
    is_delivered INTEGER            -- 0=false, 1=true
);
```


#### Friends Table
```
CREATE TABLE friends (
    device_id TEXT PRIMARY KEY,
    friendly_name TEXT,
    last_encountered_timestamp INTEGER
);
```


### 🚀 Application Lifecycle

**Initialization (onCreate)**
```
1. Load saved preferences (protocol, transport)
      ↓
2. Initialize UI components
      ↓
3. Initialize Room database
      ↓
4. Initialize device ID (Bluetooth name or Android ID)
      ↓
5. Initialize logger and handler
      ↓
6. Initialize Wi-Fi Direct and Bluetooth
      ↓
7. Request permissions
      ↓
8. Start transport layer (Wi-Fi Direct or Bluetooth)
      ↓
9. Begin continuous discovery
      ↓
10. Load existing messages from database
```

** Runtime Operation **
```
┌─────────────────────────────────────────┐
│  Continuous Discovery (every 120s)      │
│  - Discover new peers                   │
│  - Auto-connect to friends              │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│  Connection Established                 │
│  - Register in active connections       │
│  - Trigger forwarding logic             │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│  Message Exchange                       │
│  - Receive messages                     │
│  - Forward messages (routing protocol)  │
│  - Send ACKs for delivered messages     │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│  Connection Lost                        │
│  - Clean up dead connection             │
│  - Maintain mesh density                │
│  - Retry discovery if needed            │
└─────────────────────────────────────────┘
```










