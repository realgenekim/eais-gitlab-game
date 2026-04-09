"""
CLAUDE-AGENT BRAIN v2 — learned from round 1 death at edge.

Fixes: Tighter center leash, shoot while retreating, never go past x=13.
Gear: plasma-rounds (2x damage), oracle-eye (2x vision), ammo-belt (2x regen)
"""

import random

# =====================================================
#  SETTINGS
# =====================================================

CENTER_X = 10
CENTER_Y = 9
EDGE_DANGER = 6       # wider danger zone
CENTER_LEASH = 4      # never go more than 4 tiles from center
FLEE_HP = 200

# Track state across ticks
_last_positions = []
_tick_count = 0

# =====================================================
#  BRAIN
# =====================================================

def think(state):
    global _last_positions, _tick_count

    me = state.get("you")
    if not me or not me.get("alive?", True):
        return None, None

    mx, my = me["x"], me["y"]
    hp = me.get("hp", 500)
    ammo = me.get("ammo", 0)
    map_w = state["map"]["width"]
    map_h = state["map"]["height"]

    players = state["visible"].get("players", [])
    passengers = state["visible"].get("passengers", [])
    shots = state["visible"].get("shots", [])

    _tick_count += 1

    # --- Anti-stuck: if same position for 4+ ticks, force random move ---
    _last_positions.append((mx, my))
    if len(_last_positions) > 8:
        _last_positions = _last_positions[-8:]
    if len(_last_positions) >= 4 and len(set(_last_positions[-4:])) <= 1:
        _last_positions = []
        return "move", random.choice(["north", "south", "east", "west"])

    # --- PRIORITY 1: Dodge incoming bullets ---
    for shot in shots:
        path = shot.get("path", [])
        for cell in path:
            if cell[0] == mx and cell[1] == my:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    return "move", safe_dodge(mx, my, ["east", "west"], map_w, map_h)
                return "move", safe_dodge(mx, my, ["north", "south"], map_w, map_h)

    # --- PRIORITY 2: ESCAPE EDGES — #1 cause of death ---
    edge_dist = min(mx, my, map_w - 1 - mx, map_h - 1 - my)
    dist_to_center = abs(mx - CENTER_X) + abs(my - CENTER_Y)

    if edge_dist < EDGE_DANGER or dist_to_center > CENTER_LEASH:
        # Even while fleeing to center, shoot if we can
        if ammo > 0 and players:
            for target in players:
                d = can_shoot(mx, my, target["x"], target["y"])
                if d and is_toward_center(mx, my, d, map_w, map_h):
                    return "shoot", d
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 3: Flee when HP is low ---
    if hp < FLEE_HP and players:
        nearest = closest(mx, my, players)
        dist = manhattan(mx, my, nearest["x"], nearest["y"])
        if dist <= 5:
            # Shoot while fleeing if lined up
            if ammo > 0:
                d = can_shoot(mx, my, nearest["x"], nearest["y"])
                if d:
                    return "shoot", d
            d = away_from(mx, my, nearest["x"], nearest["y"])
            if not moves_toward_edge(mx, my, d, map_w, map_h):
                return "move", d
            return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 4: Shoot any rival in line of sight ---
    if ammo > 0:
        shootable = []
        for target in players:
            d = can_shoot(mx, my, target["x"], target["y"])
            if d:
                dist = manhattan(mx, my, target["x"], target["y"])
                shootable.append((dist, d, target))
        if shootable:
            shootable.sort()
            return "shoot", shootable[0][1]

    # --- PRIORITY 5: Chase nearest rival (stay within center leash) ---
    if players and ammo >= 2:
        target = closest(mx, my, players)
        dist = manhattan(mx, my, target["x"], target["y"])
        if dist <= 10:
            d = align_for_shot(mx, my, target["x"], target["y"])
            if d and not moves_toward_edge(mx, my, d, map_w, map_h):
                # Check we won't go too far from center
                ddx, ddy = DIRS.get(d, (0, 0))
                nx, ny = mx + ddx, my + ddy
                if abs(nx - CENTER_X) + abs(ny - CENTER_Y) <= CENTER_LEASH + 2:
                    return "move", d

    # --- PRIORITY 6: Deliver mission if carrying ---
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return "dropoff", None
        d = toward(mx, my, dest["x"], dest["y"])
        ddx, ddy = DIRS.get(d, (0, 0))
        nx, ny = mx + ddx, my + ddy
        if not moves_toward_edge(mx, my, d, map_w, map_h) and abs(nx - CENTER_X) + abs(ny - CENTER_Y) <= CENTER_LEASH + 2:
            return "move", d
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 7: Pickup mission if safe and nearby ---
    if passengers and not players:
        p = closest(mx, my, passengers)
        pdist = manhattan(mx, my, p["x"], p["y"])
        if p["x"] == mx and p["y"] == my:
            return "pickup", None
        if pdist <= 3:
            d = toward(mx, my, p["x"], p["y"])
            if not moves_toward_edge(mx, my, d, map_w, map_h):
                return "move", d

    # --- DEFAULT: Stay near center with unpredictable jitter ---
    if _tick_count % 3 == 0:
        dirs = ["north", "south", "east", "west"]
        random.shuffle(dirs)
        for d in dirs:
            ddx, ddy = DIRS.get(d, (0, 0))
            nx, ny = mx + ddx, my + ddy
            if abs(nx - CENTER_X) + abs(ny - CENTER_Y) <= CENTER_LEASH:
                return "move", d
    return "move", best_toward_center(mx, my, map_w, map_h)


