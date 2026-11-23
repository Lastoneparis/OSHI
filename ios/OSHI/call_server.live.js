const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const crypto = require('crypto');
const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });
const PORT = 8083;
// ============================================================
// TURN credential rotation (F6 — 2026-04-26)
// Reads TURN_SECRET from /etc/oshi/turn_secret.env via systemd EnvironmentFile.
// Generates time-limited HMAC-SHA1 creds compatible with coturn use-auth-secret.
// ============================================================
const TURN_SECRET = process.env.TURN_SECRET || '';
const TURN_TTL_SECONDS = 3600;  // 1-hour validity
if (!TURN_SECRET) {
    console.warn('WARN: TURN_SECRET env var not set — /turn-creds will return 503');
}

// Capture raw body for signature verification before JSON parsing
app.use(express.json({
    limit: '5mb',
    verify: (req, res, buf) => { req.rawBody = buf; }
}));
const activeCalls = new Map();
const audioBuffers = new Map();
const videoBuffers = new Map();
const pendingCallSignals = new Map();
const recentPushCallIds = new Set();
// __AUDIO_WAKE_PUSH_2026_06_09__
// Wake-push throttle: one silent push per recipient per WS_WAKE_THROTTLE_MS.
// Set when /audio falls to 'buffered' delivery (recipient WS dead) during an
// active call. Prevents APNs flood while still giving iOS a wake nudge.
const recentWsWakePushes = new Map(); // recipientKey -> timestamp
const WS_WAKE_THROTTLE_MS = 1500;  // __WAKE_PUSH_AGGRESSIVE_2026_06_10__ bumped 5s->1.5s — matches WS reconnect window
 // Dedup VoIP push per callId
