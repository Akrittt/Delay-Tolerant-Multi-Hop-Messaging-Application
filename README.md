DTN Messenger
[![Android](https://img.shields.io/badge/Platformg.shields.io/badge/API-21%2B-brightgreen.svg?://img.shields.io A research-focused Delay-Tolerant Network (DTN) messaging application for Android using Wi-Fi Direct P2P communication, implementing and comparing Epidemic and Spray-and-Wait routing protocols.

📋 Table of Contents
Overview

Features

Architecture

Installation

Usage

Routing Protocols

Project Structure

Research Metrics

Technologies Used

Known Limitations

Contributing

License

🌟 Overview
DTN Messenger is an Android application designed for academic research to evaluate the performance of different routing protocols in Delay-Tolerant Networks. The app enables peer-to-peer communication without internet or cellular infrastructure using Wi-Fi Direct, making it suitable for scenarios like disaster recovery, remote areas, or crowded events where traditional networks fail.

Research Objective
To implement and compare the performance of Epidemic Routing and Spray-and-Wait routing protocols in a real-world mobile DTN environment, measuring:

Message delivery ratio

Average delay

Network overhead

Buffer occupancy

✨ Features
Core Functionality
🔗 Wi-Fi Direct P2P Communication - Direct device-to-device connections without infrastructure

🔒 End-to-End Encryption - AES-128-CBC encryption for message confidentiality

✅ Message Acknowledgements - Delivery confirmation system

📊 Dual Routing Protocols - Runtime switching between Epidemic and Spray-and-Wait

💾 Store-Carry-Forward - Messages stored locally and forwarded opportunistically

📝 Comprehensive Logging - Detailed event logs for research analysis

👥 Friend Management - Multi-hop routing based on encounter history

⏱️ Message TTL - Time-to-live mechanism to prevent indefinite message propagation

🎯 Priority Messaging - High/Normal priority levels for message forwarding

UI Features
📱 Clean Material Design interface

🔄 Real-time peer discovery

💬 Chat-style message display with delivery status

🎨 Color-coded connection states (Red/Yellow/Green)

🔀 Easy protocol switching via menu

📋 Friends list management

🏗️ Architecture
Design Pattern
The app uses Strategy Pattern for routing protocols, enabling runtime switching without code modification.
┌─────────────┐
│ MainActivity │
└──────┬──────┘
       │
       ├──── WifiP2pManager (Peer Discovery)
       │
       ├──── ServerThread/ClientThread (P2P Connection)
       │
       ├──── RoutingProtocol (Strategy Interface)
       │      ├── EpidemicRouting
       │      └── SprayAndWaitRouting
       │
       ├──── Room Database (Messages & Friends)
       │      ├── MessageDao
       │      └── FriendDao
       │
       └──── Logger (Event Recording)

       
Key Components

Component            |  Purpose                                                 
---------------------+----------------------------------------------------------
MainActivity         |  UI controller, Wi-Fi Direct management, message handling
ServerThread         |  Handles incoming connections (group owner)              
ClientThread         |  Handles outgoing connections (client)                   
EpidemicRouting      |  Flooding-based routing with unlimited replication       
SprayAndWaitRouting  |  Quota-based routing with controlled message copies      
CryptoUtils          |  AES encryption/decryption and checksum validation       
Logger               |  Timestamped event logging to internal storage           
Room Database        |  Persistent storage for messages and friends             

📁 Project Structure
app/src/main/java/com/example/dtn/
│
├── MainActivity.java              # Main UI controller
│
├── network/
│   ├── ServerThread.java         # Server-side P2P connection
│   ├── ClientThread.java         # Client-side P2P connection
│   └── WifiDirectBroadcastReceiver.java  # Wi-Fi Direct events
│
├── routing/
│   ├── RoutingProtocol.java      # Strategy interface
│   ├── EpidemicRouting.java      # Epidemic implementation
│   └── SprayAndWaitRouting.java  # Spray-and-Wait implementation
│
├── data/
│   ├── Message.java              # Message entity
│   ├── Friend.java               # Friend entity
│   ├── MessageDao.java           # Message database operations
│   ├── FriendDao.java            # Friend database operations
│   └── AppDatabase.java          # Room database instance
│
├── security/
│   └── CryptoUtils.java          # Encryption utilities
│
└── utils/
    └── Logger.java               # Event logging system

res/
├── layout/
│   └── activity_main.xml         # Main UI layout
├── menu/
│   └── main_menu.xml             # Options menu
├── values/
│   ├── colors.xml                # Color definitions
│   ├── strings.xml               # String resources
│   ├── arrays.xml                # Priority options
│   └── themes.xml                # App theme
