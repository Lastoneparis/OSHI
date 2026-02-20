# Steganography System

OSHI implements a multi-layered steganography system to enable covert communication in restricted environments. The system operates across three independent layers -- image, text, and network -- each providing plausible deniability and resistance to detection.

## Architecture Overview

```
+--------------------------------------------------+
|              SteganographyManager                 |
|  Orchestrates all 3 layers, selects best method   |
|  based on network assessment and channel capacity  |
+---------+-----------------+-----------------------+
          |                 |                 |
  +-------v------+  +------v-------+  +------v-----------+
  | Image Stego  |  | Text Stego   |  | Covert Channels  |
  | LSB + AES    |  | Zero-width   |  | HTTP headers     |
  | DCT-aware    |  | Homoglyphs   |  | DNS tunneling    |
  | JPEG-resilient|  | Whitespace   |  | Domain fronting  |
  +--------------+  +--------------+  +------------------+
```

**Source files:**

| Component | File |
|---|---|
| Manager / orchestration | `src/SteganographyManager.swift` |
| Image steganography | `src/ImageSteganography.swift` |
| Text steganography | `src/TextSteganography.swift` |
| Network covert channels | `src/CovertChannelManager.swift` |
| Network restriction assessment | `src/NetworkAssessment.swift` |

## Image Steganography

### Algorithm: HUGO-Inspired Adaptive LSB

OSHI's image steganography uses an adaptive Least Significant Bit (LSB) embedding algorithm inspired by the HUGO (Highly Undetectable steGO) family of schemes. Rather than uniformly modifying LSBs across the entire image, the algorithm preferentially embeds data in high-complexity regions (edges, textures, noise) where modifications are harder to detect statistically.

### Embedding Process

1. **Capacity analysis** -- Scan the cover image to compute a distortion map. Pixels in flat, smooth regions receive high distortion costs; pixels in textured or noisy regions receive low costs.
2. **AES-256-GCM encryption** -- The plaintext payload is encrypted with AES-256-GCM before embedding. The encryption key is derived from the conversation's shared secret via HKDF. This ensures that even if the hidden data is extracted, it appears as random noise without the key.
3. **Adaptive embedding** -- Bits of the encrypted payload are written into the LSBs of selected pixels, weighted by the inverse distortion cost. High-texture areas absorb more bits; smooth areas are left untouched.
4. **DCT-awareness** -- When the output format is JPEG, the embedder accounts for DCT (Discrete Cosine Transform) quantization. It avoids embedding in DCT coefficients that are likely to be zeroed out during JPEG compression, preserving payload integrity across re-compression cycles.
5. **Integrity tag** -- The AES-GCM authentication tag is embedded alongside the ciphertext, enabling the receiver to verify that the extracted payload has not been corrupted or tampered with.

### JPEG Resilience

Standard LSB steganography breaks when an image is saved as JPEG, because lossy compression modifies pixel values. OSHI addresses this in two ways:

- **DCT-domain embedding** -- When targeting JPEG output, the embedder operates on quantized DCT coefficients rather than spatial-domain pixels. This survives a single round of JPEG compression at the same or higher quality factor.
- **Error-correcting codes** -- A Reed-Solomon outer code is applied to the encrypted payload before embedding. This allows recovery of the payload even if a fraction of the embedded bits are flipped by recompression or image processing.

### Detection Resistance

- Adaptive pixel selection minimizes changes to the image histogram and co-occurrence statistics, defeating first-order and second-order steganalysis.
- The encrypted payload is indistinguishable from random noise, preventing content-based detection.
- Embedding capacity is conservatively limited (typically under 10% of available pixels) to keep statistical detectability below practical thresholds.

## Text Steganography

OSHI implements three complementary text steganography methods. The system selects the best method (or a combination) based on the communication channel and cover text availability.

### Zero-Width Unicode Characters

Invisible Unicode characters are inserted between visible characters in the cover text to encode hidden data:

| Character | Unicode | Bit Value |
|---|---|---|
| Zero-width space | U+200B | 0 |
| Zero-width non-joiner | U+200C | 1 |
| Zero-width joiner | U+200D | delimiter |

The hidden message is encrypted with AES-256-GCM before encoding. A typical sentence can carry 50-200 hidden bits without any visible change.

**Robustness:** Most messaging platforms and text editors preserve zero-width characters. However, some platforms (notably certain web forums) strip them. The system detects this and falls back to alternative methods.

### Homoglyph Substitution

Visually identical characters from different Unicode blocks are substituted to encode bits:

