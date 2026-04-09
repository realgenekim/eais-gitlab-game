"""
CLAUDE-AGENT BRAIN — aggressive hunter with survival instincts.

Gear: plasma-rounds (2x damage), oracle-eye (2x vision), ammo-belt (2x regen)
Strategy: See far, shoot hard, never run out of ammo. Kill in 5 hits.

Priority order:
  1. Dodge incoming bullets (perpendicular escape)
  2. Escape edges (arena shrinks = instant death)
  3. Flee when HP critical (<150) — but never toward edge
  4. Shoot any rival in line of sight
  5. Aggressively chase nearest rival to line up a shot
  6. Do missions only when no rivals visible
  7. Patrol center with unpredictable movement
"""

import random
import math

# =====================================================
#  SETTINGS
# =====================================================

CENTER_X = 10
CENTER_Y = 9
EDGE_DANGER = 6       # wider danger zone — be paranoid about edges
FLEE_HP = 150          # flee threshold (we have plasma, so be braver)
CHASE_RANGE = 12       # oracle-eye gives us 16 vision, chase aggressively
SAFE_SHOOT_EDGE = 4    # don't shoot if within this many tiles of edge

# Track state across ticks
_last_positions = []
_tick_count = 0
_dodge_cooldown = 0

# =====================================================
#  BRAIN
# =====================================================

def think(state):
    global _last_positions, _tick_count, _dodge_cooldown

    me = state["you"]
    if not me.get("alive?", True):
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
    if _dodge_cooldown > 0:
        _dodge_cooldown -= 1

    # --- Anti-stuck: if same position for 4+ ticks, force random move ---
    _last_positions.append((mx, my))
    if len(_last_positions) > 8:
        _last_positions = _last_positions[-8:]
    if len(_last_positions) >= 4 and len(set(_last_positions[-4:])) <= 1:
        _last_positions = []
        return "move", random.choice(["north", "south", "east", "west"])

    # --- PRIORITY 1: Dodge incoming bullets ---
    # Check if ANY bullet path will hit our current tile or adjacent tile
    for shot in shots:
        path = shot.get("path", [])
        for cell in path:
            if cell[0] == mx and cell[1] == my:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    # Bullet traveling vertically, dodge horizontally
                    dodge = safe_dodge(mx, my, ["east", "west"], map_w, map_h)
                else:
                    # Bullet traveling horizontally, dodge vertically
                    dodge = safe_dodge(mx, my, ["north", "south"], map_w, map_h)
                _dodge_cooldown = 2
                return "move", dodge
            # Also dodge if bullet is 1 tile away and heading toward us
            if abs(cell[0] - mx) + abs(cell[1] - my) == 1:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    dodge = safe_dodge(mx, my, ["east", "west"], map_w, map_h)
                else:
                    dodge = safe_dodge(mx, my, ["north", "south"], map_w, map_h)
                return "move", dodge

    # --- PRIORITY 2: ESCAPE EDGES — survival is everything ---
    edge_dist = min(mx, my, map_w - 1 - mx, map_h - 1 - my)
    if edge_dist < EDGE_DANGER:
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 3: Flee when HP is critical ---
    if hp < FLEE_HP and players:
        nearest = closest(mx, my, players)
        dist = manhattan(mx, my, nearest["x"], nearest["y"])
        # Only flee if threat is close
        if dist <= 5:
            d = away_from(mx, my, nearest["x"], nearest["y"])
            if not moves_toward_edge(mx, my, d, map_w, map_h):
                return "move", d
            return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 4: Shoot any rival in line of sight ---
    # With plasma-rounds we do 100 damage per hit (2x), so prioritize shooting
    if ammo > 0 and edge_dist >= SAFE_SHOOT_EDGE:
        # Sort targets by HP (lowest first) to finish off weak rivals
        shootable = []
        for target in players:
            d = can_shoot(mx, my, target["x"], target["y"])
            if d:
                dist = manhattan(mx, my, target["x"], target["y"])
                shootable.append((dist, d, target))
        if shootable:
            shootable.sort()  # closest first
            return "shoot", shootable[0][1]

    # Even near edge, shoot if target is between us and center
    if ammo > 0 and edge_dist < SAFE_SHOOT_EDGE:
        for target in players:
            d = can_shoot(mx, my, target["x"], target["y"])
            if d and is_toward_center(mx, my, d, map_w, map_h):
                return "shoot", d

    # --- PRIORITY 5: Aggressively chase nearest rival ---
    # With oracle-eye we see far, with plasma we kill fast — hunt them down
    if players and ammo >= 2:
        # Pick the closest rival
        target = closest(mx, my, players)
        dist = manhattan(mx, my, target["x"], target["y"])

        if dist <= CHASE_RANGE:
            # Try to get on same row or column for a shot
            d = align_for_shot(mx, my, target["x"], target["y"], map_w, map_h)
            if d and not moves_toward_edge(mx, my, d, map_w, map_h):
                return "move", d
            # Just move toward them
            d = toward(mx, my, target["x"], target["y"])
            if not moves_toward_edge(mx, my, d, map_w, map_h):
                return "move", d

    # --- PRIORITY 6: Deliver mission if carrying ---
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return "dropoff", None
        d = toward(mx, my, dest["x"], dest["y"])
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return "move", d
        return "move", best_toward_center(mx, my, map_w, map_h)

    # --- PRIORITY 7: Pickup nearest mission (only if safe) ---
    if passengers and not players:
        p = closest(mx, my, passengers)
        if p["x"] == mx and p["y"] == my:
            return "pickup", None
        d = toward(mx, my, p["x"], p["y"])
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return "move", d

    # --- DEFAULT: Unpredictable patrol near center ---
    # Slight random jitter to avoid being a sitting duck
    if _tick_count % 3 == 0:
        return "move", random.choice(["north", "south", "east", "west"])
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
    """Returns direction to shoot if target is on same row or column."""
    if tx == mx and ty != my:
        return "south" if ty > my else "north"
    if ty == my and tx != mx:
        return "east" if tx > mx else "west"
    return None

