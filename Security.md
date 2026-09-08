# OSHI Messenger - Security Architecture

## Reporting a vulnerability

Report privately, not in a public issue. We would rather hear it from you than
read it in a paper.

**Email:** contact@oshi-messenger.com
**Encrypt to our OpenPGP key** — [`oshi-public-key.asc`](oshi-public-key.asc)

```
Fingerprint  CC57 EFDF DD66 DF95 1F9C  8108 025F 383C DCCA 2600
Key ID       025F383CDCCA2600
Type         Ed25519 (sign) + Cv25519 (encrypt)
Created      2026-09-08      Expires  2029-09-07
UID          OSHI (OSHI Messenger project key) <contact@oshi-messenger.com>
```

Verify the fingerprint against this file over a second channel before you trust it.

### What we want looked at

The transport and the cryptography are published here and are identical to the
shipping build. The areas where a finding would matter most, in order:

1. `src/OSHICryptoV2.swift` and `src/DoubleRatchet.swift` — X3DH, the ratchet
   state machine, AEAD and header binding, the post-quantum KEM layer.
2. `src/MeshNetworkManager.swift` and `src/CrossPlatformMesh.swift` — peer
   identity binding, session establishment, and active attacks by a node in
   radio range. This is the attack class that broke comparable products.
3. `src/MeshRelay.swift` — replay, amplification, and what a relayed packet
   discloses to a passive observer.

### What we will do

- Acknowledge within 72 hours.
- Agree a disclosure date with you. We do not ask for indefinite embargoes and we
  do not sign NDAs.
- Credit you as you wish, including not at all.
- Publish the finding and the fix, including anything we decide not to fix and
  why.

### Bug bounty

USD 500 critical, 250 high, 100 medium, paid from a solo developer's own pocket.
Small, and honest about being small.

### Out of scope

Anything requiring a jailbroken device, physical access to an unlocked phone, or
a debugger attached to the process. Denial of service against a single device by
an attacker already in Bluetooth range is in scope only where it is remote and
unauthenticated.

---

## 🔐 Cryptographic Overview

OSHI uses military-grade encryption to protect your communications.

### End-to-End Encryption

| Component | Algorithm | Key Size |
|-----------|-----------|----------|
| Key Exchange | X25519 (ECDH) | 256-bit |
| Message Encryption | AES-256-GCM | 256-bit |
| Key Derivation | HKDF-SHA256 | - |
| Message Authentication | GCM Tag | 128-bit |

### Double Ratchet Protocol

OSHI implements the Signal Protocol's Double Ratchet algorithm:

- **Root Key Ratchet**: Derives new keys after each DH exchange
- **Chain Key Ratchet**: Derives message keys for each message
- **Forward Secrecy**: Past messages cannot be decrypted if keys are compromised
- **Break-in Recovery**: Future messages protected after compromise

```
┌─────────────────────────────────────────────────────────┐
│                    Double Ratchet                        │
├─────────────────────────────────────────────────────────┤
│  Alice                                         Bob      │
│    │                                            │       │
│    │◄────────── DH Key Exchange ──────────────►│       │
│    │                                            │       │
│    ├── Root Key ──► Chain Key ──► Message Key   │       │
│    │                                            │       │
│    │    [Message 1] ─────────────────────────►  │       │
│    │    [Message 2] ─────────────────────────►  │       │
│    │                                            │       │
│    │◄─────────── DH Ratchet Step ─────────────►│       │
│    │                                            │       │
│    │  ◄───────────────────────── [Message 3]   │       │
│    │                                            │       │
└─────────────────────────────────────────────────────────┘
```

### Voice Call Encryption

| Feature | Implementation |
|---------|----------------|
| Audio Encryption | AES-256-GCM per packet |
| Key Exchange | ECDH + session key |
| Nonce | Incrementing counter + random salt |
| Replay Protection | Nonce validation |

## 🌐 Network Architecture

### Mesh Network (P2P)
- Direct device-to-device via MultipeerConnectivity
- No server involvement for local communications
- Automatic peer discovery and connection

### IPFS Fallback
- Messages stored encrypted on IPFS
- Only recipient can decrypt
- Server never sees plaintext

### VPS Relay (Voice Calls)
- End-to-end encrypted audio
- VPS only routes encrypted blobs
- Cannot decrypt or inspect content

```
┌──────────────────────────────────────────────────────────┐
│                  Message Routing                          │
├──────────────────────────────────────────────────────────┤
│                                                          │
│   [Device A] ◄──── Mesh Network ────► [Device B]        │
│       │              (Direct P2P)           │            │
│       │                                     │            │
│       └──────► IPFS/VPS ◄──────────────────┘            │
│              (Encrypted Fallback)                        │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 🛡️ Security Properties

### What OSHI Protects Against

| Threat | Protection |
|--------|------------|
| Message interception | E2E encryption |
| Server compromise | Zero-knowledge architecture |
| Key compromise | Forward secrecy |
| Replay attacks | Nonce validation |
| Man-in-the-middle | Key verification |
| Metadata leakage | Mesh network option |
| Screenshot | Screenshot blocking (optional) |

### What We Cannot Protect Against

- Physical device access with unlocked phone
- Keyloggers on the device
- Recipient sharing messages manually
- Legal compulsion of the user

## 🔍 Security Audit

We welcome security researchers to verify our claims.

### How to Audit

1. Review the cryptographic implementations
2. Test encryption/decryption flows
3. Verify no plaintext leakage
4. Check key management

### Responsible Disclosure

If you find a vulnerability:
1. **DO NOT** disclose publicly
2. Email: security@oshi-messenger.com
3. Include: Description, steps to reproduce, impact
4. We respond within 48 hours

### Bug Bounty

We offer rewards for critical vulnerabilities:
- Critical (RCE, key extraction): Up to $5,000
- High (encryption bypass): Up to $2,000
- Medium (information leak): Up to $500

## 📜 Cryptographic Code Samples

### Key Generation (Conceptual)
```swift
// X25519 key pair generation
let privateKey = Curve25519.KeyAgreement.PrivateKey()
let publicKey = privateKey.publicKey

// Shared secret derivation
let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)

// Key derivation with HKDF
let symmetricKey = HKDF<SHA256>.deriveKey(
    inputKeyMaterial: sharedSecret,
    salt: salt,
    info: info,
    outputByteCount: 32
)
```

### Message Encryption (Conceptual)
```swift
// AES-256-GCM encryption
let sealedBox = try AES.GCM.seal(
    plaintext,
    using: symmetricKey,
    nonce: AES.GCM.Nonce()
)

// Result: ciphertext + authentication tag
let encrypted = sealedBox.ciphertext + sealedBox.tag
```

## 📱 App Security Features

- **No phone number required**: Privacy by design
- **No central account**: Keys stored only on device
- **Encrypted local storage**: Messages encrypted at rest
- **Screenshot protection**: Blocks screenshots in chat
- **Message expiration**: Auto-delete options
- **Key verification**: Safety numbers for contact verification

## 🔗 Links

- Website: https://oshi-messenger.org
- App Store: https://apps.apple.com/app/oshi-mesh/id6753926350
- Security Contact: security@oshi-messenger.com

---

*Last updated: December 2024*
