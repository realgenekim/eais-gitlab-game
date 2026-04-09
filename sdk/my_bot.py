#!/usr/bin/env python3
"""
=============================================================================
  CAB BATTLE BOT — Enterprise AI Summit Game Night
=============================================================================

  HOW TO PLAY:
    python my_bot.py --name "YourTeamName"

  HOW TO WIN:
    Edit the decide() function below with your strategy!

  SCORING:
    +100 points — deliver a passenger to their destination
    +150 points — eliminate a rival player
    +10 to +50  — kill an NPC enemy

  THE GAME:
    - You're a cab on a grid maze. You can see 5 tiles around you (fog of war).
    - Pick up passengers and deliver them to earn points.
    - Shoot rivals and NPC enemies for bonus points.
    - The arena shrinks over time (battle royale style).
    - Lightning strikes randomly destroy walls and kill anyone nearby.

=============================================================================
"""

import argparse
import random
from cab_battle import run_bot


# =============================================================================
#  YOUR STRATEGY — edit this function!
# =============================================================================

def decide(state):
    """
    Called every tick (~4 times per second). Return an action dict.

    WHAT YOU CAN SEE (state):
    -------------------------
    state["tick"]           - Current game tick number
    state["you"]            - Your cab's info:
        ["x"], ["y"]        - Your grid position
        ["hp"]              - Health points (max 500, you die at 0)
        ["score"]           - Your current score
        ["ammo"]            - Bullets remaining (regenerates over time)
        ["alive?"]          - Are you alive? (True/False)
        ["passenger"]       - Passenger you're carrying, or None
            ["dest"]["x"]   - Passenger's destination X
            ["dest"]["y"]   - Passenger's destination Y

    state["visible"]        - What you can see around you (fog of war):
        ["players"]         - Other players nearby:
            [{"id", "x", "y", "has-passenger"}]
        ["enemies"]         - NPC enemies nearby:
            [{"id", "x", "y", "hp", "type"}]
                             type: "floopy" (weak), "squanchy" (medium), "scary" (tough)
        ["passengers"]      - Passengers waiting for pickup:
            [{"id", "x", "y", "dest": {"x", "y"}}]
        ["shots"]           - Bullet tracers you can see:
            [{"shooter-id", "direction", "path": [[x,y], ...]}]

    state["map"]            - Map dimensions:
        ["width"], ["height"]

    WHAT YOU CAN DO (return one of these):
    --------------------------------------
    {"action": "move",    "direction": "north"}  — move up
    {"action": "move",    "direction": "south"}  — move down
    {"action": "move",    "direction": "east"}   — move right
    {"action": "move",    "direction": "west"}   — move left
    {"action": "shoot",   "direction": "east"}   — shoot (uses 1 ammo)
    {"action": "pickup"}                         — pick up passenger at your tile
    {"action": "dropoff"}                        — drop off passenger at destination

    TIPS:
    -----
    - You can only carry ONE passenger at a time
    - Shooting is line-of-sight: bullets travel in a straight line until they
      hit a wall, player, or enemy
    - Ammo regenerates: you get 1 bullet back every 5 ticks
    - If you die, you respawn after 10 ticks
    - The arena walls close in over time — don't get caught!
    """
    me = state["you"]
    visible = state["visible"]

    # --- Basic strategy: deliver passengers for points ---

    # If carrying a passenger, move toward their destination
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        # Are we at the destination? Drop off!
        if me["x"] == dest["x"] and me["y"] == dest["y"]:
            return {"action": "dropoff"}
        return move_toward(me["x"], me["y"], dest["x"], dest["y"])

    # If there's a passenger nearby, go pick them up
    if visible.get("passengers"):
        pax = visible["passengers"][0]  # grab the first one we see
        # Are we on top of the passenger? Pick up!
        if pax["x"] == me["x"] and pax["y"] == me["y"]:
            return {"action": "pickup"}
        return move_toward(me["x"], me["y"], pax["x"], pax["y"])

    # Nothing to do — wander randomly
    return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


# =============================================================================
#  HELPER FUNCTIONS — feel free to add more!
# =============================================================================

def move_toward(my_x, my_y, target_x, target_y):
    """Move one step toward a target position (greedy, no pathfinding)."""
    dx = target_x - my_x
    dy = target_y - my_y
    if abs(dx) > abs(dy):
        return {"action": "move", "direction": "east" if dx > 0 else "west"}
    elif dy != 0:
        return {"action": "move", "direction": "south" if dy > 0 else "north"}
    return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


def move_away(my_x, my_y, threat_x, threat_y):
    """Move one step away from a threat."""
    dx = my_x - threat_x
    dy = my_y - threat_y
    if abs(dx) > abs(dy):
        return {"action": "move", "direction": "east" if dx > 0 else "west"}
    elif dy != 0:
        return {"action": "move", "direction": "south" if dy > 0 else "north"}
    return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


