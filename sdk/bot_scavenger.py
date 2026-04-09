#!/usr/bin/env python3
"""
SCAVENGER BOT — Avoids combat, hugs the center, delivers passengers.
Shoots only when cornered. Survives by staying away from trouble.
"""

import random
from cab_battle import CabBattleClient

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
    dx, dy = tx - mx, ty - my
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
    all_threats = enemies + players
    cx, cy = MAP_W // 2, MAP_H // 2

    # 1. FLEE — always run from nearby threats
    if all_threats:
        nearest = min(all_threats, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))
        dist = abs(nearest["x"] - mx) + abs(nearest["y"] - my)
        if dist <= 2:
            # Desperation shot if lined up
            if ammo > 0:
                tx, ty = nearest["x"], nearest["y"]
                if tx == mx and ty != my:
                    return {"action": "shoot", "direction": "south" if ty > my else "north"}
                if ty == my and tx != mx:
                    return {"action": "shoot", "direction": "east" if tx > mx else "west"}
            # Run away
            return smart_move(mx, my, mx * 2 - nearest["x"], my * 2 - nearest["y"])

    # 2. DELIVER — carrying a passenger? Drop it off
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return {"action": "dropoff"}
        return smart_move(mx, my, dest["x"], dest["y"])

    # 3. PICKUP — grab closest safe passenger
    if passengers:
        # Prefer passengers closer to center (safer)
        scored = []
        for p in passengers:
            pdist = abs(p["x"] - mx) + abs(p["y"] - my)
            center_dist = abs(p["x"] - cx) + abs(p["y"] - cy)
            scored.append((pdist + center_dist * 0.5, p))
        scored.sort()
        best = scored[0][1]
        if best["x"] == mx and best["y"] == my:
            return {"action": "pickup"}
        return smart_move(mx, my, best["x"], best["y"])

    # 4. OPPORTUNISTIC SHOT — shoot if lined up and safe
    if ammo >= 3:
        for t in enemies:
            tx, ty = t["x"], t["y"]
            if tx == mx and ty != my:
                return {"action": "shoot", "direction": "south" if ty > my else "north"}
            if ty == my and tx != mx:
                return {"action": "shoot", "direction": "east" if tx > mx else "west"}

    # 5. WANDER — drift around center area with jitter
    if random.random() < 0.35:
        return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}
    return smart_move(mx, my, cx + random.randint(-6, 6), cy + random.randint(-6, 6))


if __name__ == "__main__":
    import time
    client = CabBattleClient("http://localhost:33333")
    client.join("SCAVENGER")
    fetch_map(client)
    print(f"  Map: {MAP_W}x{MAP_H}, {len(WALLS)} walls")
    print(f"  Waiting for game...\n")
    while True:
        state = client.get_state()
        if state and state.get("phase") != "lobby":
            break
        time.sleep(0.5)
    print("  SCAVENGER online. Stay quiet, get paid.\n")
    while True:
        try:
            state = client.get_state()
            if not state: time.sleep(1); continue
            if state.get("phase") == "lobby": time.sleep(1); continue
            me = state.get("you", {})
            if not me.get("alive?", True): time.sleep(0.25); continue
            if state.get("tick", 0) % 50 == 0: fetch_map(client)
            action = decide(state)
            if action: client.do_action(action)
            time.sleep(0.25)
        except KeyboardInterrupt:
            print("\n  SCAVENGER out.")
            break
        except Exception as e:
            print(f"  Error: {e}"); time.sleep(1)
