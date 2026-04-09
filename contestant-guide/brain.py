"""
YOUR BOT'S BRAIN — the only file you need to edit!

HOW TO VIBECODE:
  1. Open this file in Claude Code, ChatGPT, or Cursor
  2. Tell the AI what you want: "make my bot more aggressive"
  3. Save the file — your bot reloads LIVE, no restart needed
  4. Watch the big screen, iterate, repeat!

WHAT YOUR BOT CAN DO:
  ("move", "north")    — move up (also: south, east, west)
  ("shoot", "east")    — fire a shot (same row/column only)
  ("pickup", None)     — grab a mission at your tile
  ("dropoff", None)    — complete mission at destination (+100 pts)

TRY TELLING YOUR AI:
  "Make my bot dodge bullets"
  "Make my bot chase the nearest enemy and shoot it"
  "Make my bot run away when HP is low"
  "Make my bot focus on delivering passengers"
  "Add pathfinding so my bot doesn't get stuck on walls"
"""


# =====================================================
#  SETTINGS — tweak these to change behavior quickly
# =====================================================

FLEE_HP = 200          # run away when HP drops below this
SHOOT_FIRST = True     # shoot enemies before doing missions?
PREFER_MISSIONS = True # prioritize passenger delivery for points?


# =====================================================
#  BRAIN — the AI calls this every tick (~4x per second)
# =====================================================

def think(state):
    me = state["you"]
    if not me.get("alive?", True):
        return None, None

    mx, my = me["x"], me["y"]
    hp = me.get("hp", 500)
    ammo = me.get("ammo", 0)

    enemies = state["visible"].get("enemies", [])
    players = state["visible"].get("players", [])
    passengers = state["visible"].get("passengers", [])
    shots = state["visible"].get("shots", [])
    threats = enemies + players

    # --- DODGE incoming bullets ---
    for shot in shots:
        for cell in shot.get("path", []):
            if cell[0] == mx and cell[1] == my:
                d = shot.get("direction", "")
                if d in ("north", "south"):
                    return "move", "east"
                return "move", "north"

    # --- FLEE when HP is low ---
    if hp < FLEE_HP and threats:
        nearest = closest(mx, my, threats)
        return "move", away_from(mx, my, nearest["x"], nearest["y"])

    # --- SHOOT anything lined up ---
    if SHOOT_FIRST and ammo > 0:
        for t in threats:
            d = can_shoot(mx, my, t["x"], t["y"])
            if d:
                return "shoot", d

    # --- DELIVER mission ---
    if PREFER_MISSIONS and me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return "dropoff", None
        return "move", toward(mx, my, dest["x"], dest["y"])

    # --- PICKUP nearest mission ---
    if PREFER_MISSIONS and passengers:
        p = closest(mx, my, passengers)
        if p["x"] == mx and p["y"] == my:
            return "pickup", None
        return "move", toward(mx, my, p["x"], p["y"])

    # --- CHASE nearest enemy ---
    if threats:
        t = closest(mx, my, threats)
        return "move", toward(mx, my, t["x"], t["y"])

    # --- WANDER toward center ---
    return "move", toward(mx, my, 10, 9)


# =====================================================
#  HELPERS — use these in your strategy
# =====================================================

def toward(mx, my, tx, ty):
    """Direction from me toward target."""
    dx, dy = tx - mx, ty - my
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"

def away_from(mx, my, tx, ty):
    """Direction from me away from threat."""
    dx, dy = mx - tx, my - ty
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"

def closest(mx, my, targets):
    """Find the nearest target by manhattan distance."""
    return min(targets, key=lambda t: abs(t["x"] - mx) + abs(t["y"] - my))

def can_shoot(mx, my, tx, ty):
    """If target is on same row/col, return the direction to shoot. Else None."""
    if tx == mx and ty != my:
        return "south" if ty > my else "north"
    if ty == my and tx != mx:
        return "east" if tx > mx else "west"
    return None
