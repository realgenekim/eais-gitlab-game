"""
YOUR BOT'S BRAIN — edit this file while your bot is running!

Save this file and your strategy reloads LIVE. No restart needed.
Use Claude Code, ChatGPT, or any AI tool to vibecode your strategy.

Every 250ms the arena calls think(state). Return (action, direction).

ACTIONS:
  ("move", "north")    — move one tile (north/south/east/west)
  ("shoot", "east")    — shoot in a direction (uses 1 ammo)
  ("pickup", None)     — grab mission passenger at your tile
  ("dropoff", None)    — complete mission at destination (+100 pts)

CRITICAL SURVIVAL RULES:
  - The arena SHRINKS from the edges. Stay near center (10, 9) or die.
  - If you're within 5 tiles of any edge, DROP EVERYTHING and move to center.
  - Elimination mode: die once = out for the round. Survival > kills.
  - Getting hit flings you (knockback) — you might get pushed to the edge!
"""

import random

# =====================================================
#  SETTINGS
# =====================================================

CENTER_X = 10
CENTER_Y = 9
EDGE_DANGER = 5    # tiles from edge = danger zone
FLEE_HP = 200

# Track position for anti-stuck
_last_positions = []

# =====================================================
#  BRAIN
# =====================================================

def think(state):
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

    # --- Anti-stuck: if same position for 3+ ticks, force random move ---
    global _last_positions
    _last_positions.append((mx, my))
    if len(_last_positions) > 5:
        _last_positions = _last_positions[-5:]
    if len(_last_positions) >= 3 and len(set(_last_positions[-3:])) <= 1:
        _last_positions = []
        return "move", random.choice(["north", "south", "east", "west"])

    # --- PRIORITY 1: Dodge incoming bullets ---
    for shot in shots:
        for cell in shot.get("path", []):
            if cell[0] == mx and cell[1] == my:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    return "move", best_toward_center(mx, my, map_w, map_h, prefer=["east", "west"])
                return "move", best_toward_center(mx, my, map_w, map_h, prefer=["north", "south"])

    # --- PRIORITY 2: ESCAPE EDGES — survival is everything ---
    near_left = mx < EDGE_DANGER
    near_right = mx >= map_w - EDGE_DANGER
    near_top = my < EDGE_DANGER
    near_bottom = my >= map_h - EDGE_DANGER

    if near_left or near_right or near_top or near_bottom:
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 3: Flee when HP is low ---
    if hp < FLEE_HP and players:
        nearest = closest(mx, my, players)
        d = away_from(mx, my, nearest["x"], nearest["y"])
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return "move", d
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 4: Shoot anything lined up ---
    if ammo > 0:
        for target in players:
            d = can_shoot(mx, my, target["x"], target["y"])
            if d:
                return "shoot", d

    # --- PRIORITY 5: Deliver mission ---
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return "dropoff", None
        d = toward(mx, my, dest["x"], dest["y"])
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return "move", d
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 6: Pickup nearest mission ---
    if passengers:
        p = closest(mx, my, passengers)
        if p["x"] == mx and p["y"] == my:
            return "pickup", None
        d = toward(mx, my, p["x"], p["y"])
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return "move", d

    # --- PRIORITY 7: Chase rival to line up shot ---
    if players and ammo > 0:
        t = closest(mx, my, players)
        d = toward(mx, my, t["x"], t["y"])
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return "move", d

    # --- DEFAULT: Patrol near center with jitter ---
    jx = CENTER_X + random.randint(-3, 3)
    jy = CENTER_Y + random.randint(-3, 3)
    return "move", best_toward_center(mx, my, map_w, map_h)


# =====================================================
#  HELPERS
# =====================================================

DIRS = {
    "north": (0, -1), "south": (0, 1),
    "east": (1, 0), "west": (-1, 0)
}

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

def best_toward_center(mx, my, map_w, map_h, prefer=None):
    """Pick the direction that most reduces distance to center.
    Tries all 4 directions ranked by center-distance reduction."""
    cx, cy = CENTER_X, CENTER_Y
    ranked = []
    for d, (ddx, ddy) in DIRS.items():
        nx, ny = mx + ddx, my + ddy
        if nx < 0 or ny < 0 or nx >= map_w or ny >= map_h:
            continue
        dist = abs(nx - cx) + abs(ny - cy)
        # Bonus for preferred directions
        bonus = -1 if (prefer and d in prefer) else 0
        ranked.append((dist + bonus, d))
    ranked.sort()
    if ranked:
        return ranked[0][1]
    return "south"  # fallback

def moves_toward_edge(mx, my, direction, map_w, map_h):
    """Returns True if moving in this direction puts us closer to an edge."""
    ddx, ddy = DIRS.get(direction, (0, 0))
    nx, ny = mx + ddx, my + ddy
    edge_dist_now = min(mx, my, map_w - 1 - mx, map_h - 1 - my)
    edge_dist_new = min(nx, ny, map_w - 1 - nx, map_h - 1 - ny)
    return edge_dist_new < edge_dist_now and edge_dist_new < EDGE_DANGER
