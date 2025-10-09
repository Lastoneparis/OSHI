# OSHI
OSHI is **100% open source**, built with transparency and auditability at its core. Every line of code is public and verifiable.

OSHI is 100% open source, built with transparency and auditability at its core. Every line of code is public and verifiable.

OSHI - The Sovereign Messenger

Decentralized, end-to-end encrypted messaging without servers

OSHI is a proof-of-concept secure messenger that works offline using peer-to-peer mesh networking. Built on principles of digital sovereignty, privacy, and resilience.

🔐 Security Features

Signal Double Ratchet Protocol - Industry-standard E2E encryption Wallet-Based Identity - Ethereum/Solana cryptographic keys Peer-to-Peer Mesh - Direct Bluetooth/WiFi connections Multi-Hop Routing - Messages relay through trusted peers Perfect Forward Secrecy - Past messages stay secure Out-of-Order Handling - Robust mesh message decryption Encrypted IPFS Fallback - Decentralized cloud backup

📱 Key Files for Proof of Concept Core Security (Essential)

DoubleRatchet.swift - Signal protocol implementation WalletManager.swift - Cryptographic identity MessageManager.swift - Message encryption & routing SafetyNumber.swift - Contact verification

Networking (Essential)

MeshNetworkManager.swift - P2P mesh networking MeshRelay.swift - Multi-hop message routing

Supporting

KeyRotation.swift - Automated key management MediaManager.swift - Encrypted media files GroupMessaging.swift - Group chat support

🚀 Quick Start swift// 1. Create Identity let wallet = WalletManager() try wallet.generateWallet(blockchain: .ethereum)

// 2. Start Mesh meshManager.startAdvertising() meshManager.startBrowsing()

// 3. Send Message messageManager.sendMessage( content: "Hello", recipientAddress: peerPublicKey, walletManager: wallet, meshManager: mesh )

⚠️ Disclaimer Experimental software - Not audited. See LICENSE for full disclaimers.

📄 License MIT License with additional disclaimers - See LICENSE

Built for a world without central control

**Double Ratchet works PERFECTLY through cloud/IPFS!** ✅

The transport method (mesh vs cloud) **does NOT affect** the encryption!

---

## 🔍 How It Works

### Message Flow (Cloud/IPFS):

```
SENDER (You)
    ↓
1. Plaintext: "Hello"
    ↓
2. Double Ratchet Encrypt
    → Creates: DoubleRatchetMessage
    → Contains: ciphertext, header, chain info
    ↓
3. JSON Encode
    → Converts to JSON bytes
    ↓
4. ECIES Wrap (wrapRatchetMessage)
    → Outer encryption layer
    ↓
5. Upload to IPFS via VPS
    → Store encrypted blob on IPFS
    → Get IPFS hash: QmXYZ...
    ↓
6. VPS queues hash for recipient
    ↓
    
RECIPIENT (Other Person)
    ↓
1. Poll VPS for pending messages
    → VPS returns: [QmXYZ...]
    ↓
2. Fetch from IPFS (multiple gateways)
    → Download encrypted blob
    ↓
3. ECIES Unwrap (unwrapRatchetMessage)
    → Remove outer layer
    ↓
4. JSON Decode
    → Get DoubleRatchetMessage
    ↓
5. Double Ratchet Decrypt
    → Verify chain state
    → Decrypt ciphertext
    ↓
6. Plaintext: "Hello" ✅
```
