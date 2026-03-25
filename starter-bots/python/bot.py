#!/usr/bin/env python3
"""
Starter bot for Cab Battle — EAIS Game Night
=============================================
This is your template! Modify the `decide()` function to build your strategy.

Usage:
    python bot.py --server http://localhost:8080 --name "YourName"
"""

import requests
import time
import argparse
import random

SERVER = "http://localhost:8080"
TOKEN = None
PLAYER_ID = None


def join(name):
    global TOKEN, PLAYER_ID
    resp = requests.post(f"{SERVER}/game/join", json={"name": name})
    data = resp.json()
    TOKEN = data["token"]
    PLAYER_ID = data["player-id"]
    print(f"Joined as {PLAYER_ID} — token: {TOKEN[:8]}...")


def get_state():
    resp = requests.get(f"{SERVER}/game/state", params={"token": TOKEN})
    return resp.json()


def do_action(action_dict):
    action_dict["token"] = TOKEN
    resp = requests.post(f"{SERVER}/game/action", json=action_dict)
    return resp.json()


# =============================================================================
# YOUR STRATEGY HERE — modify this function!
# =============================================================================

def decide(state):
    """
    Given the game state (your view), return an action dict.

    State looks like:
    {
      "tick": 42,
      "you": {"x": 5, "y": 10, "hp": 100, "score": 0, "passenger": null, "ammo": 5},
      "visible": {
        "players": [{"id": "player-xxx", "x": 7, "y": 10, "has-passenger": true}],
        "passengers": [{"id": "pax-1", "x": 2, "y": 15, "dest": {"x": 18, "y": 3}}]
      }
    }

    Actions you can return:
      {"action": "move", "direction": "north|south|east|west"}
      {"action": "pickup"}
      {"action": "dropoff"}
      {"action": "shoot", "direction": "north|south|east|west"}
    """
    me = state["you"]
    visible = state["visible"]

    # --- Strategy: pick up passengers and deliver them ---

    # If carrying a passenger, move toward destination
    if me.get("passenger"):
        dest = me["passenger"]["dest"]
        return move_toward(me["x"], me["y"], dest["x"], dest["y"])

    # If there's a visible passenger, move toward it
    if visible.get("passengers"):
        pax = visible["passengers"][0]
        if pax["x"] == me["x"] and pax["y"] == me["y"]:
            return {"action": "pickup"}
        return move_toward(me["x"], me["y"], pax["x"], pax["y"])

    # Otherwise, wander randomly
    return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


def move_toward(x1, y1, x2, y2):
    """Simple greedy movement toward target (no pathfinding — you could add A*!)."""
    dx = x2 - x1
    dy = y2 - y1
    if abs(dx) > abs(dy):
        return {"action": "move", "direction": "east" if dx > 0 else "west"}
    elif dy != 0:
        return {"action": "move", "direction": "south" if dy > 0 else "north"}
    else:
        return {"action": "move", "direction": random.choice(["north", "south", "east", "west"])}


# =============================================================================
# Main loop — don't need to change this
# =============================================================================

def main():
    parser = argparse.ArgumentParser(description="Cab Battle Bot")
    parser.add_argument("--server", default="http://localhost:8080")
    parser.add_argument("--name", default="python-bot")
    args = parser.parse_args()

    global SERVER
    SERVER = args.server

    join(args.name)
    print(f"Playing as {PLAYER_ID}!")

    while True:
        try:
            state = get_state()
            if not state.get("you", {}).get("alive?", True):
                print(f"[tick {state['tick']}] Dead... waiting for respawn")
                time.sleep(0.5)
                continue

            action = decide(state)
            do_action(action)
            print(f"[tick {state['tick']}] pos=({state['you']['x']},{state['you']['y']}) "
                  f"score={state['you']['score']} → {action.get('action')} {action.get('direction','')}")
            time.sleep(0.5)
        except KeyboardInterrupt:
            print("\nGG!")
            break
        except Exception as e:
            print(f"Error: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()