# =====================================================
#  HELPERS
# =====================================================

DIRS = {
    "north": (0, -1), "south": (0, 1),
    "east": (1, 0), "west": (-1, 0)
}

def manhattan(x1, y1, x2, y2):
    return abs(x1 - x2) + abs(y1 - y2)

def toward(mx, my, tx, ty):
    dx, dy = tx - mx, ty - my
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"

def away_from(mx, my, tx, ty):
    dx, dy = mx - tx, my - ty
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"

def closest(mx, my, targets):
    return min(targets, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))

def can_shoot(mx, my, tx, ty):
    if tx == mx and ty != my:
        return "south" if ty > my else "north"
    if ty == my and tx != mx:
        return "east" if tx > mx else "west"
    return None

def align_for_shot(mx, my, tx, ty):
    """Move to get on same row or column as target."""
    dx, dy = tx - mx, ty - my
    if abs(dx) <= abs(dy) and dx != 0:
        return "east" if dx > 0 else "west"
    if abs(dy) < abs(dx) and dy != 0:
        return "south" if dy > 0 else "north"
    return toward(mx, my, tx, ty)

def safe_dodge(mx, my, prefer_dirs, map_w, map_h):
    for d in prefer_dirs:
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return d
    return best_toward_center(mx, my, map_w, map_h)

def is_toward_center(mx, my, direction, map_w, map_h):
    ddx, ddy = DIRS.get(direction, (0, 0))
    nx, ny = mx + ddx, my + ddy
    return manhattan(nx, ny, CENTER_X, CENTER_Y) < manhattan(mx, my, CENTER_X, CENTER_Y)

def best_toward_center(mx, my, map_w, map_h, prefer=None):
    cx, cy = CENTER_X, CENTER_Y
    ranked = []
    for d, (ddx, ddy) in DIRS.items():
        nx, ny = mx + ddx, my + ddy
        if nx < 0 or ny < 0 or nx >= map_w or ny >= map_h:
            continue
        dist = abs(nx - cx) + abs(ny - cy)
        bonus = -1 if (prefer and d in prefer) else 0
        ranked.append((dist + bonus, d))
    ranked.sort()
    return ranked[0][1] if ranked else "south"

def moves_toward_edge(mx, my, direction, map_w, map_h):
    ddx, ddy = DIRS.get(direction, (0, 0))
    nx, ny = mx + ddx, my + ddy
    edge_dist_now = min(mx, my, map_w - 1 - mx, map_h - 1 - my)
    edge_dist_new = min(nx, ny, map_w - 1 - nx, map_h - 1 - ny)
    return edge_dist_new < edge_dist_now and edge_dist_new < EDGE_DANGER