| Latin | Cyrillic | Bit |
|---|---|---|
| a (U+0061) | a (U+0430) | 1 |
| e (U+0065) | e (U+0435) | 1 |
| o (U+006F) | o (U+043E) | 1 |
| p (U+0070) | p (U+0440) | 1 |
| Original char | -- | 0 |

Each substitutable character position encodes one bit. The substitutions are invisible to the human eye but detectable by the recipient's client, which compares Unicode code points.

**Capacity:** Roughly 1 bit per eligible character. A 100-character message with 30 substitutable characters encodes 30 bits (~3.75 bytes) of hidden data.

### Whitespace Encoding

Trailing spaces and tabs at the end of lines encode hidden bits:

- A trailing space encodes `0`
- A trailing tab encodes `1`
- Line count and whitespace patterns encode the full payload

This method is most effective in multi-line text (code snippets, formatted messages) where trailing whitespace is not suspicious.

### Cover Text Generation

When no natural cover text is available, the system generates plausible cover text using template-based sentence construction. Templates are drawn from common conversational patterns to avoid raising suspicion. The generated text is then used as a carrier for one of the above encoding methods.

## Covert Network Channels

For environments where message content is inspected or filtered, OSHI can embed data in the network transport layer itself.

### HTTP Header Steganography

Hidden data is encoded in HTTP headers that appear legitimate:

- **Header ordering** -- The order of standard HTTP headers (Accept, Accept-Language, Accept-Encoding, etc.) encodes bits. There are N! permutations of N headers; each permutation maps to a binary value.
- **Header value variations** -- Minor, valid variations in header values encode additional bits. For example, `Accept-Language: en-US,en;q=0.9` vs `Accept-Language: en-US,en;q=0.8` encodes one bit in the quality factor.
- **Cache-Control directives** -- The presence or absence of optional directives (no-transform, must-revalidate, etc.) encodes bits while remaining valid HTTP.
- **Custom timing** -- Controlled inter-request timing intervals encode bits via pulse-position modulation.

### DNS Covert Channel

Data is encoded in DNS queries that resolve normally:

- Subdomain labels carry encoded payload: `a3f2b1.data.example.com`
- Query types (A, AAAA, MX, TXT) encode 2 bits per query
- TTL values in cached responses carry return-channel data
- All queries resolve to valid addresses, making the traffic appear normal

### Domain Fronting

OSHI supports domain fronting as an anti-censorship measure:

- The TLS SNI (Server Name Indication) and the HTTP Host header point to different destinations
- The outer TLS connection appears to connect to a permitted CDN domain
- The inner HTTP request is routed to the actual OSHI server
- From a network observer's perspective, the traffic appears to be normal CDN/cloud usage

### Web Request Mimicry

Covert data is embedded in traffic patterns that mimic normal web browsing:

- Requests follow realistic browsing patterns (HTML, then CSS/JS/images)
- Payload is encoded in query parameters, cookie values, and POST form fields
- Traffic timing mimics human browsing cadence with realistic inter-request delays
- The system maintains a set of cover URLs that serve real web content

## Network Restriction Assessment

Before selecting a covert channel, OSHI assesses the network environment and assigns a restriction level from 1 to 5:

| Level | Description | Available Channels |
|---|---|---|
| 1 - Open | No restrictions detected | Direct connection (no stego needed) |
| 2 - Light | Basic firewall, some ports blocked | HTTP header stego, DNS covert |
| 3 - Moderate | DPI active, known protocols inspected | Domain fronting, web mimicry |
| 4 - Heavy | Whitelist-only, TLS inspection | Domain fronting + header stego |
| 5 - Severe | Air-gapped or near-total block | Image/text stego via side channels |

The assessment runs automatically on app launch and periodically in the background. It probes for:

- Port availability (80, 443, 53, 8080, custom)
- DNS resolution accuracy (checks for DNS hijacking)
- TLS certificate inspection (detects MITM proxies)
- Protocol fingerprinting (detects deep packet inspection)
- IP-based geofencing (checks reachability of known endpoints)

The selected restriction level determines which steganography layers are activated and which transport methods are used for message delivery.

## Security Considerations

- **No security through obscurity** -- All steganography methods assume the adversary knows the algorithm. Security rests on the encryption key (derived from the E2E shared secret), not on the hiding method.
- **Plausible deniability** -- Cover images and cover text are genuine content. There is no structural marker that reveals the presence of hidden data without the decryption key.
- **Forward secrecy** -- Each message uses a unique encryption key derived via the Double Ratchet protocol. Compromising one key does not reveal past or future hidden payloads.
- **Layered defense** -- Multiple steganography methods can be combined (e.g., text stego inside an image stego payload inside an HTTP covert channel) for defense in depth.
