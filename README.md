# 📱 DTN Messenger - Delay-Tolerant Network Messenger for Android

A decentralized peer-to-peer messaging application for Android that enables offline communication through Bluetooth and WiFi Direct mesh networks. The app implements epidemic and spray-and-wait routing protocols for efficient multi-hop message propagation without requiring internet connectivity.

## Features

### Core Functionality
- **Dual Protocol Support**: Seamless switching between Bluetooth and WiFi Direct
- **Mesh Networking**: Multi-hop message relay across connected devices
- **Offline Messaging**: Complete independence from internet or cellular networks
- **Device Discovery**: Automatic peer detection and connection management
- **Persistent Storage**: Local message history with Room database

### Routing Protocols
- **Epidemic Routing**: Maximum delivery probability through flooding
- **Spray-and-Wait**: Optimized bandwidth usage with controlled message copies
- **Dynamic Protocol Switching**: Real-time protocol changes without reconnection

### Technical Highlights
- MVVM architecture for separation of concerns
- LiveData for reactive UI updates
- Repository pattern for data access
- Custom network managers for Bluetooth/WiFi Direct
- Broadcast receivers for network state monitoring
- Message deduplication and TTL management

## Technologies Used

- **Language**: Java
- **Architecture**: MVVM (Model-View-ViewModel)
- **Database**: Room Persistence Library
- **UI Components**: RecyclerView, CardView, Material Design
- **Networking**: Bluetooth Classic, WiFi Direct (WiFi P2P)
- **Concurrency**: ExecutorService for background tasks
- **Lifecycle**: Android Jetpack Lifecycle components

## Architecture
```
app/
├── model/
│   ├── data/           # Data models (Message, Friend)
│   └── repository/     # Database repositories
├── viewmodel/          # MainViewModel (business logic)
├── view/              # MainActivity (UI)
├── network/
│   ├── manager/       # BluetoothManager, WifiDirectManager, ConnectionManager
│   └── routing/       # EpidemicRouting, SprayAndWaitRouting
├── database/          # Room database setup
└── utils/             # Helper classes
```

## Routing Algorithms

### Epidemic Routing
- Floods messages to all available neighbors
- Maximum delivery probability
- Higher bandwidth consumption
- Best for: Dense networks, critical messages

### Spray-and-Wait Routing
- Binary spray phase: Distributes limited message copies (default: 6)
- Wait phase: Direct delivery only
- Reduced network overhead
- Best for: Resource-constrained scenarios, sparse networks

## Setup & Installation

### Prerequisites
- Android Studio Arctic Fox or later
- Minimum SDK: API 21 (Android 5.0)
- Target SDK: API 33 (Android 13)
- Physical Android devices (emulator doesn't support Bluetooth/WiFi Direct)

### Installation Steps

1. **Clone the repository**
```bash
git clone https://github.com/Akrittt/Delay-Tolerant-Multi-Hop-Messaging-Application.git
cd Delay-Tolerant-Multi-Hop-Messaging-Application
```

2. **Open in Android Studio**
   - File → Open → Select project directory
   - Wait for Gradle sync to complete

3. **Configure permissions**
   - App requires location, Bluetooth, and WiFi permissions
   - Grant all permissions when prompted on first launch

4. **Build and Run**
   - Connect physical Android device via USB
   - Enable USB debugging in Developer Options
   - Click Run (Shift + F10)

## Usage

### Getting Started
1. **Launch App**: Open on multiple devices
2. **Select Protocol**: Choose Bluetooth or WiFi Direct from dropdown
3. **Discover Devices**: Tap "Discover Devices" button
4. **Connect**: Select discovered peers from list
5. **Send Messages**: Type message and tap send

### Switching Protocols
- Use dropdown menu to switch between protocols
- Active connections remain stable during switch
- New connections use selected protocol

### Viewing Messages
- Chat view shows conversation history
- Swipe to refresh message list
- Messages persist across app restarts

## Network Specifications

| Protocol | Range | Data Rate | Power |
|----------|-------|-----------|-------|
| Bluetooth Classic | ~30m | 2-3 Mbps | Low |
| WiFi Direct | ~150m | 250 Mbps | Medium |

## Permissions Required
```xml
<!-- Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- WiFi Direct -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

## Key Classes

### Network Layer
- **BluetoothManager**: Handles Bluetooth discovery and connections
- **WifiDirectManager**: Manages WiFi P2P operations
- **ConnectionManager**: Coordinates network operations across protocols

### Routing Layer
- **EpidemicRouting**: Implements flooding-based message dissemination
- **SprayAndWaitRouting**: Implements binary spray routing algorithm

### Data Layer
- **MessageRepository**: CRUD operations for messages
- **FriendRepository**: Friend list management
- **AppDatabase**: Room database configuration

### ViewModel
- **MainViewModel**: Business logic, network coordination, UI state management

## Testing

### Test on Physical Devices
1. Install app on 5+ Android devices
2. Enable Bluetooth/WiFi on all devices
3. Start app and select same protocol
4. Discover and connect devices
5. Send messages and verify multi-hop relay

### Test Scenarios
- ✅ Direct message delivery (1 hop)
- ✅ Multi-hop relay (2+ hops)
- ✅ Protocol switching during active session
- ✅ Message persistence after app restart
- ✅ Duplicate message detection
- ✅ TTL expiration handling

## Limitations

- Maximum of 7 Bluetooth connections per device (Android limitation)
- WiFi Direct supports 1 group owner connection at a time
- No end-to-end encryption (future enhancement)
- Requires location services for device discovery
- Messages expire after 10 hops (configurable)

## Future Enhancements

- [ ] End-to-end encryption for secure messaging
- [ ] File transfer support (images, documents)
- [ ] Group chat functionality
- [ ] Adaptive routing based on network conditions
- [ ] BLE in place of classic Bluetooth


## Acknowledgments

- Android Bluetooth API documentation
- WiFi Direct P2P framework
- Delay Tolerant Networking research papers
- Epidemic and Spray-and-Wait routing protocols

## Contact

**Akrit Gupta**  
Email: akritttgupta@gmail.com <br>
GitHub: [@Akrittt]((https://github.com/Akrittt))

---

**Note**: This app is designed for educational purposes and research in delay-tolerant networking. For production use, implement proper security measures including message encryption and user authentication.