const answeredElsewhereMap = new Map();  // Multi-device: non-destructive signal store
const wsConnections = new Map();
let audioPacketCount = 0;
let videoPacketCount = 0;
let binaryAudioCount = 0;
let binaryVideoCount = 0;
function normalizeKey(key) {
    if (!key) return key;
    return key.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// ============================================================
// SECURITY: Ed25519 Signature Verification
// ============================================================
// Grace period: accept unsigned requests from old clients
// Set to true once all clients are updated to require signatures
const SIGNATURE_REQUIRED = false;
const TIMESTAMP_MAX_AGE_MS = 30000; // 30 seconds freshness window

// Ed25519 DER SPKI prefix (12 bytes) for wrapping raw 32-byte public keys
const ED25519_DER_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

function verifySignature(req) {
    const signature = req.headers['x-oshi-signature'];
    const timestamp = req.headers['x-oshi-timestamp'];
    const signingPubKey = req.headers['x-oshi-signing-pubkey'];

    // Grace period: if no signature headers, accept (old client)
    if (!signature && !SIGNATURE_REQUIRED) {
        return { valid: true, reason: 'unsigned-accepted' };
    }

    // If signature headers are incomplete
    if (!signature || !timestamp || !signingPubKey) {
        if (SIGNATURE_REQUIRED) {
            return { valid: false, reason: 'missing-signature-headers' };
        }
        return { valid: true, reason: 'unsigned-accepted' };
    }

    // Timestamp freshness check
    const requestTime = parseInt(timestamp, 10);
    const now = Date.now();
    if (isNaN(requestTime) || Math.abs(now - requestTime) > TIMESTAMP_MAX_AGE_MS) {
        if (!SIGNATURE_REQUIRED) {
            return { valid: true, reason: 'timestamp-expired-grace' };
        }
        return { valid: false, reason: 'timestamp-expired' };
    }

    // Build canonical message: METHOD\nPATH\nSHA256(body)\nTIMESTAMP
    // Use raw body bytes (before Express JSON re-serialization) for consistent hashing
    const method = req.method;
    const path = req.originalUrl.split('?')[0]; // Exclude query params
    const rawBodyBuf = req.rawBody || Buffer.from(JSON.stringify(req.body) || '');
    const bodyHash = crypto.createHash('sha256').update(rawBodyBuf).digest('hex');
    const message = `${method}\n${path}\n${bodyHash}\n${timestamp}`;

    try {
        const pubKeyBuffer = Buffer.from(signingPubKey, 'base64');
        if (pubKeyBuffer.length !== 32) {
            if (!SIGNATURE_REQUIRED) {
                return { valid: true, reason: 'invalid-pubkey-grace' };
            }
            return { valid: false, reason: 'invalid-pubkey-length' };
        }

        const signatureBuffer = Buffer.from(signature, 'base64');
        const messageBuffer = Buffer.from(message, 'utf8');

        // Wrap raw Ed25519 key in DER SPKI format for Node.js crypto API
        const keyObject = crypto.createPublicKey({
            key: Buffer.concat([ED25519_DER_PREFIX, pubKeyBuffer]),
            format: 'der',
            type: 'spki'
        });

        const isValid = crypto.verify(null, messageBuffer, keyObject, signatureBuffer);
        if (isValid) {
            return { valid: true, reason: 'verified' };
        }

        // Grace period: accept invalid signatures but log for debugging
        if (!SIGNATURE_REQUIRED) {
            console.log(`[SIG-DEBUG] Invalid sig for ${method} ${path} — client may use different path prefix`);
            return { valid: true, reason: 'invalid-sig-grace' };
        }
        return { valid: false, reason: 'invalid-signature' };
    } catch (err) {
        console.error('Signature verification error:', err.message);
        if (!SIGNATURE_REQUIRED) {
            return { valid: true, reason: 'verification-error-grace' };
        }
        return { valid: false, reason: 'verification-error' };
    }
}

function verifyWebSocketSignature(data) {
    const { signature, wsTimestamp, signingPubKey } = data;

    if (!signature && !SIGNATURE_REQUIRED) {
        return { valid: true, reason: 'unsigned-accepted' };
    }

    if (!signature || !wsTimestamp || !signingPubKey) {
        if (SIGNATURE_REQUIRED) {
            return { valid: false, reason: 'missing-ws-signature' };
        }
        return { valid: true, reason: 'unsigned-accepted' };
    }

    const requestTime = parseInt(wsTimestamp, 10);
    const now = Date.now();
    if (isNaN(requestTime) || Math.abs(now - requestTime) > TIMESTAMP_MAX_AGE_MS) {
        return { valid: false, reason: 'timestamp-expired' };
    }

    // Build WS canonical message: WS\nTYPE\nSHA256(payload)\nTIMESTAMP
    const payloadObj = {};
    if (data.audio) payloadObj.audio = data.audio;
    if (data.signal) payloadObj.signal = data.signal;
    if (data.video) payloadObj.video = data.video;
    if (data.recipient) payloadObj.recipient = data.recipient;
    if (data.callId) payloadObj.callId = data.callId;
    const payloadHash = crypto.createHash('sha256').update(JSON.stringify(payloadObj)).digest('hex');
    const message = `WS\n${data.type}\n${payloadHash}\n${wsTimestamp}`;

    try {
        const pubKeyBuffer = Buffer.from(signingPubKey, 'base64');
        if (pubKeyBuffer.length !== 32) {
            return { valid: false, reason: 'invalid-pubkey-length' };
        }

        const signatureBuffer = Buffer.from(signature, 'base64');
        const messageBuffer = Buffer.from(message, 'utf8');

        const keyObject = crypto.createPublicKey({
            key: Buffer.concat([ED25519_DER_PREFIX, pubKeyBuffer]),
            format: 'der',
            type: 'spki'
        });

        const isValid = crypto.verify(null, messageBuffer, keyObject, signatureBuffer);
        if (isValid) {
            return { valid: true, reason: 'verified' };
        }
        if (!SIGNATURE_REQUIRED) {
            return { valid: true, reason: 'invalid-ws-sig-grace' };
        }
        return { valid: false, reason: 'invalid-signature' };
    } catch (err) {
        console.error('WS signature verification error:', err.message);
        if (!SIGNATURE_REQUIRED) {
            return { valid: true, reason: 'ws-verification-error-grace' };
        }
        return { valid: false, reason: 'verification-error' };
    }
}

// ============================================================
// SECURITY: Rate Limiting
// ============================================================
const rateLimits = new Map(); // senderKey -> { signal: { count, resetTime }, audio: { count, resetTime } }

const RATE_LIMITS = {
    signal: { max: 30, windowMs: 60000 },   // 10 signals per minute
    audio: { max: 200, windowMs: 1000 },     // 200 audio packets per second
    video: { max: 120, windowMs: 1000 }      // 120 video packets per second
};

function checkRateLimit(senderKey, type) {
    if (!senderKey) return true;

    const limit = RATE_LIMITS[type];
    if (!limit) return true;

    const now = Date.now();

    if (!rateLimits.has(senderKey)) {
        rateLimits.set(senderKey, {});
    }

    const senderLimits = rateLimits.get(senderKey);

    if (!senderLimits[type] || now > senderLimits[type].resetTime) {
        senderLimits[type] = { count: 1, resetTime: now + limit.windowMs };
        return true;
    }

    senderLimits[type].count++;

    if (senderLimits[type].count > limit.max) {
        if (senderLimits[type].count === limit.max + 1) {
            console.log(`  RATE LIMITED: ${senderKey.substring(0,8)}... exceeded ${type} limit (${limit.max}/${limit.windowMs}ms)`);
        }
        return false;
    }

    return true;
}

// Cleanup stale rate limit entries every 5 minutes
setInterval(() => {
    const now = Date.now();
    for (const [key, limits] of rateLimits) {
        let allExpired = true;
        for (const type in limits) {
            if (limits[type].resetTime > now) {
                allExpired = false;
            }
        }
        if (allExpired) {
            rateLimits.delete(key);
        }
    }
}, 300000);

// ============================================================
// SECURITY: CORS Configuration
// ============================================================
const ALLOWED_ORIGINS = ['https://oshi-messenger.com'];

app.use((req, res, next) => {
    const origin = req.headers.origin;
    if (origin && ALLOWED_ORIGINS.includes(origin)) {
        res.setHeader('Access-Control-Allow-Origin', origin);
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-OSHI-Signature, X-OSHI-Timestamp, X-OSHI-Signing-PubKey');
    }
    if (req.method === 'OPTIONS') {
        return res.sendStatus(200);
    }
    next();
});

// ============================================================
// Binary WebSocket Protocol (Phase 1 optimization)
// ============================================================
// Client→Server: [type(1)][recipientKeyLen(1)][recipientKey][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
// Server→Client: [type(1)][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
// Types: 0x01=audio, 0x02=video, 0x03=signal, 0x04=register, 0x05=ping

function handleBinaryMessage(ws, clientKeyRef, buf) {
    if (buf.length < 3) return clientKeyRef;
    let offset = 0;
    const msgType = buf[offset++];

    // Read recipient key
    const recipientKeyLen = buf[offset++];
    if (offset + recipientKeyLen > buf.length) return clientKeyRef;
    const recipientKey = normalizeKey(buf.slice(offset, offset + recipientKeyLen).toString('utf8'));
    offset += recipientKeyLen;

    // Read sender key
    const senderKeyLen = buf[offset++];
    if (offset + senderKeyLen > buf.length) return clientKeyRef;
    const senderKey = normalizeKey(buf.slice(offset, offset + senderKeyLen).toString('utf8'));
    offset += senderKeyLen;

    // Read callId
    const callIdLen = buf[offset++];
    if (offset + callIdLen > buf.length) return clientKeyRef;
    const callId = buf.slice(offset, offset + callIdLen).toString('utf8');
    offset += callIdLen;

    // Payload (remaining bytes)
    const payload = buf.slice(offset);

    // Register (0x04)
    if (msgType === 0x04) {
        const newKey = senderKey;
        wsConnections.set(newKey, ws);
        ws._binaryMode = true;
        // Send binary ack: [0x04][0x01]
        ws.send(Buffer.from([0x04, 0x01]), { binary: true });
        console.log(`[BINARY] Registered ${newKey.substring(0, 12)}`);
        return newKey;
    }

    // Ping (0x05) -> Pong
    if (msgType === 0x05) {
        const pong = Buffer.alloc(9);
        pong[0] = 0x05;
        pong.writeBigUInt64BE(BigInt(Date.now()), 1);
        ws.send(pong, { binary: true });
        return clientKeyRef;
    }

    // Rate limiting for binary audio/video/signal
    const rateLimitType = msgType === 0x01 ? 'audio' : msgType === 0x02 ? 'video' : msgType === 0x03 ? 'signal' : null;
    if (rateLimitType && !checkRateLimit(senderKey, rateLimitType)) {
        return clientKeyRef; // Silently drop rate-limited binary packets
    }

    // Audio (0x01), Video (0x02), Signal (0x03): forward to recipient
    if (msgType === 0x01) {
        binaryAudioCount++;
        if (binaryAudioCount % 500 === 1) {
            console.log(`[BIN-AUDIO] #${binaryAudioCount} from ${senderKey.substring(0, 12)} to ${recipientKey.substring(0, 12)} payload:${payload.length}B`);
        }
    }
    if (msgType === 0x02) {
        binaryVideoCount++;
        if (binaryVideoCount % 100 === 1) {
            console.log(`[BIN-VIDEO] #${binaryVideoCount} from ${senderKey.substring(0, 12)} to ${recipientKey.substring(0, 12)} payload:${payload.length}B`);
        }
    }

    // ALWAYS buffer for HTTP polling fallback (even when WS is open)
    // This ensures seamless fallback if the recipient WS dies silently
    bufferBinaryPacket(msgType, recipientKey, senderKey, callId, payload);

    // Also send via WS for lowest latency (best-effort)
    const recipientWs = wsConnections.get(recipientKey);
    if (recipientWs?.readyState === WebSocket.OPEN) {
        const senderBuf = Buffer.from(senderKey, 'utf8');
        const callIdBuf = Buffer.from(callId, 'utf8');
        const fwd = Buffer.alloc(1 + 1 + senderBuf.length + 1 + callIdBuf.length + payload.length);
        let fwdOffset = 0;
        fwd[fwdOffset++] = msgType;
        fwd[fwdOffset++] = senderBuf.length;
        senderBuf.copy(fwd, fwdOffset); fwdOffset += senderBuf.length;
        fwd[fwdOffset++] = callIdBuf.length;
        callIdBuf.copy(fwd, fwdOffset); fwdOffset += callIdBuf.length;
        payload.copy(fwd, fwdOffset);
        recipientWs.send(fwd, { binary: true }, (err) => {
            if (err) console.log('[BIN] WS send error: ' + err.message);
        });
    }

    return clientKeyRef;
}

function bufferBinaryPacket(msgType, recipientKey, senderKey, callId, payload) {
    const payloadBase64 = payload.toString('base64');
    if (msgType === 0x01) {
        if (!audioBuffers.has(recipientKey)) audioBuffers.set(recipientKey, []);
        const buf = audioBuffers.get(recipientKey);
        buf.push({ sender: senderKey, audio: payloadBase64, callId, timestamp: Date.now() });
        while (buf.length > 100) buf.shift();
    } else if (msgType === 0x02) {
        if (!videoBuffers.has(recipientKey)) videoBuffers.set(recipientKey, []);
        const buf = videoBuffers.get(recipientKey);
        buf.push({ sender: senderKey, video: payloadBase64, callId, timestamp: Date.now() });
        while (buf.length > 300) buf.shift();
    } else if (msgType === 0x03) {
        if (!pendingCallSignals.has(recipientKey)) pendingCallSignals.set(recipientKey, []);
        pendingCallSignals.get(recipientKey).push({ sender: senderKey, signal: payloadBase64, callId, timestamp: Date.now() });
    }
}

// ============================================================
// WebSocket Connection Handler
// ============================================================
wss.on('connection', (ws) => {
    let clientKey = null;
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('message', (message, isBinary) => {
        // Binary frame: new optimized protocol
        if (isBinary) {
            clientKey = handleBinaryMessage(ws, clientKey, message);
            return;
        }
        // Text frame: legacy JSON protocol (backward compatible)
        try {
            const data = JSON.parse(message);
            if (data.type === 'register') {
                clientKey = normalizeKey(data.publicKey);
                wsConnections.set(clientKey, ws);
                ws.send(JSON.stringify({ type: 'registered', success: true }));
            } else if (data.type === 'audio') {
                // Verify WS signature
                const sigResult = verifyWebSocketSignature(data);
                if (!sigResult.valid) {
                    ws.send(JSON.stringify({ type: 'error', message: 'Invalid signature' }));
                    return;
                }
                // Rate limiting
                if (!checkRateLimit(clientKey, 'audio')) {
                    ws.send(JSON.stringify({ type: 'error', message: 'Rate limit exceeded' }));
                    return;
                }
                const recipient = normalizeKey(data.recipient);
                const recipientWs = wsConnections.get(recipient);
                audioPacketCount++;
                if (audioPacketCount % 100 === 1) {
                    console.log(`[AUDIO] #${audioPacketCount} from ${clientKey?.substring(0,12)} to ${recipient?.substring(0,12)} - ${recipientWs ? 'ws' : 'buffer'}`);
                }
                // ALWAYS buffer audio for HTTP polling fallback
                if (!audioBuffers.has(recipient)) audioBuffers.set(recipient, []);
                const abuf = audioBuffers.get(recipient);
                abuf.push({ sender: clientKey, audio: data.audio, callId: data.callId, timestamp: Date.now() });
                while (abuf.length > 100) abuf.shift();
                // Also send via WS for lowest latency (best-effort)
                if (recipientWs?.readyState === WebSocket.OPEN) {
                    try {
                        if (recipientWs._binaryMode) {
                            const payloadBuf = Buffer.from(data.audio, 'base64');
                            const senderBuf = Buffer.from(clientKey || '', 'utf8');
                            const callIdBuf = Buffer.from(data.callId || '', 'utf8');
                            const fwd = Buffer.alloc(1 + 1 + senderBuf.length + 1 + callIdBuf.length + payloadBuf.length);
                            let off = 0;
                            fwd[off++] = 0x01;
                            fwd[off++] = senderBuf.length;
                            senderBuf.copy(fwd, off); off += senderBuf.length;
                            fwd[off++] = callIdBuf.length;
                            callIdBuf.copy(fwd, off); off += callIdBuf.length;
                            payloadBuf.copy(fwd, off);
                            recipientWs.send(fwd, { binary: true }, (err) => { if (err) console.log('[AUDIO] ws binary send error: ' + err.message); });
                        } else {
                            recipientWs.send(JSON.stringify({ type: 'audio', sender: clientKey, audio: data.audio, callId: data.callId, timestamp: Date.now() }), (err) => { if (err) console.log('[AUDIO] ws send error: ' + err.message); });
                        }
                    } catch (e) { console.log('[AUDIO] ws exception: ' + e.message); }
                }
            } else if (data.type === 'video') {
                // Verify WS signature
                const sigResult = verifyWebSocketSignature(data);
                if (!sigResult.valid) {
                    ws.send(JSON.stringify({ type: 'error', message: 'Invalid signature' }));
                    return;
                }
                // Rate limiting
                if (!checkRateLimit(clientKey, 'video')) {
                    ws.send(JSON.stringify({ type: 'error', message: 'Rate limit exceeded' }));
                    return;
                }
                const recipient = normalizeKey(data.recipient);
                const recipientWs = wsConnections.get(recipient);
                videoPacketCount++;
                if (videoPacketCount % 100 === 1) {
                    console.log(`[VIDEO] #${videoPacketCount} from ${clientKey?.substring(0,12)} to ${recipient?.substring(0,12)} - ${recipientWs ? 'ws' : 'buffer'}`);
                }
                // ALWAYS buffer video for HTTP polling fallback
                if (!videoBuffers.has(recipient)) videoBuffers.set(recipient, []);
                const vbuf = videoBuffers.get(recipient);
                vbuf.push({ sender: clientKey, video: data.video, callId: data.callId, timestamp: Date.now() });
                while (vbuf.length > 300) vbuf.shift();
                // Also send via WS for lowest latency (best-effort)
                if (recipientWs?.readyState === WebSocket.OPEN) {
                    try {
                        if (recipientWs._binaryMode) {
                            const payloadBuf = Buffer.from(data.video, 'base64');
                            const senderBuf = Buffer.from(clientKey || '', 'utf8');
                            const callIdBuf = Buffer.from(data.callId || '', 'utf8');
                            const fwd = Buffer.alloc(1 + 1 + senderBuf.length + 1 + callIdBuf.length + payloadBuf.length);
                            let off = 0;
                            fwd[off++] = 0x02;
                            fwd[off++] = senderBuf.length;
                            senderBuf.copy(fwd, off); off += senderBuf.length;
                            fwd[off++] = callIdBuf.length;
                            callIdBuf.copy(fwd, off); off += callIdBuf.length;
                            payloadBuf.copy(fwd, off);
                            recipientWs.send(fwd, { binary: true }, (err) => { if (err) console.log('[VIDEO] ws binary send error: ' + err.message); });
                        } else {
                            recipientWs.send(JSON.stringify({ type: 'video', sender: clientKey, video: data.video, callId: data.callId, timestamp: Date.now() }), (err) => { if (err) console.log('[VIDEO] ws send error: ' + err.message); });
                        }
                    } catch (e) { console.log('[VIDEO] ws exception: ' + e.message); }
                }
            } else if (data.type === 'call_signal') {
                // Verify WS signature
                const sigResult = verifyWebSocketSignature(data);
                if (!sigResult.valid) {
                    ws.send(JSON.stringify({ type: 'error', message: 'Invalid signature' }));
                    return;
                }
                // Rate limiting
                if (!checkRateLimit(clientKey, 'signal')) {
                    ws.send(JSON.stringify({ type: 'error', message: 'Rate limit exceeded' }));
                    return;
                }
                const recipient = normalizeKey(data.recipient);
                const recipientWs = wsConnections.get(recipient);
                if (recipientWs?.readyState === WebSocket.OPEN) {
                    recipientWs.send(JSON.stringify({ type: 'call_signal', sender: clientKey, signal: data.signal, callId: data.callId, timestamp: Date.now() }));
                } else {
                    if (!pendingCallSignals.has(recipient)) pendingCallSignals.set(recipient, []);
                    pendingCallSignals.get(recipient).push({ sender: clientKey, signal: data.signal, callId: data.callId, timestamp: Date.now() });
                }
            } else if (data.type === 'ping') {
                ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
            }
        } catch (e) {}
    });
    ws.on('close', () => { if (clientKey) wsConnections.delete(clientKey); });
});

// ============================================================
// WebSocket Heartbeat - detect dead connections every 15s
// ============================================================
setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) {
            console.log('[WS-HEARTBEAT] Terminating dead connection');
            return ws.terminate();
        }
        ws.isAlive = false;
        ws.ping();
    });
}, 15000);

