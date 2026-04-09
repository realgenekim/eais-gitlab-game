#!/usr/bin/env python3
"""
BERSERKER BOT — Charges straight at the nearest threat and unloads ammo.
No fear, no retreat. Shoots everything, delivers nothing.
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
    ammo = me.get("ammo", 0)
    visible = state["visible"]
    enemies = visible.get("enemies", [])
    players = visible.get("players", [])
    all_targets = enemies + players

    # SHOOT anything lined up — don't even think about it
    if ammo > 0:
        for t in all_targets:
            tx, ty = t["x"], t["y"]
            if tx == mx and ty != my:
                return {"action": "shoot", "direction": "south" if ty > my else "north"}
            if ty == my and tx != mx:
                return {"action": "shoot", "direction": "east" if tx > mx else "west"}

    # CHARGE nearest target — get on their axis
    if all_targets:
        nearest = min(all_targets, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))
        nx, ny = nearest["x"], nearest["y"]
        # Align on row or column for a shot
        if nx == mx:
            return smart_move(mx, my, mx, ny)
        if ny == my:
            return smart_move(mx, my, nx, my)
        # Move to align — prefer whichever axis is closer
        if abs(nx - mx) <= abs(ny - my):
            return smart_move(mx, my, nx, my)
        else:
            return smart_move(mx, my, mx, ny)

    # No targets — erratic patrol, looks like a player mashing keys
    if random.random() < 0.35:
        return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}
    patrol_points = [(5, 5), (15, 5), (15, 13), (5, 13), (10, 9), (3, 9), (17, 9)]
    target = random.choice(patrol_points)
    return smart_move(mx, my, target[0] + random.randint(-2, 2), target[1] + random.randint(-2, 2))


if __name__ == "__main__":
    import time
    client = CabBattleClient("http://localhost:33333")
    client.join("BERSERKER")
    fetch_map(client)
    print(f"  Map: {MAP_W}x{MAP_H}, {len(WALLS)} walls")
    print(f"  Waiting for game...\n")
    while True:
        state = client.get_state()
        if state and state.get("phase") != "lobby":
            break
        time.sleep(0.5)
    print("  BERSERKER ONLINE. NO MERCY.\n")
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
            print("\n  BERSERKER OUT.")
            break
        except Exception as e:
            print(f"  Error: {e}"); time.sleep(1)
