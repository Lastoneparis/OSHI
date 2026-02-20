#!/usr/bin/env python3
"""
OSHI Bot SDK - Python client for the OSHI Bot API

Create and manage bots on OSHI Messenger, similar to Telegram's Bot API.
Bots can send messages to groups/channels via their unique token.

Quick Start:
    from oshi_bot import OshiBot

    bot = OshiBot(token="YOUR_BOT_TOKEN")
    bot.send("GROUP_ID", "Hello from Python!")

Full Workflow:
    1. Create a bot in the OSHI app (Portal > Bots > Create)
    2. Copy the bot token shown after creation
    3. Assign the bot to a group in the app
    4. Use this SDK to send messages from Python

Compatible with MoltBot: use OshiBot as a transport layer.

API Reference:
    https://oshi-messenger.com/api/bot/

Author: OSHI Team
"""

import requests
import time
import logging
from typing import Optional

logger = logging.getLogger("oshi_bot")


class OshiBotError(Exception):
    """Base exception for OSHI Bot SDK errors."""
    pass


class OshiAuthError(OshiBotError):
    """Invalid or missing bot token."""
    pass


class OshiRateLimitError(OshiBotError):
    """Rate limit exceeded (60 messages/minute)."""
    pass


class OshiGroupError(OshiBotError):
    """Bot not assigned to the target group."""
    pass


