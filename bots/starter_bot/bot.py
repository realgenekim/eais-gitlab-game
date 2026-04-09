#!/usr/bin/env python3
"""
Starter bot with loadout support.
Sends your loadout.py gear config when joining the game.

Usage:
    python bots/starter_bot/bot.py [--name MyBot] [--server http://localhost:33333]
"""

import requests
import time
import argparse
import sys
from pathlib import Path

# Import loadout from the same directory
sys.path.insert(0, str(Path(__file__).parent))
from loadout import LOADOUT


def join(server, loadout):
    """Join the game with loadout, return (player_id, token)."""
    payload = {
        "name": loadout.get("name", "Bot"),
        "loadout": loadout.get("gear", {}),
    }
    resp = requests.post(f"{server}/game/join", json=payload)
    data = resp.json()
    if "error" in data:
        print(f"Failed to join: {data}")
        sys.exit(1)
    print(f"Joined as {payload['name']}: id={data['player-id']}")
    return data["player-id"], data["token"]


def get_state(server, token):
    """Poll game state (fog-of-war view)."""
    return requests.get(
        f"{server}/game/state",
        headers={"Authorization": token}
    ).json()


def send_action(server, token, action, direction=None):
    """Send an action to the server."""
    body = {"action": action}
    if direction:
        body["direction"] = direction
    requests.post(
        f"{server}/game/action",
        headers={"Authorization": token},
        json=body,
    )


def pick_direction(mx, my, tx, ty):
    """Pick cardinal direction toward target."""
    dx, dy = tx - mx, ty - my
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"


def think(state):
    """
    Decide what to do this tick. Returns (action, direction) or (None, None).

    This is where you build your bot's brain!
    You have access to your loadout gear — use actions like:
      - ("move", "north/south/east/west")
      - ("shoot", "north/south/east/west")
      - ("pickup", None)     — pick up a passenger
      - ("dropoff", None)    — deliver a passenger
      - ("grenade", "north") — throw grenade (if you have grenades)
      - ("teleport", None)   — teleport to random spot (if you have teleporter)
      - ("trap", None)       — place mine at your position (if you have trap-mine)
      - ("decoy", None)      — place decoy at your position (if you have decoy)
    """
    me = state["you"]
    if not me.get("alive?", True):
        return None, None

    mx, my = me["x"], me["y"]
    ammo = me.get("ammo", 0)
    enemies = state["visible"].get("enemies", [])
    players = state["visible"].get("players", [])

    # Shoot at enemies on same axis
    if ammo > 0:
        for target in enemies + players:
            tx, ty = target["x"], target["y"]
            if tx == mx and ty != my:
                return "shoot", "south" if ty > my else "north"
            if ty == my and tx != mx:
                return "shoot", "east" if tx > mx else "west"

    # Move toward nearest enemy
    if enemies:
        nearest = min(enemies, key=lambda e: abs(e["x"] - mx) + abs(e["y"] - my))
        return "move", pick_direction(mx, my, nearest["x"], nearest["y"])

    # Wander toward center
    return "move", pick_direction(mx, my, 10, 9)


def main():
    parser = argparse.ArgumentParser(description="Starter Bot with Loadout")
    parser.add_argument("--name", default=None, help="Override bot name")
    parser.add_argument("--server", default="http://localhost:33333", help="Server URL")
    args = parser.parse_args()

    if args.name:
        LOADOUT["name"] = args.name

    player_id, token = join(args.server, LOADOUT)

    print(f"  Gear: {LOADOUT.get('gear', {})}")
    print(f"  Running... (Ctrl+C to stop)")

    while True:
        try:
            state = get_state(args.server, token)
            action, direction = think(state)
            if action:
                send_action(args.server, token, action, direction)
            time.sleep(0.25)
        except KeyboardInterrupt:
            print("\nBot stopped.")
            break
        except Exception as e:
            print(f"Error: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()