def align_for_shot(mx, my, tx, ty, map_w, map_h):
    """Move to get on the same row or column as target for a shot."""
    dx, dy = tx - mx, ty - my
    # If close to same row, align vertically
    if abs(dx) <= abs(dy) and dx != 0:
        return "east" if dx > 0 else "west"
    # If close to same column, align horizontally
    if abs(dy) < abs(dx) and dy != 0:
        return "south" if dy > 0 else "north"
    # Already aligned, move closer
    return toward(mx, my, tx, ty)

def safe_dodge(mx, my, prefer_dirs, map_w, map_h):
    """Dodge in preferred directions, but never toward an edge."""
    for d in prefer_dirs:
        if not moves_toward_edge(mx, my, d, map_w, map_h):
            return d
    # All preferred dodge dirs are toward edge — go to center instead
    return best_toward_center(mx, my, map_w, map_h)

def is_toward_center(mx, my, direction, map_w, map_h):
    """Check if a direction moves us closer to center."""
    ddx, ddy = DIRS.get(direction, (0, 0))
    nx, ny = mx + ddx, my + ddy
    return manhattan(nx, ny, CENTER_X, CENTER_Y) < manhattan(mx, my, CENTER_X, CENTER_Y)

def best_toward_center(mx, my, map_w, map_h, prefer=None):
    """Pick the direction that most reduces distance to center."""
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
    if ranked:
        return ranked[0][1]
    return "south"

def moves_toward_edge(mx, my, direction, map_w, map_h):
    """Returns True if moving in this direction puts us closer to an edge."""
    ddx, ddy = DIRS.get(direction, (0, 0))
    nx, ny = mx + ddx, my + ddy
    edge_dist_now = min(mx, my, map_w - 1 - mx, map_h - 1 - my)
    edge_dist_new = min(nx, ny, map_w - 1 - nx, map_h - 1 - ny)
    return edge_dist_new < edge_dist_now and edge_dist_new < EDGE_DANGER