class OshiBot:
    """
    OSHI Bot client for sending messages to groups/channels.

    Args:
        token: Bot token (32-char hex string from OSHI app)
        base_url: API base URL (default: https://oshi-messenger.com)
        timeout: Request timeout in seconds (default: 10)
        auto_retry: Retry on rate limit with backoff (default: True)

    Example:
        bot = OshiBot(token="a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6")
        result = bot.send("550e8400-e29b-41d4-a716-446655440000", "Hello!")
        print(f"Delivered to {result['delivered']} members")
    """

    def __init__(
        self,
        token: str,
        base_url: str = "https://oshi-messenger.com",
        timeout: int = 10,
        auto_retry: bool = True,
    ):
        self.token = token
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.auto_retry = auto_retry
        self._session = requests.Session()
        self._session.headers.update({
            "Content-Type": "application/json",
            "User-Agent": "OSHI-Bot-SDK/1.0 Python",
        })

    # ------------------------------------------------------------------
    # Core API Methods
    # ------------------------------------------------------------------

    def send(self, group_id: str, content: str) -> dict:
        """
        Send a message to a group via the bot.

        Args:
            group_id: UUID of the target group (visible in OSHI app)
            content: Message text to send

        Returns:
            dict with keys: success, delivered, totalMembers, messageId, groupName

        Raises:
            OshiAuthError: Invalid bot token
            OshiGroupError: Bot not assigned to this group
            OshiRateLimitError: Rate limit exceeded

        Example:
            result = bot.send("550e8400-...", "Market update: BTC +2.5%")
            print(f"Sent to {result['delivered']} members")
        """
        payload = {
            "token": self.token,
            "groupId": group_id,
            "content": content,
        }
        return self._request("POST", "/api/bot/send", json=payload)

    def info(self) -> dict:
        """
        Get bot info, stats, and registered groups.

        Returns:
            dict with keys: botName, registeredAt, groups[], stats{}

        Example:
            info = bot.info()
            print(f"Bot: {info['bot']['botName']}")
            for g in info['bot']['groups']:
                print(f"  Group: {g['name']} ({g['memberCount']} members)")
        """
        return self._request("GET", "/api/bot/info", params={"token": self.token})

    def register(self, bot_name: str, owner_public_key: str, groups: Optional[list] = None) -> dict:
        """
        Register the bot with the server. Usually done automatically by the OSHI app,
        but can be called manually for programmatic bot creation.

        Args:
            bot_name: Display name for the bot
            owner_public_key: Creator's public key (base64url)
            groups: Optional list of group dicts: [{"id": "uuid", "name": "...", "members": ["pubkey"]}]

        Returns:
            dict with registration confirmation

        Example:
            bot.register("My Alert Bot", "base64url_pubkey", groups=[
                {"id": "uuid-here", "name": "Alerts Channel", "members": ["key1", "key2"]}
            ])
        """
        payload = {
            "token": self.token,
            "botName": bot_name,
            "ownerPublicKey": owner_public_key,
            "groups": groups or [],
        }
        return self._request("POST", "/api/bot/register", json=payload)

    def update_groups(self, groups: list) -> dict:
        """
        Update the bot's group assignments on the server.

        Args:
            groups: List of group dicts: [{"id": "uuid", "name": "...", "members": ["pubkey"]}]

        Returns:
            dict with update confirmation
        """
        payload = {
            "token": self.token,
            "groups": groups,
        }
        return self._request("POST", "/api/bot/update-groups", json=payload)

    def unregister(self) -> dict:
        """
        Remove the bot from the server registry.

        Returns:
            dict with unregister confirmation
        """
        return self._request("DELETE", "/api/bot/unregister", params={"token": self.token})

    def list_bots(self) -> dict:
        """
        List all registered bots on the server (admin).

        Returns:
            dict with count and bots array
        """
        return self._request("GET", "/api/bot/list")

    # ------------------------------------------------------------------
    # Convenience Methods
    # ------------------------------------------------------------------

    def send_to_all_groups(self, content: str) -> list:
        """
        Send a message to ALL groups the bot is assigned to.

        Args:
            content: Message text

        Returns:
            List of result dicts (one per group)

        Example:
            results = bot.send_to_all_groups("System maintenance in 5 minutes")
            for r in results:
                print(f"  {r.get('groupName')}: {r.get('delivered')} delivered")
        """
        bot_info = self.info()
        results = []
        for group in bot_info.get("bot", {}).get("groups", []):
            try:
                result = self.send(group["id"], content)
                results.append(result)
            except OshiBotError as e:
                results.append({"success": False, "groupId": group["id"], "error": str(e)})
        return results

    def get_groups(self) -> list:
        """
        Get list of groups this bot is assigned to.

        Returns:
            List of group dicts: [{"id": "uuid", "name": "...", "memberCount": N}]
        """
        bot_info = self.info()
        return bot_info.get("bot", {}).get("groups", [])

    def get_stats(self) -> dict:
        """
        Get bot message statistics.

        Returns:
            dict with messagesSent and lastActivity
        """
        bot_info = self.info()
        return bot_info.get("bot", {}).get("stats", {})

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _request(self, method: str, endpoint: str, **kwargs) -> dict:
        url = f"{self.base_url}{endpoint}"
        kwargs.setdefault("timeout", self.timeout)

        try:
            resp = self._session.request(method, url, **kwargs)
        except requests.ConnectionError:
            raise OshiBotError(f"Connection failed: {url}")
        except requests.Timeout:
            raise OshiBotError(f"Request timed out after {self.timeout}s")

        # Handle rate limiting with auto-retry
        if resp.status_code == 429:
            if self.auto_retry:
                logger.warning("Rate limited, waiting 5s before retry...")
                time.sleep(5)
                try:
                    resp = self._session.request(method, url, **kwargs)
                except Exception:
                    pass
            if resp.status_code == 429:
                raise OshiRateLimitError("Rate limit: max 60 messages/minute")

        # Parse response
        try:
            data = resp.json()
        except ValueError:
            raise OshiBotError(f"Invalid response from server (HTTP {resp.status_code})")

        # Handle errors
        if resp.status_code == 401:
            raise OshiAuthError(data.get("error", "Invalid bot token"))
        if resp.status_code == 403:
            raise OshiGroupError(data.get("error", "Bot not assigned to group"))
        if resp.status_code == 404:
            raise OshiBotError(data.get("error", "Not found"))
        if resp.status_code >= 400:
            raise OshiBotError(data.get("error", f"HTTP {resp.status_code}"))

        return data

    def __repr__(self):
        return f"OshiBot(token='{self.token[:8]}...', base_url='{self.base_url}')"


# ============================================================================
# MoltBot Integration Bridge
# ============================================================================

