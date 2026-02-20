# OSHI Bot SDK - Python

A Python client for the OSHI Bot API. Create bots that send messages to groups and channels on OSHI Messenger, similar to Telegram's Bot API.

**Full API docs:** https://oshi-messenger.com/bot-api
**Base URL:** `https://oshi-messenger.com/api/bot/`

## Installation

No package manager needed. Just download the SDK file:

```bash
curl -O https://raw.githubusercontent.com/nicmusic-music/OSHI-public/main/sdk/oshi_bot.py
```

Or copy `oshi_bot.py` into your project directory.

**Requirements:** Python 3.6+ and the `requests` library (`pip install requests`).

## Quick Start

1. Create a bot in the OSHI app: **Portal > Bots > Create**
2. Copy the bot token shown after creation (32-character hex string)
3. Assign the bot to a group in the app
4. Send messages from Python:

```python
from oshi_bot import OshiBot

bot = OshiBot(token="a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6")
bot.send("550e8400-e29b-41d4-a716-446655440000", "Hello from OSHI Bot!")
```

## OshiBot Class

### Constructor

```python
bot = OshiBot(
    token="YOUR_BOT_TOKEN",           # Required: 32-char hex token from OSHI app
    base_url="https://oshi-messenger.com",  # Optional: API base URL
    timeout=10,                        # Optional: request timeout in seconds
    auto_retry=True,                   # Optional: retry on rate limit with backoff
)
```

### Core Methods

#### `bot.send(group_id, content) -> dict`

Send a message to a specific group.

```python
result = bot.send("550e8400-e29b-41d4-a716-446655440000", "Market update: BTC +2.5%")
print(f"Delivered to {result['delivered']} of {result['totalMembers']} members")
# Returns: {"success": True, "delivered": 5, "totalMembers": 5, "messageId": "...", "groupName": "..."}
```

**Raises:**
- `OshiAuthError` -- invalid bot token (HTTP 401)
- `OshiGroupError` -- bot not assigned to this group (HTTP 403)
- `OshiRateLimitError` -- rate limit exceeded (HTTP 429)

#### `bot.info() -> dict`

Get bot info, stats, and registered groups.

```python
info = bot.info()
print(f"Bot: {info['bot']['botName']}")
for g in info['bot']['groups']:
    print(f"  Group: {g['name']} ({g['memberCount']} members)")
```

#### `bot.register(bot_name, owner_public_key, groups=None) -> dict`

Register a bot with the server programmatically. Usually done via the OSHI app, but available for automation.

```python
bot.register("My Alert Bot", "base64url_pubkey", groups=[
    {"id": "uuid-here", "name": "Alerts Channel", "members": ["key1", "key2"]}
])
```

#### `bot.update_groups(groups) -> dict`

Update the bot's group assignments on the server.

```python
bot.update_groups([
    {"id": "uuid-1", "name": "Group A", "members": ["key1"]},
    {"id": "uuid-2", "name": "Group B", "members": ["key2", "key3"]},
])
```

#### `bot.unregister() -> dict`

Remove the bot from the server registry.

```python
bot.unregister()
```

#### `bot.list_bots() -> dict`

List all registered bots on the server (admin endpoint).

```python
result = bot.list_bots()
for b in result["bots"]:
    print(f"{b['botName']} - {b['messagesSent']} messages sent")
```

### Convenience Methods

#### `bot.send_to_all_groups(content) -> list`

Send a message to every group the bot is assigned to.

```python
results = bot.send_to_all_groups("System maintenance in 5 minutes")
for r in results:
    print(f"  {r.get('groupName')}: {r.get('delivered')} delivered")
```

#### `bot.get_groups() -> list`

Get a list of groups this bot is assigned to.

```python
groups = bot.get_groups()
for g in groups:
    print(f"{g['name']} (id: {g['id']}, members: {g.get('memberCount', '?')})")
```

#### `bot.get_stats() -> dict`

Get bot message statistics.

```python
stats = bot.get_stats()
print(f"Messages sent: {stats.get('messagesSent', 0)}")
print(f"Last activity: {stats.get('lastActivity', 'never')}")
```

## OshiMoltBotBridge Class

Bridge class for integrating OSHI bots with the [MoltBot](https://github.com/nicmusic-music) multi-platform bot framework. Use OSHI as a transport/output plugin for MoltBot.

```python
from oshi_bot import OshiMoltBotBridge

bridge = OshiMoltBotBridge(
    oshi_token="YOUR_OSHI_BOT_TOKEN",
    default_group="GROUP_UUID",
)

# Send to default group
bridge.send("Hello from MoltBot!")

# Send to a specific group
bridge.send("Alert!", group_id="other-group-uuid")

# Broadcast to all groups
bridge.broadcast("System announcement")

# Get bot info
info = bridge.get_info()
```

### Using as a MoltBot plugin

```python
class MyMoltBot:
    def __init__(self):
        self.outputs = [bridge]

    def broadcast(self, message):
        for output in self.outputs:
            output.send(message)
```

### MoltBot handler pattern

```python
bridge = OshiMoltBotBridge(oshi_token="...", default_group="...")

def on_message(event):
    response = f"Received: {event['text']}"
    bridge.send(response)
```

## Exception Handling

The SDK defines a hierarchy of exceptions for error handling:

| Exception | HTTP Code | Description |
|---|---|---|
| `OshiBotError` | various | Base exception for all SDK errors |
| `OshiAuthError` | 401 | Invalid or missing bot token |
| `OshiRateLimitError` | 429 | Rate limit exceeded (60 messages/min) |
| `OshiGroupError` | 403 | Bot not assigned to the target group |

```python
from oshi_bot import OshiBot, OshiAuthError, OshiRateLimitError, OshiGroupError

bot = OshiBot(token="YOUR_TOKEN")
try:
    bot.send("GROUP_ID", "Hello!")
except OshiAuthError:
    print("Invalid token -- check your bot token in the OSHI app")
except OshiGroupError:
    print("Bot not assigned to this group -- add it in the OSHI app")
except OshiRateLimitError:
    print("Slow down -- max 60 messages per minute")
```

## CLI Usage

The SDK doubles as a command-line tool:

```bash
# Send a message
python oshi_bot.py send TOKEN GROUP_ID "Hello from CLI!"

# Get bot info
python oshi_bot.py info TOKEN

# List bot's groups
python oshi_bot.py groups TOKEN

# Get bot stats
python oshi_bot.py stats TOKEN

# List all bots (admin)
python oshi_bot.py list

# Show help
python oshi_bot.py help
```

## Rate Limits

- **60 messages per minute** per bot token
- When `auto_retry=True` (default), the SDK waits 5 seconds and retries once on HTTP 429
- If still rate-limited after retry, `OshiRateLimitError` is raised

## License

MIT License. See [LICENSE](../LICENSE) for details.
