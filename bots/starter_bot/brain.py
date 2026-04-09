"""
YOUR BOT'S BRAIN — edit this file while your bot is running!

Every 250ms, the game calls your think() function with the current state.
Return (action, direction) to tell your bot what to do.

ACTIONS:
  ("move", "north")        — move one tile (2 with speed-boost)
  ("shoot", "east")        — shoot in a direction (uses ammo)
  ("pickup", None)         — pick up a passenger at your position
  ("dropoff", None)        — deliver passenger at destination (+100 pts)
  ("grenade", "south")     — area damage, radius 2 (uses a grenade)
  ("teleport", None)       — random teleport (needs teleporter gear)
  ("trap", None)           — place invisible mine (needs trap-mine gear)
  ("decoy", None)          — place fake blip to lure enemies (needs decoy gear)

STATE you receive:
  state["you"]             — your bot: x, y, hp, ammo, grenades, score, alive?
  state["visible"]["enemies"]   — nearby NPC enemies: x, y, hp, type
  state["visible"]["players"]   — nearby rival bots: x, y, has-passenger
  state["visible"]["passengers"] — nearby passengers: x, y, dest
  state["visible"]["shots"]     — incoming bullets
  state["map"]             — width, height

TIPS:
  - You can only shoot in cardinal directions (north/south/east/west)
  - Shots only hit if the target is on the exact same row or column
  - Enemies chase you — kiting (shoot then retreat) is effective
  - Passengers are worth 100 pts, kills are 150 pts
  - The arena shrinks over time — stay near the center in late game
  - Save this file to hot-reload your strategy while the game is running!
"""


def pick_direction(mx, my, tx, ty):
    """Pick cardinal direction from (mx,my) toward (tx,ty)."""
    dx, dy = tx - mx, ty - my
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"


def think(state):
    """
    Your bot's brain. Called every tick.
    Return (action, direction) or (None, None) to do nothing.
    """
    me = state["you"]
    if not me.get("alive?", True):
        return None, None

    mx, my = me["x"], me["y"]
    ammo = me.get("ammo", 0)
    enemies = state["visible"].get("enemies", [])
    players = state["visible"].get("players", [])

    # Shoot at enemies on same row/column
    if ammo > 0:
        for target in enemies + players:
            tx, ty = target["x"], target["y"]
            if tx == mx and ty != my:
                return "shoot", "south" if ty > my else "north"
            if ty == my and tx != mx:
                return "shoot", "east" if tx > mx else "west"

    # Move toward nearest enemy
    if enemies:
        nearest = min(enemies, key=lambda e: abs(e["x"] - mx) + abs(e["y"] - my))
        return "move", pick_direction(mx, my, nearest["x"], nearest["y"])

    # Wander toward center
    return "move", pick_direction(mx, my, 10, 9)
