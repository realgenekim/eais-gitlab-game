"""
BERZERKER brain — aggressive close-range chaos.
Rushes enemies, shotguns point-blank, grenades clusters, drops traps while retreating.
"""
import random


def pick_direction(mx, my, tx, ty):
    dx, dy = tx - mx, ty - my
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"


def away_from(mx, my, tx, ty):
    opposites = {"north": "south", "south": "north", "east": "west", "west": "east"}
    return opposites[pick_direction(mx, my, tx, ty)]


def dist(mx, my, tx, ty):
    return abs(tx - mx) + abs(ty - my)


def think(state):
    me = state["you"]
    if not me.get("alive?", True):
        return None, None

    mx, my = me["x"], me["y"]
    ammo = me.get("ammo", 0)
    grenades = me.get("grenades", 0)
    hp = me.get("hp", 500)
    enemies = state["visible"].get("enemies", [])
    players = state["visible"].get("players", [])
    all_targets = enemies + players

    if not all_targets:
        if random.random() < 0.15:
            return "trap", None
        return "move", random.choice(["north", "south", "east", "west"])

    nearest = min(all_targets, key=lambda t: dist(mx, my, t["x"], t["y"]))
    nd = dist(mx, my, nearest["x"], nearest["y"])
    nx, ny = nearest["x"], nearest["y"]

    # LOW HP — drop trap and run
    if hp < 150:
        if random.random() < 0.4:
            return "trap", None
        return "move", away_from(mx, my, nx, ny)

    # GRENADE clusters
    if grenades > 0:
        nearby = [t for t in all_targets if dist(mx, my, t["x"], t["y"]) <= 5]
        if len(nearby) >= 2:
            cx = sum(t["x"] for t in nearby) // len(nearby)
            cy = sum(t["y"] for t in nearby) // len(nearby)
            return "grenade", pick_direction(mx, my, cx, cy)
        if nd <= 4 and nearest.get("hp", 100) >= 50:
            return "grenade", pick_direction(mx, my, nx, ny)

    # SHOOT if on axis and in range
    if ammo > 0:
        for t in all_targets:
            tx, ty = t["x"], t["y"]
            d = dist(mx, my, tx, ty)
            if d <= 3:
                if tx == mx and ty != my:
                    return "shoot", "south" if ty > my else "north"
                if ty == my and tx != mx:
                    return "shoot", "east" if tx > mx else "west"

    # PICKUP passengers for easy points
    passengers = state["visible"].get("passengers", [])
    if passengers and not me.get("passenger"):
        nearest_pax = min(passengers, key=lambda p: dist(mx, my, p["x"], p["y"]))
        pd = dist(mx, my, nearest_pax["x"], nearest_pax["y"])
        if pd == 0:
            return "pickup", None
        if pd < 4:
            return "move", pick_direction(mx, my, nearest_pax["x"], nearest_pax["y"])

    # DELIVER if carrying
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        if mx == dest["x"] and my == dest["y"]:
            return "dropoff", None
        return "move", pick_direction(mx, my, dest["x"], dest["y"])

    # RUSH
    if nd > 1:
        return "move", pick_direction(mx, my, nx, ny)

    if ammo > 0:
        return "shoot", pick_direction(mx, my, nx, ny)

    return "trap", None