// ============================================================
// REST API endpoints
// ============================================================

// GET /turn-creds — returns short-lived TURN REST credentials.
// Username format: '<unix_ts_expiry>:oshi'   (colon-separated, expiry first)
// Password: base64(hmac_sha1(username, TURN_SECRET))
app.get('/turn-creds', (req, res) => {
    if (!TURN_SECRET) {
        return res.status(503).json({ error: 'TURN_SECRET not configured on server' });
    }
    const expiry = Math.floor(Date.now() / 1000) + TURN_TTL_SECONDS;
    const username = expiry + ':oshi';
    const hmac = crypto.createHmac('sha1', TURN_SECRET);
    hmac.update(username);
    const password = hmac.digest('base64');
    res.json({
        username: username,
        password: password,
        ttl: TURN_TTL_SECONDS,
        uris: [
            'turn:45.67.216.197:3478?transport=udp',
            'turn:45.67.216.197:3478?transport=tcp'
        ]
    });
});

app.get('/health', (req, res) => res.json({
    status: 'ok',
    activeCalls: activeCalls.size,
    wsConnections: wsConnections.size,
    binaryClients: Array.from(wsConnections.values()).filter(ws => ws._binaryMode).length,
    audioPackets: { json: audioPacketCount, binary: binaryAudioCount },
    videoPackets: { json: videoPacketCount, binary: binaryVideoCount },
    signatureRequired: SIGNATURE_REQUIRED
}));
app.post('/ping', (req, res) => res.json({ pong: true, serverTime: Date.now() }));
app.post('/signal', (req, res) => {
    // Verify request signature
    const sigResult = verifySignature(req);
    if (!sigResult.valid) {
        console.log(`  REJECTED signal: ${sigResult.reason}`);
        return res.status(403).json({ error: 'Invalid signature', reason: sigResult.reason });
    }

    const { recipient, sender, signal, callId, type: signalType, senderDeviceId } = req.body;
    if (!recipient || !sender || !signal) return res.status(400).json({ error: 'Missing fields' });
    const nr = normalizeKey(recipient), ns = normalizeKey(sender);

    // Rate limiting
    if (!checkRateLimit(ns, 'signal')) {
        return res.status(429).json({ error: 'Rate limit exceeded', retryAfter: 60 });
    }

    console.log('[SIGNAL] POST from ' + ns.substring(0,12) + ' to ' + nr.substring(0,12) + ' callId:' + (callId || 'none').substring(0,8) + ' sig:' + sigResult.reason);
    const rws = wsConnections.get(nr);
    const wsLive = rws?.readyState === WebSocket.OPEN;
    if (wsLive) {
        console.log('[SIGNAL] Forwarding via WebSocket (recipient connected, binaryMode:' + (rws._binaryMode || false) + ') + queueing as backup');
        rws.send(JSON.stringify({ type: 'call_signal', sender: ns, signal, callId, signalType, timestamp: Date.now() }));
    }
    // 🔧 FIX (callEnd / callRequest lost when WS push fails silently):
    // Always queue the signal so a polling GET can retrieve it as a fallback.
    // The WS push above is best-effort for instant delivery. Client dedups
    // by callId, so duplicate (WS + poll) is harmless. Skip the queue path
    // entirely for callAnsweredElsewhere — handled by the dedicated branch
    // below with its own non-destructive answeredElsewhereMap.
    if (signalType !== 'callAnsweredElsewhere') {
        if (!pendingCallSignals.has(nr)) pendingCallSignals.set(nr, []);
        pendingCallSignals.get(nr).push({ sender: ns, signal, callId, type: signalType, timestamp: Date.now() });
    }
    if (wsLive) {
        return res.json({ success: true, delivery: signalType === 'callAnsweredElsewhere' ? 'websocket' : 'websocket+queued' });
    }
    // Multi-device: For callAnsweredElsewhere, store in non-destructive map
    // so ALL devices get it on next poll (not just the first one)
    if (signalType === 'callAnsweredElsewhere') {
        console.log('[SIGNAL] callAnsweredElsewhere from device ' + (senderDeviceId || 'unknown').substring(0,8));
        if (!answeredElsewhereMap.has(nr)) answeredElsewhereMap.set(nr, []);
        answeredElsewhereMap.get(nr).push({ sender: ns, signal, callId, type: signalType, senderDeviceId: senderDeviceId || '', timestamp: Date.now() });
        // Also try WS forward to any connected device
        if (rws?.readyState === WebSocket.OPEN) {
            rws.send(JSON.stringify({ type: 'call_signal', sender: ns, signal, callId, signalType, timestamp: Date.now() }));
        }
        return res.json({ success: true, delivery: 'multidevice' });
    }
    console.log('[SIGNAL] Queued (recipient not connected via WS)');
    if (!pendingCallSignals.has(nr)) pendingCallSignals.set(nr, []);
    pendingCallSignals.get(nr).push({ sender: ns, signal, callId, type: signalType, timestamp: Date.now() });
    res.json({ success: true, delivery: 'queued' });

    // __CALLEND_PUSH_ROUTE_2026_06_08__
    // Push fallback: branch on signal type.
    //   - call OFFERS (type undefined or 'callRequest'): fire VoIP push so
    //     CallKit rings the recipient.
    //   - call END events (callEnd/callDeclined/callMissed/callTimeout):
    //     fire APNs ALERT push so the app wakes briefly and polls the
    //     queue to pick up the callEnd signal. We must NOT fire VoIP push
    //     here — that would re-ring CallKit for a call that just ended.
    if (callId && !recentPushCallIds.has(callId)) {
        recentPushCallIds.add(callId);
        setTimeout(() => recentPushCallIds.delete(callId), 60000);

        const isTerminalCallEvent = signalType === 'callEnd'
            || signalType === 'callDeclined'
            || signalType === 'callMissed'
            || signalType === 'callTimeout'
            || signalType === 'callCancelled';

        try {
            if (isTerminalCallEvent) {
                // APNs alert path — wake the app to poll, do NOT re-ring.
                const pushBody = JSON.stringify({
                    recipientPublicKey: nr,
                    type: 'callEnd',
                    title: 'Call ended',
                    body: req.body.callerName || 'Caller hung up',
                    data: {
                        callId: callId || '',
                        senderPublicKey: ns,
                        signalType: signalType
                    }
                });
                const pushReq = http.request({
                    hostname: '127.0.0.1',
                    port: 8084,
                    path: '/send-push',
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(pushBody) }
                }, (pushRes) => {
                    let data = '';
                    pushRes.on('data', c => data += c);
                    pushRes.on('end', () => console.log('[CALLEND PUSH] Response:', pushRes.statusCode, data.substring(0, 100)));
                });
                pushReq.on('error', (e) => console.log('[CALLEND PUSH] Error:', e.message));
                pushReq.write(pushBody);
                pushReq.end();
                console.log('[CALLEND PUSH] Triggered APNs alert for signalType=' + signalType + ' callId=' + (callId||'').substring(0,8));
            } else {
                // OFFER path — VoIP push to ring CallKit.
                const pushBody = JSON.stringify({
                    recipientPublicKey: nr,
                    callerPublicKey: ns,
                    callerName: req.body.callerName || 'Incoming Call',
                    callId: callId || '',
                    isVideoCall: req.body.isVideoCall || false
                });
                const pushReq = http.request({
                    hostname: '127.0.0.1',
                    port: 8084,
                    path: '/send-voip-push',
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(pushBody) }
                }, (pushRes) => {
                    let data = '';
                    pushRes.on('data', c => data += c);
                    pushRes.on('end', () => console.log('[PUSH FALLBACK] Response:', pushRes.statusCode, data.substring(0, 100)));
                });
                pushReq.on('error', (e) => console.log('[PUSH FALLBACK] Error:', e.message));
                pushReq.write(pushBody);
                pushReq.end();
                console.log('[PUSH FALLBACK] Triggered VoIP push for queued offer signal (type=' + (signalType||'undefined') + ')');
            }
        } catch (e) {
            console.log('[PUSH FALLBACK] Failed:', e.message);
        }
    }
});
app.get(/^\/signals?\/(.+)$/, (req, res) => {
    let r = normalizeKey(decodeURIComponent(req.params[0]));
    const signals = pendingCallSignals.get(r) || [];
    if (signals.length > 0) {
        console.log('[SIGNAL] GET poll by ' + r.substring(0,12) + ' -> returning ' + signals.length + ' signal(s)');
        pendingCallSignals.set(r, []);
    }
    // Multi-device: Include answered-elsewhere signals, expire after 30s, clear after delivery
    const rawAnswered = answeredElsewhereMap.get(r) || [];
    const answered = rawAnswered.filter(s => Date.now() - s.timestamp < 30000);
    if (answered.length > 0) {
        // Clear after first delivery to prevent infinite re-delivery
        answeredElsewhereMap.delete(r);
    } else {
        answeredElsewhereMap.delete(r);
    }
    const allSignals = [...signals, ...answered];
    res.json(allSignals);
});
app.post('/audio', (req, res) => {
    // Verify request signature
    const sigResult = verifySignature(req);
    if (!sigResult.valid) {
        return res.status(403).json({ error: 'Invalid signature', reason: sigResult.reason });
    }

    const { recipient, sender, audio, callId } = req.body;
    if (!recipient || !sender || !audio) return res.status(400).json({ error: 'Missing fields' });
    const nr = normalizeKey(recipient), ns = normalizeKey(sender);

    // Rate limiting
    if (!checkRateLimit(ns, 'audio')) {
        return res.status(429).json({ error: 'Rate limit exceeded', retryAfter: 1 });
    }

    // ALWAYS buffer for HTTP polling fallback
    if (!audioBuffers.has(nr)) audioBuffers.set(nr, []);
    const buf = audioBuffers.get(nr);
    buf.push({ sender: ns, audio, callId, timestamp: Date.now() });
    while (buf.length > 100) buf.shift();
    // Also send via WS for lowest latency
    const rws = wsConnections.get(nr);
    if (rws?.readyState === WebSocket.OPEN) {
        rws.send(JSON.stringify({ type: 'audio', sender: ns, audio, callId, timestamp: Date.now() }));
        return res.json({ success: true, delivery: 'websocket+buffered' });
    }
    res.json({ success: true, delivery: 'buffered' });

    // __AUDIO_WAKE_PUSH_2026_06_09__
    // Recipient's WebSocket is not OPEN — they're either backgrounded
    // (URLSession got suspended) or fully disconnected. Audio is being
    // stored in the server buffer awaiting a /audio GET poll, which is
    // far too slow for real-time voice. Fire a silent (content-available)
    // APNs wake-push so iOS brings the app forward enough to reconnect WS.
    {
        const now = Date.now();
        const last = recentWsWakePushes.get(nr) || 0;
        if (now - last >= WS_WAKE_THROTTLE_MS) {
            recentWsWakePushes.set(nr, now);
            try {
                const wakeBody = JSON.stringify({
                    recipientPublicKey: nr,
                    callId: callId,
                    senderPublicKey: ns
                });
                const wakeReq = http.request({
                    hostname: '127.0.0.1',
                    port: 8084,
                    path: '/send-call-keepalive',
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(wakeBody) }
                }, (r) => { let d = ''; r.on('data', c => d += c); r.on('end', () => {}); });
                wakeReq.on('error', (e) => console.log('[WS-WAKE] error: ' + e.message));
                wakeReq.write(wakeBody);
                wakeReq.end();
                console.log('[WS-WAKE] fired for ' + nr.substring(0,12) + '... callId=' + (callId||'').substring(0,8));
            } catch (e) {
                console.log('[WS-WAKE] failed: ' + e.message);
            }
        }
    }
});
app.get(/^\/audio\/(.+)$/, (req, res) => {
    let r = normalizeKey(decodeURIComponent(req.params[0]));
    const buf = audioBuffers.get(r) || [];
    audioBuffers.set(r, []);
    res.json(buf);
});
app.post('/video', (req, res) => {
    // Verify request signature
    const sigResult = verifySignature(req);
    if (!sigResult.valid) {
        return res.status(403).json({ error: 'Invalid signature', reason: sigResult.reason });
    }

    const { recipient, sender, video, callId } = req.body;
    if (!recipient || !sender || !video) return res.status(400).json({ error: 'Missing fields' });
    const nr = normalizeKey(recipient), ns = normalizeKey(sender);

    // Rate limiting
    if (!checkRateLimit(ns, 'video')) {
        return res.status(429).json({ error: 'Rate limit exceeded', retryAfter: 1 });
    }

    // ALWAYS buffer for HTTP polling fallback
    if (!videoBuffers.has(nr)) videoBuffers.set(nr, []);
    const buf = videoBuffers.get(nr);
    buf.push({ sender: ns, video, callId, timestamp: Date.now() });
    while (buf.length > 300) buf.shift();
    // Also send via WS for lowest latency
    const rws = wsConnections.get(nr);
    if (rws?.readyState === WebSocket.OPEN) {
        rws.send(JSON.stringify({ type: 'video', sender: ns, video, callId, timestamp: Date.now() }));
        return res.json({ success: true, delivery: 'websocket+buffered' });
    }
    res.json({ success: true, delivery: 'buffered' });
});
app.get(/^\/video\/(.+)$/, (req, res) => {
    let r = normalizeKey(decodeURIComponent(req.params[0]));
    const buf = videoBuffers.get(r) || [];
    videoBuffers.set(r, []);
    res.json(buf);
});
app.post('/register', (req, res) => {
    const { callId, participant1, participant2 } = req.body;
    if (!callId) return res.status(400).json({ error: 'Missing fields' });
    activeCalls.set(callId, { participants: [normalizeKey(participant1), normalizeKey(participant2)], createdAt: Date.now() });
    res.json({ success: true });
});
app.post('/api/call/register', (req, res) => {
    const { callId, participant1, participant2 } = req.body;
    if (!callId) return res.status(400).json({ error: 'Missing fields' });
    activeCalls.set(callId, { participants: [normalizeKey(participant1), normalizeKey(participant2)], createdAt: Date.now() });
    res.json({ success: true });
});
app.post('/api/call/end', (req, res) => { if (req.body.callId) activeCalls.delete(req.body.callId); res.json({ success: true }); });

