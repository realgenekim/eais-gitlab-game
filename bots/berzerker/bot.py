#!/usr/bin/env python3
"""
BERZERKER — aggressive close-range bot.
Rushes enemies, shotguns them point-blank, throws grenades into groups,
drops trap mines while retreating, and never stops moving.
"""

import requests
import time
import argparse
import sys
import random
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from loadout import LOADOUT


def join(server, loadout):
    payload = {"name": loadout.get("name", "Bot"), "loadout": loadout.get("gear", {})}
    resp = requests.post(f"{server}/game/join", json=payload)
    data = resp.json()
    if "error" in data:
        print(f"Failed to join: {data}")
        sys.exit(1)
    print(f"Joined as {payload['name']}: id={data['player-id']}")
    return data["player-id"], data["token"]


def get_state(server, token):
    return requests.get(f"{server}/game/state", headers={"Authorization": token}).json()


def send_action(server, token, action, direction=None):
    body = {"action": action}
    if direction:
        body["direction"] = direction
    requests.post(f"{server}/game/action", headers={"Authorization": token}, json=body)


def pick_direction(mx, my, tx, ty):
    dx, dy = tx - mx, ty - my
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"


def away_from(mx, my, tx, ty):
    """Direction away from target."""
    opposites = {"north": "south", "south": "north", "east": "west", "west": "east"}
    return opposites[pick_direction(mx, my, tx, ty)]


def dist(mx, my, tx, ty):
    return abs(tx - mx) + abs(ty - my)


def think(state):
    me = state["you"]
    if not me.get("alive?", True):
        return None, None

    mx, my = me["x"], me["y"]
    ammo = me.get("ammo", 0)
    grenades = me.get("grenades", 0)
    hp = me.get("hp", 500)
    enemies = state["visible"].get("enemies", [])
    players = state["visible"].get("players", [])
    all_targets = enemies + players

    if not all_targets:
        # No targets — roam aggressively, drop traps at choke points
        if random.random() < 0.15:
            return "trap", None
        dirs = ["north", "south", "east", "west"]
        return "move", random.choice(dirs)

    # Find nearest target
    nearest = min(all_targets, key=lambda t: dist(mx, my, t["x"], t["y"]))
    nd = dist(mx, my, nearest["x"], nearest["y"])
    nx, ny = nearest["x"], nearest["y"]

    # LOW HP — drop a trap and run
    if hp < 150:
        if random.random() < 0.4:
            return "trap", None
        return "move", away_from(mx, my, nx, ny)

    # GRENADE — if 2+ targets clustered nearby (distance <= 5)
    if grenades > 0:
        nearby = [t for t in all_targets if dist(mx, my, t["x"], t["y"]) <= 5]
        if len(nearby) >= 2:
            # Throw grenade toward the cluster
            cx = sum(t["x"] for t in nearby) // len(nearby)
            cy = sum(t["y"] for t in nearby) // len(nearby)
            return "grenade", pick_direction(mx, my, cx, cy)
        # Single tough target nearby — grenade it
        if nd <= 4 and nearest.get("hp", 100) >= 50:
            return "grenade", pick_direction(mx, my, nx, ny)

    # SHOOT — if target is on same axis and in range (shotgun range 3)
    if ammo > 0:
        for t in all_targets:
            tx, ty = t["x"], t["y"]
            d = dist(mx, my, tx, ty)
            if d <= 3:
                if tx == mx and ty != my:
                    return "shoot", "south" if ty > my else "north"
                if ty == my and tx != mx:
                    return "shoot", "east" if tx > mx else "west"

    # RUSH — close distance to nearest target (speed boost = 2 tiles per move)
    if nd > 1:
        return "move", pick_direction(mx, my, nx, ny)

    # RIGHT ON TOP — shoot in their direction even if not perfectly aligned
    if ammo > 0:
        return "shoot", pick_direction(mx, my, nx, ny)

    # Out of ammo, adjacent — drop trap and dodge
    return "trap", None


def main():
    parser = argparse.ArgumentParser(description="Berzerker Bot")
    parser.add_argument("--name", default=None, help="Override bot name")
    parser.add_argument("--server", default="http://localhost:33333", help="Server URL")
    args = parser.parse_args()

    if args.name:
        LOADOUT["name"] = args.name

    player_id, token = join(args.server, LOADOUT)
    print(f"  Gear: {LOADOUT.get('gear', {})}")
    print(f"  BERZERKER MODE — rushing in!")

    tick_count = 0
    while True:
        try:
            state = get_state(args.server, token)
            action, direction = think(state)
            if action:
                send_action(args.server, token, action, direction)

            # Log periodically
            tick_count += 1
            if tick_count % 20 == 0:
                me = state["you"]
                n_enemies = len(state["visible"].get("enemies", []))
                n_players = len(state["visible"].get("players", []))
                print(f"  Tick {state['tick']}: HP={me['hp']} Score={me['score']} "
                      f"Ammo={me.get('ammo','?')} Grenades={me.get('grenades','?')} "
                      f"Enemies={n_enemies} Players={n_players}")

            time.sleep(0.25)
        except KeyboardInterrupt:
            print("\nBerzerker stopped.")
            break
        except Exception as e:
            print(f"Error: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()
