#!/usr/bin/env python3
"""
Smart Bot — aims and shoots at enemies, dodges when low HP.
This is the reference bot for the VS Arena competition.

Usage:
    python bots/smart_bot.py [--name MyBot] [--server http://localhost:33333]
"""

import requests
import time
import argparse
import sys

def join(server, name):
    """Join the game, return (player_id, token)."""
    resp = requests.post(f"{server}/game/join",
                         json={"name": name})
    data = resp.json()
    if "error" in data:
        print(f"Failed to join: {data['error']}")
        sys.exit(1)
    print(f"Joined as {name}: id={data['player-id']}")
    return data["player-id"], data["token"]

def get_state(server, token):
    """Poll game state (fog-of-war view)."""
    resp = requests.get(f"{server}/game/state",
                        headers={"Authorization": token})
    return resp.json()

def send_action(server, token, action, direction=None):
    """Send an action to the server."""
    body = {"action": action}
    if direction:
        body["direction"] = direction
    requests.post(f"{server}/game/action",
                  headers={"Authorization": token},
                  json=body)

def pick_direction(me_x, me_y, target_x, target_y):
    """Pick cardinal direction from me to target."""
    dx = target_x - me_x
    dy = target_y - me_y
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    else:
        return "south" if dy > 0 else "north"

def enemy_on_axis(me_x, me_y, enemies):
    """Find an enemy that's on a cardinal axis (can be shot at).
    Returns (direction, enemy) or (None, None)."""
    best = None
    best_dist = 999
    for e in enemies:
        ex, ey = e["x"], e["y"]
        dist = abs(ex - me_x) + abs(ey - me_y)
        if ex == me_x and ey != me_y:
            d = "south" if ey > me_y else "north"
            if dist < best_dist:
                best = (d, e)
                best_dist = dist
        elif ey == me_y and ex != me_x:
            d = "east" if ex > me_x else "west"
            if dist < best_dist:
                best = (d, e)
                best_dist = dist
    return best if best else (None, None)

def think(state):
    """Decide what to do this tick. Returns (action, direction)."""
    me = state["you"]
    mx, my = me["x"], me["y"]
    ammo = me.get("ammo", 0)
    enemies = state["visible"].get("enemies", [])

    if not me.get("alive?", True):
        return None, None  # Dead, wait for respawn

    if enemies:
        # Priority 1: Shoot at enemy on same row/column
        if ammo > 0:
            shoot_dir, target = enemy_on_axis(mx, my, enemies)
            if shoot_dir:
                return "shoot", shoot_dir

        # Priority 2: Move to align with nearest enemy (get on same axis)
        nearest = min(enemies, key=lambda e: abs(e["x"]-mx) + abs(e["y"]-my))
        nx, ny = nearest["x"], nearest["y"]

        # If enemy is adjacent, shoot toward it even if not perfectly aligned
        dist = abs(nx - mx) + abs(ny - my)
        if dist <= 2 and ammo > 0:
            return "shoot", pick_direction(mx, my, nx, ny)

        # Move to get on same row or column as nearest enemy
        if abs(nx - mx) <= abs(ny - my):
            # Align horizontally first
            if nx != mx:
                return "move", "east" if nx > mx else "west"
            else:
                return "move", "south" if ny > my else "north"
        else:
            # Align vertically first
            if ny != my:
                return "move", "south" if ny > my else "north"
            else:
                return "move", "east" if nx > mx else "west"
    else:
        # No enemies visible — move toward center
        center_x, center_y = 10, 9  # rough center of 21x19 map
        if mx != center_x or my != center_y:
            return "move", pick_direction(mx, my, center_x, center_y)
        return None, None

def main():
    parser = argparse.ArgumentParser(description="Smart Bot for VS Arena")
    parser.add_argument("--name", default="SmartBot", help="Bot name")
    parser.add_argument("--server", default="http://localhost:33333", help="Server URL")
    args = parser.parse_args()

    player_id, token = join(args.server, args.name)

    kills = 0
    while True:
        try:
            state = get_state(args.server, token)
            action, direction = think(state)

            if action:
                send_action(args.server, token, action, direction)

            # Log status
            me = state["you"]
            n_enemies = len(state["visible"].get("enemies", []))
            new_kills = me.get("score", 0) // 10  # rough kill count
            if new_kills > kills:
                print(f"  Tick {state['tick']}: {action} {direction} | "
                      f"HP={me['hp']} Score={me['score']} Ammo={me.get('ammo','?')} "
                      f"Enemies={n_enemies} | +{new_kills - kills} kills!")
                kills = new_kills

            time.sleep(0.25)  # Match tick rate

        except KeyboardInterrupt:
            print("\nBot stopped.")
            break
        except Exception as e:
            print(f"Error: {e}")
            time.sleep(1)

if __name__ == "__main__":
    main()