// Cleanup: stale buffers + stale calls + dead WS connections
setInterval(() => {
    const now = Date.now();
    audioBuffers.forEach((b, k) => audioBuffers.set(k, b.filter(p => now - p.timestamp < 15000)));
    videoBuffers.forEach((b, k) => videoBuffers.set(k, b.filter(p => now - p.timestamp < 10000)));
    pendingCallSignals.forEach((s, k) => pendingCallSignals.set(k, s.filter(p => now - p.timestamp < 60000)));
    // Cleanup answered-elsewhere signals (60s TTL)
    answeredElsewhereMap.forEach((s, k) => {
        const filtered = s.filter(p => now - p.timestamp < 60000);
        if (filtered.length > 0) answeredElsewhereMap.set(k, filtered);
        else answeredElsewhereMap.delete(k);
    });
    // Clean stale active calls (older than 1 hour)
    activeCalls.forEach((call, id) => {
        if (now - call.createdAt > 3600000) activeCalls.delete(id);
    });
    // Clean dead WebSocket connections
    wsConnections.forEach((ws, key) => {
        if (ws.readyState !== WebSocket.OPEN) wsConnections.delete(key);
    });
}, 30000);

server.listen(PORT, () => console.log('🎙️ Call Server on port ' + PORT + ' (binary+JSON dual mode, security hardened)'));

// Express error handler — catch 'request aborted' from cancelled HTTP requests
app.use((err, req, res, next) => {
    if (err.type === 'entity.parse.failed' || (err.message && err.message.includes('request aborted'))) {
        return res.status(400).end();
    }
    console.error('[ERROR]', err.message);
    res.status(500).json({ error: 'Internal server error' });
});
