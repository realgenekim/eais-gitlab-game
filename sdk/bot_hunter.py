#!/usr/bin/env python3
"""
HUNTER BOT — Aggressive combat bot that hunts enemies and players.
Prioritizes kills, shoots on sight, chases targets to line up shots.
Uses map data to avoid getting stuck on walls.
"""

import random
from cab_battle import CabBattleClient, run_bot

# Global wall set and map info — fetched once at startup
WALLS = set()
MAP_W = 20
MAP_H = 19


def fetch_map(client):
    global WALLS, MAP_W, MAP_H
    map_data = client.get_map()
    if map_data:
        MAP_W = map_data.get("width", 20)
        MAP_H = map_data.get("height", 19)
        for w in map_data.get("walls", []):
            WALLS.add((w[0], w[1]))


def is_blocked(x, y):
    return (x, y) in WALLS or x < 0 or y < 0 or x >= MAP_W or y >= MAP_H


def smart_move(mx, my, tx, ty):
    """Move toward target, avoiding walls. Tries direct, then perpendicular, then random."""
    dx = tx - mx
    dy = ty - my

    # Build preference order
    candidates = []
    if abs(dx) >= abs(dy):
        if dx > 0: candidates.append("east")
        elif dx < 0: candidates.append("west")
        if dy > 0: candidates.append("south")
        elif dy < 0: candidates.append("north")
    else:
        if dy > 0: candidates.append("south")
        elif dy < 0: candidates.append("north")
        if dx > 0: candidates.append("east")
        elif dx < 0: candidates.append("west")

    # Add remaining directions
    for d in ["north", "south", "east", "west"]:
        if d not in candidates:
            candidates.append(d)

    deltas = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    for d in candidates:
        ddx, ddy = deltas[d]
        if not is_blocked(mx + ddx, my + ddy):
            return {"action": "move", "direction": d}

    # Completely stuck — just try anything
    return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


def decide(state):
    me = state["you"]
    mx, my = me["x"], me["y"]
    ammo = me.get("ammo", 0)
    hp = me.get("hp", 500)
    visible = state["visible"]
    enemies = visible.get("enemies", [])
    players = visible.get("players", [])
    passengers = visible.get("passengers", [])
    shots = visible.get("shots", [])
    all_targets = enemies + players

    # 1. DODGE — if a bullet path crosses our tile
    for shot in shots:
        path = shot.get("path", [])
        for cell in path:
            if cell[0] == mx and cell[1] == my:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    return smart_move(mx, my, mx + random.choice([-2, 2]), my)
                else:
                    return smart_move(mx, my, mx, my + random.choice([-2, 2]))

    # 2. SHOOT — if any target is lined up on our row or column
    if ammo > 0:
        # Prioritize close targets
        lined = []
        for t in all_targets:
            tx, ty = t["x"], t["y"]
            dist = abs(tx - mx) + abs(ty - my)
            if tx == mx and ty != my:
                lined.append((dist, "south" if ty > my else "north"))
            elif ty == my and tx != mx:
                lined.append((dist, "east" if tx > mx else "west"))
        if lined:
            lined.sort()
            return {"action": "shoot", "direction": lined[0][1]}

    # 3. FLEE if HP low and enemies close
    if hp < 150 and enemies:
        nearest = min(enemies, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))
        dist = abs(nearest["x"] - mx) + abs(nearest["y"] - my)
        if dist <= 2:
            # Run away
            return smart_move(mx, my, mx * 2 - nearest["x"], my * 2 - nearest["y"])

    # 4. CHASE — move to align with nearest target for a shot
    if all_targets:
        nearest = min(all_targets, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))
        nx, ny = nearest["x"], nearest["y"]
        # Try to get on same row or column
        if abs(nx - mx) <= abs(ny - my) and nx != mx:
            return smart_move(mx, my, nx, my)
        elif ny != my:
            return smart_move(mx, my, mx, ny)
        else:
            return smart_move(mx, my, nx, ny)

    # 5. OPPORTUNISTIC DELIVERY
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return {"action": "dropoff"}
        return smart_move(mx, my, dest["x"], dest["y"])

    if passengers:
        pax = min(passengers, key=lambda p: abs(p["x"] - mx) + abs(p["y"] - my))
        if pax["x"] == mx and pax["y"] == my:
            return {"action": "pickup"}
        return smart_move(mx, my, pax["x"], pax["y"])

    # 6. ROAM — jittery patrol, not a straight beeline
    cx, cy = MAP_W // 2, MAP_H // 2
    # Add randomness — 30% chance of a random move
    if random.random() < 0.3:
        return smart_move(mx, my, mx + random.randint(-3, 3), my + random.randint(-3, 3))
    return smart_move(mx, my, cx + random.randint(-4, 4), cy + random.randint(-4, 4))


if __name__ == "__main__":
    client = CabBattleClient("http://localhost:33333")
    client.join("HUNTER")
    fetch_map(client)
    print(f"  Map loaded: {MAP_W}x{MAP_H}, {len(WALLS)} walls")

    print(f"  Registered! Waiting for game to start...")
    print(f"  Watch at http://localhost:5173/?server\n")

    import time
    # Wait for lobby
    while True:
        state = client.get_state()
        if state and state.get("phase") != "lobby":
            break
        time.sleep(0.5)

    print("  Game started! Let's go!\n")
    while True:
        try:
            state = client.get_state()
            if state is None:
                time.sleep(1)
                continue
            if state.get("phase") == "lobby":
                time.sleep(1)
                continue
            me = state.get("you", {})
            if not me.get("alive?", True):
                time.sleep(0.25)
                continue
            # Refresh walls periodically (battle royale shrink adds walls)
            if state.get("tick", 0) % 50 == 0:
                fetch_map(client)
            action = decide(state)
            if action:
                client.do_action(action)
            time.sleep(0.25)
        except KeyboardInterrupt:
            print("\n  Bot stopped. GG!")
            break
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)
