# Bot API Reference

OSHI Messenger provides a Bot API for building automated integrations, similar to Telegram's Bot API. Bots can send messages to groups, respond to events, and automate workflows.

**Full interactive docs:** https://oshi-messenger.com/bot-api
**Base URL:** `https://oshi-messenger.com/api/bot/`
**Python SDK:** [`sdk/oshi_bot.py`](../sdk/oshi_bot.py) ([SDK documentation](../sdk/README.md))

## Getting Started

1. Open the OSHI app and navigate to **Portal > Bots > Create**
2. Choose a bot type (see [Bot Types](#bot-types) below)
3. Copy the generated bot token (32-character hex string)
4. Assign the bot to one or more groups
5. Use the API or Python SDK to send messages

## Authentication

All API requests are authenticated via the bot token. Include it in the request body (POST) or as a query parameter (GET/DELETE):

```bash
# POST -- token in JSON body
curl -X POST https://oshi-messenger.com/api/bot/send \
  -H "Content-Type: application/json" \
  -d '{"token": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6", "groupId": "...", "content": "Hello!"}'

# GET -- token as query parameter
curl "https://oshi-messenger.com/api/bot/info?token=a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
```

## Endpoints

### POST /api/bot/send

Send a message to a group.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | Bot token (32-char hex) |
| `groupId` | string | yes | UUID of the target group |
| `content` | string | yes | Message text to send |

**Response (200):**

```json
{
  "success": true,
  "delivered": 5,
  "totalMembers": 5,
  "messageId": "msg-uuid",
  "groupName": "My Group"
}
```

**Error responses:**

| HTTP Code | Error | Description |
|---|---|---|
| 401 | `Invalid bot token` | Token is missing, malformed, or revoked |
| 403 | `Bot not assigned to group` | Bot has not been added to this group |
| 429 | `Rate limit exceeded` | More than 60 messages/minute |

---

### POST /api/bot/register

Register a new bot with the server. This is typically done automatically by the OSHI app when creating a bot, but is available for programmatic bot creation.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | Bot token |
| `botName` | string | yes | Display name for the bot |
| `ownerPublicKey` | string | yes | Creator's public key (base64url-encoded) |
| `groups` | array | no | Initial group assignments (see below) |

**Group object:**

```json
{
  "id": "group-uuid",
  "name": "Group Name",
  "members": ["pubkey1", "pubkey2"]
}
```

**Response (200):**

```json
{
  "success": true,
  "message": "Bot registered successfully"
}
```

---

### POST /api/bot/update-groups

Update the bot's group assignments.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | Bot token |
| `groups` | array | yes | Updated list of group objects |

**Response (200):**

```json
{
  "success": true,
  "message": "Groups updated"
}
```

---

### GET /api/bot/info

Get bot information, group assignments, and statistics.

**Query parameters:**

| Field | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | Bot token |

**Response (200):**

```json
{
  "bot": {
    "botName": "My Alert Bot",
    "registeredAt": "2025-01-15T10:30:00Z",
    "groups": [
      {
        "id": "group-uuid",
        "name": "Alerts Channel",
        "memberCount": 12
      }
    ],
    "stats": {
      "messagesSent": 142,
      "lastActivity": "2025-06-20T14:22:00Z"
    }
  }
}
```

---

### DELETE /api/bot/unregister

Remove the bot from the server registry. This is irreversible -- the bot token will stop working.

**Query parameters:**

| Field | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | Bot token |

**Response (200):**

```json
{
  "success": true,
  "message": "Bot unregistered"
}
```

---

### GET /api/bot/list

List all registered bots on the server. This is an admin endpoint.

**Response (200):**

```json
{
  "count": 3,
  "bots": [
    {
      "botName": "Alert Bot",
      "tokenPrefix": "a1b2c3d4",
      "messagesSent": 142,
      "groups": 2
    }
  ]
}
```

## Bot Types

When creating a bot in the OSHI app, you choose one of four types that determine its behavior and capabilities:

| Type | Description | Use Case |
|---|---|---|
| **automation** | Sends messages on demand via API calls | Price alerts, CI/CD notifications, monitoring |
| **webhook** | Triggered by incoming webhook HTTP requests | GitHub events, payment notifications, form submissions |
| **scheduled** | Runs on a cron-like schedule | Daily reports, recurring reminders, periodic data fetches |
| **moderator** | Responds to messages and manages group content | Auto-moderation, FAQ responses, command handling |

### Automation Bots

The simplest type. Your external system calls the `/api/bot/send` endpoint whenever it needs to post a message. No server-side logic runs on OSHI's infrastructure.

### Webhook Bots

OSHI provides a unique webhook URL for each webhook bot. When an external service sends an HTTP POST to this URL, the bot forwards the payload as a message to its assigned groups.

### Scheduled Bots

Define a schedule (cron expression or interval) when creating the bot. At each scheduled time, the bot executes its configured action (e.g., fetching data from an API and posting a summary).

### Moderator Bots

These bots receive copies of messages sent to their groups and can respond automatically. Use moderator bots for:

- Keyword-based auto-replies
- Command processing (e.g., `/help`, `/status`)
- Content filtering and moderation
- Welcome messages for new members

## Trigger Types

Bots can be configured to activate on different trigger types:

| Trigger | Description | Example |
|---|---|---|
| **keywords** | Activates when a message contains specific words | `"price"`, `"alert"` |
| **commands** | Activates on slash commands | `/help`, `/status`, `/subscribe` |
| **schedules** | Activates on a time schedule | `0 9 * * 1-5` (weekdays at 9 AM) |
| **events** | Activates on group events | Member joined, member left, group created |
| **regex** | Activates when a message matches a regular expression | `\$[A-Z]{2,5}` (stock ticker pattern) |

Triggers can be combined. For example, a moderator bot might respond to both the `/help` command and the keyword "help".

## Python SDK

The Python SDK (`oshi_bot.py`) wraps all API endpoints in a clean interface:

```python
from oshi_bot import OshiBot

bot = OshiBot(token="a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6")

# Send a message
result = bot.send("group-uuid", "Hello!")
print(f"Delivered to {result['delivered']} members")

# Get bot info
info = bot.info()

# Send to all groups
bot.send_to_all_groups("Broadcast message")

# List groups
for g in bot.get_groups():
    print(f"{g['name']}: {g['memberCount']} members")
```

### MoltBot Integration

For multi-platform bot frameworks, use the bridge class:

```python
from oshi_bot import OshiMoltBotBridge

bridge = OshiMoltBotBridge(
    oshi_token="YOUR_TOKEN",
    default_group="GROUP_UUID",
)

bridge.send("Hello from MoltBot!")
bridge.broadcast("Announcement to all groups")
```

See the [SDK README](../sdk/README.md) for complete documentation.

### CLI Usage

```bash
python oshi_bot.py send TOKEN GROUP_ID "message"
python oshi_bot.py info TOKEN
python oshi_bot.py groups TOKEN
python oshi_bot.py stats TOKEN
python oshi_bot.py list
```

## Rate Limits

| Limit | Value |
|---|---|
| Messages per minute per token | 60 |
| Request timeout | 30 seconds server-side |
| Max message content length | 4096 characters |
| Max groups per bot | 50 |

When the rate limit is exceeded, the API returns HTTP 429. The Python SDK (`auto_retry=True` by default) waits 5 seconds and retries once automatically.

## Error Handling

All error responses follow this format:

```json
{
  "error": "Human-readable error message"
}
```

| HTTP Code | Meaning | Action |
|---|---|---|
| 200 | Success | -- |
| 401 | Invalid token | Check bot token; regenerate if needed |
| 403 | Not authorized for group | Assign bot to group in OSHI app |
| 404 | Endpoint or resource not found | Check URL and parameters |
| 429 | Rate limit exceeded | Wait and retry (SDK does this automatically) |
| 500 | Server error | Retry after a short delay |

## Webhook Payload Format

When external services send data to a webhook bot's URL, the payload is forwarded to the bot's groups. The webhook accepts any JSON body and formats it as a message:

```bash
curl -X POST https://oshi-messenger.com/api/bot/webhook/BOT_TOKEN \
  -H "Content-Type: application/json" \
  -d '{"text": "Deploy succeeded", "repo": "myapp", "branch": "main"}'
```

The bot formats the JSON into a readable message and sends it to all assigned groups.
