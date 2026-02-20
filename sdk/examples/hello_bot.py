#!/usr/bin/env python3
"""
OSHI Bot SDK - Hello World Example

A minimal example that connects to the OSHI Bot API, retrieves bot info,
and sends a greeting message to a group.

Setup:
    1. Create a bot in the OSHI app (Portal > Bots > Create)
    2. Copy the bot token and a group ID
    3. Replace YOUR_BOT_TOKEN and YOUR_GROUP_ID below
    4. Run: python hello_bot.py

Requirements:
    pip install requests
"""

import sys
import os

# Add parent directory so we can import oshi_bot
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from oshi_bot import OshiBot, OshiBotError, OshiAuthError, OshiGroupError

# -- Configuration ----------------------------------------------------------
BOT_TOKEN = "YOUR_BOT_TOKEN"       # 32-char hex token from OSHI app
GROUP_ID  = "YOUR_GROUP_ID"        # UUID of the target group
# ---------------------------------------------------------------------------


def main():
    # Create the bot client
    bot = OshiBot(token=BOT_TOKEN)
    print(f"SDK initialized: {bot}")

    # Step 1: Fetch bot info
    try:
        info = bot.info()
        bot_data = info.get("bot", {})
        print(f"Bot name: {bot_data.get('botName', 'unknown')}")
        print(f"Registered groups: {len(bot_data.get('groups', []))}")
        for g in bot_data.get("groups", []):
            print(f"  - {g['name']} ({g.get('memberCount', '?')} members)")
    except OshiAuthError:
        print("ERROR: Invalid bot token. Check your token in the OSHI app.")
        sys.exit(1)
    except OshiBotError as e:
        print(f"ERROR: Could not fetch bot info: {e}")
        sys.exit(1)

    # Step 2: Send a message
    try:
        result = bot.send(GROUP_ID, "Hello from OSHI Bot!")
        print(f"\nMessage sent successfully!")
        print(f"  Message ID: {result.get('messageId', 'n/a')}")
        print(f"  Group: {result.get('groupName', 'n/a')}")
        print(f"  Delivered to: {result.get('delivered', 0)} / {result.get('totalMembers', 0)} members")
    except OshiGroupError:
        print(f"ERROR: Bot is not assigned to group {GROUP_ID}.")
        print("  Assign it in the OSHI app under Portal > Bots > Edit.")
        sys.exit(1)
    except OshiBotError as e:
        print(f"ERROR: Failed to send message: {e}")
        sys.exit(1)

    # Step 3: Show stats
    stats = bot.get_stats()
    print(f"\nBot stats:")
    print(f"  Total messages sent: {stats.get('messagesSent', 0)}")
    print(f"  Last activity: {stats.get('lastActivity', 'never')}")


if __name__ == "__main__":
    main()
