# Airport Blackout Guide - HackUPC 2025

**Version:** 1.0.0
**Event:** HackUPC 2025 (May 2-4, 2025, Barcelona)
**Challenge:** Vueling - Enhancing Passenger Experience During Disruptions

[![HackUPC](https://img.shields.io/badge/Hackathon-HackUPC%202025-blue.svg)](https://hackupc.com/)
[![Vueling Challenge](https://img.shields.io/badge/Challenge-Vueling-yellow.svg)]()

---

## Introduction

Navigating large, unfamiliar airports can be stressful, but it becomes significantly harder during power outages or network failures when digital signages go dark and online maps become inaccessible. The Airport Blackout Guide is an Android application prototype developed during HackUPC 2025 to address this Vueling challenge, providing reliable offline navigation assistance when passengers need it most.

This app ensures passengers can still find their gate, essential facilities, or exits even without an internet connection or relying on airport infrastructure.

## Demo

**Login and Register pages**
![Screenshot_20250504-041158](https://github.com/user-attachments/assets/06494318-d9c3-4bd4-834c-f7215f5bdd4e)

**Interactive Map with automatic Pathfinding (with route changes), Voice Commands and much more!**
![Screenshot_20250504-041213](https://github.com/user-attachments/assets/d03d493d-8344-48a9-94e4-c4c7989fa1c1)
![Screenshot_20250504-041230](https://github.com/user-attachments/assets/a9f16752-3287-4fbb-bec2-a3c664c17b88)

**Multiple Maps Supported**
![Screenshot_20250504-041243](https://github.com/user-attachments/assets/fb54a8c6-aca6-4e7e-aee6-8f606694ce78)

**Ticket Management**
![Screenshot_20250504-041254](https://github.com/user-attachments/assets/633618e2-5a2b-440d-8814-8fd73e9ebda1)

**Tickets discoverability and purchase**
![Screenshot_20250504-041304](https://github.com/user-attachments/assets/59df47f1-1046-44dd-a0ce-ec1c902a48e6)

**Bluetooth chat integrated for communication with companions even without internet and through walls**
![Screenshot_20250504-041315](https://github.com/user-attachments/assets/df8cbc62-fa62-464b-9756-20fda4601686)
![Screenshot_20250504-041401](https://github.com/user-attachments/assets/57621bf7-67fe-4327-83e1-ad90d267b107)
![Screenshot_20250504-041406](https://github.com/user-attachments/assets/5c161b7e-0115-4a29-8d29-9f53b8c0eb82)
![Screenshot_20250504-041420](https://github.com/user-attachments/assets/9acf5599-b021-4acb-b1fc-b403361cfe31)


## Features (v1.0.0)

* **✈️ Offline Map Navigation:** Navigate detailed, multi-floor airport maps (BCN T1 & JFK T4 included) completely offline using pre-loaded map data (JSON format).
* **🗺️ Map Interaction & Selection:**
    * Visualize airport layout with nodes (gates, restrooms, exits, etc.) and paths.
    * Tap on map nodes to view detailed information.
    * Switch between different loaded airport maps via a dropdown menu in the app bar.
* **🗣️ Voice & Text Commands:** Use natural language to ask for directions, update your location, or query distances (e.g., "How do I get to my gate?", "I am at Security Checkpoint 1", "How far is the nearest bathroom?").
* **🤖 Simulated On-Device AI:** Features a mocked local LLM interface that processes user requests locally, determining intent and extracting parameters to trigger app functions (pathfinding, location updates) without needing cloud connectivity.
* **➡️ A* Pathfinding:** Calculates efficient walking routes between points within the airport, considering stairs/elevators.
* **📍 GPS Localization:** Utilizes device GPS to provide an estimated current location mapped to the nearest node on the airport map (Note: GPS to indoor map conversion is simplified).
* **🔊 Turn-by-Turn Voice Guidance:** Receive clear audio instructions via Text-to-Speech (TTS) guiding you along your calculated path.
* **🔌 Blackout Mode:**
    * Automatically detects loss of network connectivity.
    * Visually indicates blackout mode is active.
    * Displays the time the blackout was first detected.
* **🎟️ Mock User & Tickets:** Includes basic Login/Register screens (mocked logic) and a Vueling-themed Tickets screen displaying mock purchased and available flight data. The user's assigned gate from mock tickets is used for "go to my gate" commands.
* **💬 Bluetooth Companion Chat (Experimental):** Enables simple, bidirectional, text-only chat between two devices via Bluetooth Classic (SPP). Uses in-app device discovery and connection initiation, allowing companions on the same flight to communicate directly without network infrastructure. Includes username exchange upon connection.

## Tech Stack

* **Platform:** Android
* **Language:** Kotlin
* **UI:** Jetpack Compose (Material 3)
* **Navigation:** Jetpack Navigation Compose
* **State Management:** Kotlin Flows & StateFlow (in ViewModels)
* **Asynchronicity:** Kotlin Coroutines
* **Pathfinding:** A* Algorithm (custom implementation)
* **Maps:** Custom node/edge graph rendering on Compose Canvas from JSON data.
* **Location:** Android Location Services (FusedLocationProviderClient)
* **Voice/Text:** Android SpeechRecognizer (ASR), Android TextToSpeech (TTS)
* **Connectivity:** Android ConnectivityManager
* **Networking:** Bluetooth Classic (SPP Sockets) for chat

## Setup & Running

1.  **Prerequisites:**
    * Android Studio (latest stable version recommended)
    * Android SDK installed
    * Android Emulator or Physical Device (Physical device required for Bluetooth testing)
2.  **Clone Repository:**
    ```bash
    git clone <repository-url>
    cd <repository-folder>
    ```
3.  **Open Project:** Open the project in Android Studio.
4.  **Build:** Let Gradle sync and build the project (Build > Make Project).
5.  **Run:** Select an emulator or connect a physical device and click Run (Shift+F10).

**Important Notes for Testing:**

* **LLM:** The Local LLM is currently **mocked** (`MockLlmService.kt`). It uses keyword matching to simulate intent recognition and function calls.
* **Tickets:** User login/registration and ticket data are **mocked** (`MockUserRepository.kt`, `MockTicketRepository.kt`).
* **Map Data:** Airport maps are loaded from static JSON files in `res/raw/`.
* **Bluetooth Chat:**
    * Requires **two physical Android devices**.
    * Ensure Bluetooth is **enabled** on both devices.
    * Grant **all required Bluetooth permissions** (CONNECT, SCAN, ADVERTISE - varies by Android version) and potentially Location permission when prompted by the app or in system settings.
    * For best results, **pair the two devices** using the standard Android Bluetooth settings *before* attempting to connect via the app's chat screen, although the app uses discovery and attempts connection regardless.
    * One user should tap "Listen" and the other should tap "Find Companion", select the listening device from the list, and tap it to connect.

## Future Ideas

* Integrate a real, lightweight on-device LLM (e.g., using MediaPipe, TensorFlow Lite, ONNX Runtime) for improved natural language understanding.
* Implement dynamic map loading based on actual user tickets (origin/destination airports).
* Integrate with real flight/gate information APIs (requires network).
* Add more detailed Points of Interest (shops, restaurants, lounges).
* Improve indoor localization using Wi-Fi RTT, BLE beacons, or ARCore.
* Enhance Bluetooth chat (group chat, file sharing - likely requires BLE).
* Refine UI/UX and accessibility features.

## Acknowledgments

* **Vueling** for proposing this relevant and challenging problem for airport passengers.
* The organizers of **HackUPC 2025** for hosting this event.

---
