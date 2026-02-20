<p align="center">
  <img src="assets/logo.png" alt="OSHI Messenger" width="140"/>
</p>

<h1 align="center">OSHI Messenger</h1>

<h3 align="center"><em>The Most Private Messenger Ever Built</em></h3>

<p align="center">
  Zero-knowledge encryption. No phone number. Works offline.<br>
  Your messages belong to you — and only you.
</p>

<p align="center">
  <a href="https://apps.apple.com/app/oshi-mesh/id6753926350">
    <img src="https://img.shields.io/badge/iOS-17%2B-000000?style=for-the-badge&logo=apple&logoColor=white" alt="iOS 17+"/>
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="MIT License"/>
  </a>
  <a href="https://github.com/Lastoneparis/OSHI">
    <img src="https://img.shields.io/badge/Open%20Source-%E2%9C%93-brightgreen?style=for-the-badge" alt="Open Source"/>
  </a>
  <a href="https://apps.apple.com/app/oshi-mesh/id6753926350">
    <img src="https://img.shields.io/badge/App%20Store-Download-blue?style=for-the-badge&logo=app-store&logoColor=white" alt="App Store"/>
  </a>
</p>

<p align="center">
  <a href="https://github.com/Lastoneparis/OSHI/stargazers">
    <img src="https://img.shields.io/github/stars/Lastoneparis/OSHI?style=social" alt="GitHub Stars"/>
  </a>
  <a href="https://oshi-messenger.com">
    <img src="https://img.shields.io/badge/Web-oshi--messenger.com-informational" alt="Website"/>
  </a>
</p>

---

## 🔐 What is OSHI?

**OSHI** is a zero-knowledge encrypted messenger designed for people who refuse to compromise on privacy. Unlike mainstream messengers that require your phone number, harvest metadata, and depend on centralized servers, OSHI takes a fundamentally different approach:

- **No phone number, no email** — create an account with nothing but a username
- **End-to-end encrypted by default** — every message, every call, every time
- **Works offline** — communicate directly over Bluetooth and WiFi mesh networks
- **Zero metadata** — we cannot see who talks to whom, when, or how often
- **Open source** — every line of cryptographic code is auditable right here

OSHI is built for journalists, activists, security professionals, and anyone living in or traveling to regions where private communication is a matter of safety — not convenience.

---

## 🌟 Core Features

### 🔒 End-to-End Encryption

OSHI implements the **Signal Protocol (Double Ratchet)** with modern cryptographic primitives:

| Component | Algorithm |
|-----------|-----------|
| Key Exchange | **X25519** (Curve25519 ECDH) |
| Message Encryption | **AES-256-GCM** |
| Ratchet Protocol | **Double Ratchet** (Signal Protocol) |
| Key Derivation | **HKDF-SHA256** |

Every message generates new encryption keys through the ratchet mechanism, providing **forward secrecy** and **break-in recovery**. Even if a key is compromised, past and future messages remain protected.

---

### 📡 Mesh Networking

OSHI creates direct peer-to-peer connections between devices using **Bluetooth LE** and **WiFi Direct** — no internet required.

- **Range**: ~100 meters between devices
- **Automatic peer discovery**: devices find each other without manual configuration
- **Multi-hop relay**: messages can route through intermediate peers
- **Zero server dependency**: local messages never touch a server

Perfect for concerts, protests, disaster zones, remote areas, or any situation where internet access is unavailable or compromised.

---

### 🎭 Steganography (NEW)

OSHI introduces **military-grade steganography** — the ability to hide secret messages inside ordinary-looking content. This is a first for any mainstream messenger.

#### HUGO Algorithm — Image Steganography
Hide encrypted messages inside normal photographs. The embedded data is statistically undetectable, even under forensic analysis. Recipients extract the hidden message; everyone else sees a regular photo.

#### Text Steganography
Embed secret messages using **zero-width Unicode characters** within ordinary text. The message looks completely normal to anyone reading it — the hidden payload is invisible to the human eye.

#### 6 Covert Channel Families
OSHI implements six distinct families of network covert channels for censorship bypass in hostile network environments:

- Timing-based channels
- Protocol header manipulation
- DNS covert channels
- HTTP field encoding
- Packet size modulation
- Traffic pattern mimicry

These channels allow OSHI to function even when deep packet inspection (DPI) is actively blocking encrypted messenger traffic.

---

### 🤖 Bot API (NEW)

Build automation on top of OSHI with a **Telegram-like Bot API**. Create bots that can:

