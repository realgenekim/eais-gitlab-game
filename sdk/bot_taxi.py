#!/usr/bin/env python3
"""
TAXI BOT — Efficient passenger delivery bot with self-defense.
Prioritizes deliveries, shoots only when targets are perfectly lined up,
and avoids danger when HP is low. Uses map data to avoid walls.
"""

import random
from cab_battle import CabBattleClient, run_bot

# Global wall set and map info
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
    """Move toward target, avoiding walls."""
    dx = tx - mx
    dy = ty - my

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

    for d in ["north", "south", "east", "west"]:
        if d not in candidates:
            candidates.append(d)

    deltas = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    for d in candidates:
        ddx, ddy = deltas[d]
        if not is_blocked(mx + ddx, my + ddy):
            return {"action": "move", "direction": d}

    return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


def decide(state):
    me = state["you"]
    mx, my = me["x"], me["y"]
    hp = me.get("hp", 500)
    ammo = me.get("ammo", 0)
    visible = state["visible"]
    enemies = visible.get("enemies", [])
    players = visible.get("players", [])
    passengers = visible.get("passengers", [])
    shots = visible.get("shots", [])

    # 1. DODGE — evade incoming bullets
    for shot in shots:
        path = shot.get("path", [])
        for cell in path:
            if cell[0] == mx and cell[1] == my:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    return smart_move(mx, my, mx + random.choice([-2, 2]), my)
                else:
                    return smart_move(mx, my, mx, my + random.choice([-2, 2]))

    # 2. FLEE — if HP is low AND threats are close, run
    all_threats = enemies + players
    if hp < 200 and all_threats:
        nearest = min(all_threats, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))
        dist = abs(nearest["x"] - mx) + abs(nearest["y"] - my)
        if dist <= 3:
            return smart_move(mx, my, mx * 2 - nearest["x"], my * 2 - nearest["y"])

    # 3. SHOOT — if anything is lined up, take the shot
    if ammo >= 2:
        for t in enemies + players:
            tx, ty = t["x"], t["y"]
            if tx == mx and ty != my:
                return {"action": "shoot", "direction": "south" if ty > my else "north"}
            if ty == my and tx != mx:
                return {"action": "shoot", "direction": "east" if tx > mx else "west"}

    # 4. DELIVER — if carrying a passenger, go to destination
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return {"action": "dropoff"}
        return smart_move(mx, my, dest["x"], dest["y"])

    # 5. PICKUP — find closest passenger
    if passengers:
        closest = min(passengers, key=lambda p: abs(p["x"] - mx) + abs(p["y"] - my))
        if closest["x"] == mx and closest["y"] == my:
            return {"action": "pickup"}
        return smart_move(mx, my, closest["x"], closest["y"])

    # 6. EXPLORE — wander around, not just beeline to center
    if random.random() < 0.4:
        return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}
    cx, cy = MAP_W // 2, MAP_H // 2
    return smart_move(mx, my, cx + random.randint(-5, 5), cy + random.randint(-5, 5))


if __name__ == "__main__":
    client = CabBattleClient("http://localhost:33333")
    client.join("TAXI")
    fetch_map(client)
    print(f"  Map loaded: {MAP_W}x{MAP_H}, {len(WALLS)} walls")

    print(f"  Registered! Waiting for game to start...")
    print(f"  Watch at http://localhost:5173/?server\n")

    import time
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