class OshiMoltBotBridge:
    """
    Bridge class to connect OSHI bots with the MoltBot framework.

    MoltBot is a multi-platform bot framework. This bridge adapts OSHI's
    Bot API to work as a MoltBot transport/output plugin.

    Usage with MoltBot:
        from oshi_bot import OshiMoltBotBridge

        bridge = OshiMoltBotBridge(
            oshi_token="YOUR_OSHI_BOT_TOKEN",
            default_group="GROUP_UUID"
        )

        # Use as standalone
        bridge.send("Hello from MoltBot!")

        # Use as MoltBot plugin
        class MyMoltBot:
            def __init__(self):
                self.outputs = [bridge]

            def broadcast(self, message):
                for output in self.outputs:
                    output.send(message)

    MoltBot Handler Pattern:
        bridge = OshiMoltBotBridge(oshi_token="...", default_group="...")

        def on_message(event):
            # Process event from MoltBot
            response = f"Received: {event['text']}"
            bridge.send(response)
            bridge.send(response, group_id="other-group-uuid")
    """

    def __init__(
        self,
        oshi_token: str,
        default_group: Optional[str] = None,
        base_url: str = "https://oshi-messenger.com",
    ):
        self.bot = OshiBot(token=oshi_token, base_url=base_url)
        self.default_group = default_group

    def send(self, message: str, group_id: Optional[str] = None) -> dict:
        """Send message via OSHI bot. Uses default_group if group_id not specified."""
        target = group_id or self.default_group
        if not target:
            raise OshiBotError("No group_id specified and no default_group set")
        return self.bot.send(target, message)

    def broadcast(self, message: str) -> list:
        """Send to all groups the bot is assigned to."""
        return self.bot.send_to_all_groups(message)

    def get_info(self) -> dict:
        """Get bot info."""
        return self.bot.info()

    def __repr__(self):
        return f"OshiMoltBotBridge(bot={self.bot}, default_group='{self.default_group}')"


# ============================================================================
# CLI Usage & Examples
# ============================================================================

if __name__ == "__main__":
    import sys
    import json

    help_text = """
OSHI Bot SDK - Command Line Interface

Usage:
    python oshi_bot.py send    TOKEN GROUP_ID "message"
    python oshi_bot.py info    TOKEN
    python oshi_bot.py groups  TOKEN
    python oshi_bot.py stats   TOKEN
    python oshi_bot.py list
    python oshi_bot.py help

Examples:
    # Send a message
    python oshi_bot.py send a1b2c3d4e5f6g7h8... 550e8400-... "Hello from CLI!"

    # Get bot info
    python oshi_bot.py info a1b2c3d4e5f6g7h8...

    # List all bots
    python oshi_bot.py list

    # Python code:
    from oshi_bot import OshiBot
    bot = OshiBot(token="YOUR_TOKEN")
    bot.send("GROUP_ID", "Hello!")

    # MoltBot integration:
    from oshi_bot import OshiMoltBotBridge
    bridge = OshiMoltBotBridge(oshi_token="TOKEN", default_group="GROUP_ID")
    bridge.send("Hello from MoltBot!")
    """

    if len(sys.argv) < 2 or sys.argv[1] == "help":
        print(help_text)
        sys.exit(0)

    cmd = sys.argv[1]

    try:
        if cmd == "send" and len(sys.argv) >= 5:
            bot = OshiBot(token=sys.argv[2])
            result = bot.send(sys.argv[3], sys.argv[4])
            print(json.dumps(result, indent=2))

        elif cmd == "info" and len(sys.argv) >= 3:
            bot = OshiBot(token=sys.argv[2])
            result = bot.info()
            print(json.dumps(result, indent=2))

        elif cmd == "groups" and len(sys.argv) >= 3:
            bot = OshiBot(token=sys.argv[2])
            groups = bot.get_groups()
            for g in groups:
                print(f"  {g['name']} (id: {g['id']}, members: {g.get('memberCount', '?')})")

        elif cmd == "stats" and len(sys.argv) >= 3:
            bot = OshiBot(token=sys.argv[2])
            stats = bot.get_stats()
            print(f"  Messages sent: {stats.get('messagesSent', 0)}")
            print(f"  Last activity: {stats.get('lastActivity', 'never')}")

        elif cmd == "list":
            bot = OshiBot(token="dummy")
            result = bot.list_bots()
            for b in result.get("bots", []):
                print(f"  {b['botName']} ({b['tokenPrefix']}) - {b['messagesSent']} msgs, {b['groups']} groups")

        else:
            print(f"Unknown command: {cmd}")
            print(help_text)
            sys.exit(1)

    except OshiBotError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