def distance(x1, y1, x2, y2):
    """Manhattan distance between two points."""
    return abs(x2 - x1) + abs(y2 - y1)


def direction_to(my_x, my_y, target_x, target_y):
    """Get the cardinal direction from me to target.
    Returns 'north', 'south', 'east', or 'west'."""
    dx = target_x - my_x
    dy = target_y - my_y
    if abs(dx) >= abs(dy):
        return "east" if dx > 0 else "west"
    return "south" if dy > 0 else "north"


def can_shoot_at(my_x, my_y, target_x, target_y):
    """Check if target is on same row or column (can be shot at).
    Returns the direction to shoot, or None."""
    if my_x == target_x and my_y != target_y:
        return "south" if target_y > my_y else "north"
    if my_y == target_y and my_x != target_x:
        return "east" if target_x > my_x else "west"
    return None


# =============================================================================
#  EXAMPLE STRATEGIES — uncomment one to try it, or write your own!
# =============================================================================

# def decide(state):
#     """AGGRESSIVE — shoot everything, chase enemies."""
#     me = state["you"]
#     visible = state["visible"]
#     enemies = visible.get("enemies", [])
#     players = visible.get("players", [])
#     all_targets = enemies + players
#
#     if me["ammo"] > 0 and all_targets:
#         for t in all_targets:
#             shoot_dir = can_shoot_at(me["x"], me["y"], t["x"], t["y"])
#             if shoot_dir:
#                 return {"action": "shoot", "direction": shoot_dir}
#         # Move toward nearest target to line up a shot
#         nearest = min(all_targets, key=lambda t: distance(me["x"], me["y"], t["x"], t["y"]))
#         return move_toward(me["x"], me["y"], nearest["x"], nearest["y"])
#
#     return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


# def decide(state):
#     """BALANCED — shoot if lined up, otherwise deliver passengers."""
#     me = state["you"]
#     visible = state["visible"]
#
#     # Shoot anything we can
#     if me["ammo"] > 0:
#         for target in visible.get("enemies", []) + visible.get("players", []):
#             shoot_dir = can_shoot_at(me["x"], me["y"], target["x"], target["y"])
#             if shoot_dir:
#                 return {"action": "shoot", "direction": shoot_dir}
#
#     # Deliver passengers
#     if me.get("passenger"):
#         dest = me["passenger"]["dest"]
#         if me["x"] == dest["x"] and me["y"] == dest["y"]:
#             return {"action": "dropoff"}
#         return move_toward(me["x"], me["y"], dest["x"], dest["y"])
#
#     if visible.get("passengers"):
#         pax = min(visible["passengers"],
#                   key=lambda p: distance(me["x"], me["y"], p["x"], p["y"]))
#         if pax["x"] == me["x"] and pax["y"] == me["y"]:
#             return {"action": "pickup"}
#         return move_toward(me["x"], me["y"], pax["x"], pax["y"])
#
#     return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


# =============================================================================
#  GEAR SELECTION — pick your loadout before the battle starts!
#
#  Available gear (first come, first served — once someone takes it, it's gone):
#
#    plasma-rounds   — 2x shot damage (permanent)
#    titan-shield    — 50% damage reduction (permanent)
#    oracle-eye      — Double vision radius (permanent)
#    sprint-boots    — Move twice per tick (temporary)
#    vampiric-rounds — Heal 15 HP per hit (permanent)
#    juggernaut      — +300 bonus HP (instant)
#    ammo-belt       — Double ammo regen (permanent)
#    cluster-shot    — Shots hit 3-wide (temporary)
#
#  Pick 1-2 items below. If someone else already took it, pick a different one!
# =============================================================================

MY_GEAR = ["titan-shield"]  # <-- Change this! Pick from the list above


# =============================================================================
#  MAIN — you don't need to change anything below this line
# =============================================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Bot Battle Arena")
    parser.add_argument("--name", default="MyBot", help="Your bot's name on the scoreboard")
    parser.add_argument("--server", default="http://localhost:33333", help="Game server URL")
    args = parser.parse_args()

    from cab_battle import CabBattleClient

    # Join and select gear before entering the game loop
    client = CabBattleClient(args.server)
    client.join(args.name)

    # Equip gear
    for item in MY_GEAR:
        result = client.select_gear(item)
        if result and "error" in result:
            # Show what's still available
            gear = result.get("gear-catalog", {})
            available = [k for k, v in gear.items() if v.get("available")]
            print(f"  Still available: {', '.join(available)}")

    run_bot(name=args.name, decide_fn=decide, server_url=args.server)