- Send messages to groups and channels
- Respond to commands
- Run on schedules (cron-style)
- Moderate content
- Integrate with external services via webhooks

A complete **Python SDK** is included in the [`sdk/`](sdk/) directory. See the [Bot SDK](#-bot-sdk) section below for a quick start.

---

### 🧠 AI Offline (NEW)

OSHI integrates **Apple Intelligence** for on-device natural language processing on iOS 26+:

- **Smart text corrections** — grammar and style suggestions without sending text to the cloud
- **Translation** — 16+ languages, processed entirely on-device
- **Contextual suggestions** — intelligent replies powered by on-device NLP
- **Zero cloud dependency** — all AI processing happens on your device's Neural Engine

Your conversations are never analyzed by external servers. Intelligence stays local.

---

### 📞 Encrypted Voice Calls

Real-time voice calls with full end-to-end encryption:

- **CallKit integration** — native iOS call UI and experience
- **Voice effects** — optional voice modification for identity protection
- **Works over mesh or internet** — adaptive routing
- **China-compliant** — compatible with MIIT regulations for users in mainland China

---

### 🌐 IPFS & Tor Integration

When direct communication isn't possible, OSHI falls back to decentralized infrastructure:

- **IPFS** — messages stored on a decentralized network, no single point of failure
- **Tor routing** — optional onion routing for maximum anonymity
- **30-day ephemeral storage** — messages automatically expire from IPFS nodes
- **Censorship-resistant** — no central server to block or seize

---

### 💨 Disappearing Messages

Control the lifecycle of every message you send:

- **View-once** — message is destroyed after a single viewing
- **Auto-delete timers** — set messages to disappear after minutes, hours, or days
- **Screenshot blocking** — prevents screen capture on the recipient's device
- **No forensic traces** — securely wiped from device storage

---

## 📊 Feature Comparison

How OSHI compares to other messaging platforms:

| Feature | OSHI | Signal | Telegram | WhatsApp | Session |
|---------|:----:|:------:|:--------:|:--------:|:-------:|
| **No Phone Required** | ✅ | ❌ | ❌ | ❌ | ✅ |
| **Works Offline** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Steganography** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Bot API** | ✅ | ❌ | ✅ | ❌ | ❌ |
| **AI Offline** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Covert Channels** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Open Source** | ✅ | ✅ | ⚠️ Client | ❌ | ✅ |
| **E2E Encryption** | ✅ | ✅ | ⚠️ Secret chats | ✅ | ✅ |
| **Mesh Network** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Zero Metadata** | ✅ | ⚠️ Partial | ❌ | ❌ | ✅ |

---

## 🏗️ Architecture

OSHI is built as a native iOS application in Swift, with a decentralized backend architecture. For the complete technical deep-dive, see [Architecture.md](Architecture.md).

```
OSHI/
├── src/                    # Core Swift source files
│   ├── DoubleRatchet.swift         # Signal Protocol implementation
│   ├── MeshNetworkManager.swift    # Bluetooth/WiFi P2P mesh
│   ├── SteganographyManager.swift  # HUGO image steganography
│   ├── TextSteganography.swift     # Zero-width Unicode encoding
│   ├── NetworkSteganography.swift  # Network covert channels
│   ├── CovertChannelManager.swift  # 6-family covert channel engine
│   ├── BotManager.swift            # Bot API server-side logic
│   ├── NLPManager.swift            # Apple Intelligence / NLP
│   ├── VoiceCallManager.swift      # E2E encrypted calls + CallKit
│   └── ChinaRegionDetector.swift   # MIIT compliance detection
├── sdk/                    # Python Bot SDK
│   ├── oshi_bot.py                 # Bot SDK client library
│   └── examples/                   # Example bot implementations
├── docs/                   # Technical documentation
│   ├── encryption.md               # Cryptographic protocol details
│   └── protocol.md                 # Communication protocol spec
├── assets/                 # Logo, screenshots, media
├── Architecture.md         # System architecture overview
├── Security.md             # Security model & bug bounty
└── LICENSE                 # MIT License
```

---

## 🤖 Bot SDK

The OSHI Bot SDK lets you build bots in Python that interact with OSHI groups and channels — similar to Telegram's Bot API.

### Installation

```bash
# Download the SDK
curl -O https://raw.githubusercontent.com/Lastoneparis/OSHI/main/sdk/oshi_bot.py
```

Or clone the repository:

```bash
git clone https://github.com/Lastoneparis/OSHI.git
cd OSHI/sdk
```

### Quick Start

```python
from oshi_bot import OshiBot

# Initialize with your bot token (created in the OSHI app)
bot = OshiBot(token="YOUR_BOT_TOKEN")

# Send a message to a group
bot.send("GROUP_ID", "Hello from my OSHI bot!")
```

### Creating a Bot

1. Open the OSHI app
2. Navigate to **Portal > Bots > Create**
3. Copy the bot token
4. Assign the bot to a group
5. Use the SDK to send messages

### Bot Types

- **Webhook bots** — respond to incoming messages in real time
- **Scheduled bots** — send messages on a cron schedule
- **Moderator bots** — automate group moderation
- **Integration bots** — bridge OSHI with external services

Full API documentation: **[https://oshi-messenger.com/bot-api](https://oshi-messenger.com/bot-api)**

---

## 🛡️ Security

OSHI's security model is designed around a single principle: **we cannot read your messages, even if compelled by law**. We have zero access to plaintext content, encryption keys, or social graphs.

Key properties:

- **Zero knowledge** — the server never sees plaintext or keys
- **Forward secrecy** — compromising a key does not expose past messages
- **Break-in recovery** — the ratchet automatically heals after a compromise
- **No metadata** — we do not log who communicates with whom
- **Open source** — audit the cryptographic implementation yourself

For the full security model, threat analysis, and cryptographic specifications, see:

- [**Security.md**](Security.md) — Security model and responsible disclosure
- [**docs/encryption.md**](docs/encryption.md) — Cryptographic protocol details
- [**docs/protocol.md**](docs/protocol.md) — Communication protocol specification

---

## 📱 Download

<p align="center">
  <a href="https://apps.apple.com/app/oshi-mesh/id6753926350">
    <img src="https://developer.apple.com/assets/elements/badges/download-on-the-app-store.svg" alt="Download on the App Store" width="200"/>
  </a>
</p>

<p align="center">
  Available on iPhone and iPad running iOS 17+<br>
  <a href="https://oshi-messenger.com">https://oshi-messenger.com</a>
</p>

---

## 🤝 Contributing

We welcome contributions from the community! OSHI is open source under the [MIT License](LICENSE), and we believe privacy tools are strongest when built in the open.

Ways to contribute:

- **Report bugs** — open an [issue](https://github.com/Lastoneparis/OSHI/issues)
- **Suggest features** — start a [discussion](https://github.com/Lastoneparis/OSHI/discussions)
- **Submit code** — fork the repo and open a pull request
- **Audit security** — review the cryptographic implementation and report findings
- **Translate** — help bring OSHI to more languages

Please read the code of conduct before contributing. All contributions are subject to the MIT License.

---

## 💰 Bug Bounty

We take security seriously. If you discover a vulnerability in OSHI, we want to hear about it.

| Severity | Reward |
|----------|--------|
| **Critical** (RCE, key extraction, E2E bypass) | Up to **$5,000** |
| **High** (authentication bypass, data leak) | Up to **$2,500** |
| **Medium** (XSS, CSRF, information disclosure) | Up to **$1,000** |
| **Low** (minor issues, best practice violations) | Up to **$250** |

**Contact**: [hugomoriceau@icloud.com](mailto:hugomoriceau@icloud.com)

Please practice responsible disclosure. Give us 90 days to address the issue before public disclosure. See [Security.md](Security.md) for full details.

---

## 📜 License

OSHI is released under the **[MIT License](LICENSE)**.

```
MIT License

Copyright (c) 2026 OSHI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
```

---

## 🙏 Acknowledgments

- [Apple CryptoKit](https://developer.apple.com/documentation/cryptokit) — Cryptographic primitives
- [Signal Protocol](https://signal.org/docs/) — Double Ratchet inspiration
- [IPFS](https://ipfs.io) — Decentralized content storage
- [Tor Project](https://www.torproject.org) — Onion routing
- [Pinata](https://pinata.cloud) — IPFS pinning infrastructure

---

<p align="center">
  <strong>Made with care in Switzerland 🇨🇭</strong><br>
  <em>Privacy is not a luxury — it's a fundamental right.</em>
</p>

<p align="center">
  <a href="https://oshi-messenger.com">Website</a> ·
  <a href="https://apps.apple.com/app/oshi-mesh/id6753926350">App Store</a> ·
  <a href="https://github.com/Lastoneparis/OSHI">GitHub</a> ·
  <a href="mailto:hugomoriceau@icloud.com">Contact</a>
</p>
