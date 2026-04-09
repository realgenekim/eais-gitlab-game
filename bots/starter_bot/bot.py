#!/usr/bin/env python3
"""
Bot runner with hot-reload.

Connects to the game server, then watches your bot directory for changes.
When you save brain.py (or any .py file), it reloads your think() function
WITHOUT disconnecting — your bot keeps its score, HP, and position.

Usage:
    python bots/starter_bot/bot.py --server http://localhost:33333

You edit brain.py. This file handles the rest.
"""

import importlib
import importlib.util
import os
import requests
import sys
import time
import argparse
import traceback
from pathlib import Path

BOT_DIR = Path(__file__).parent
sys.path.insert(0, str(BOT_DIR))

from loadout import LOADOUT


# ---------------------------------------------------------------------------
# Game server connection
# ---------------------------------------------------------------------------

def join(server, loadout):
    payload = {"name": loadout.get("name", "Bot"), "loadout": loadout.get("gear", {})}
    resp = requests.post(f"{server}/game/join", json=payload)
    data = resp.json()
    if "error" in data:
        print(f"  FAILED TO JOIN: {data}")
        sys.exit(1)
    return data["player-id"], data["token"]


def get_state(server, token):
    return requests.get(f"{server}/game/state", headers={"Authorization": token}).json()


def send_action(server, token, action, direction=None):
    body = {"action": action}
    if direction:
        body["direction"] = direction
    requests.post(f"{server}/game/action", headers={"Authorization": token}, json=body)


# ---------------------------------------------------------------------------
# Hot-reload system
# ---------------------------------------------------------------------------

class HotBrain:
    """Watches brain.py and reloads think() on file changes."""

    def __init__(self, bot_dir: Path):
        self.bot_dir = bot_dir
        self.brain_path = bot_dir / "brain.py"
        self._think_fn = None
        self._last_mtimes = {}
        self._load_count = 0
        self._reload()

    def _get_mtimes(self):
        """Get modification times of all .py files in bot dir."""
        mtimes = {}
        for f in self.bot_dir.glob("*.py"):
            if f.name == "bot.py":
                continue  # don't watch ourselves
            try:
                mtimes[str(f)] = os.path.getmtime(f)
            except OSError:
                pass
        return mtimes

    def _reload(self):
        """Reload brain.py and extract think()."""
        if not self.brain_path.exists():
            print("  [hot-reload] brain.py not found!")
            return

        namespace = {}
        try:
            code = self.brain_path.read_text()
            exec(compile(code, str(self.brain_path), "exec"), namespace)
        except Exception:
            print(f"  [hot-reload] ERROR in brain.py:")
            traceback.print_exc()
            print(f"  [hot-reload] Keeping previous brain.")
            return

        if "think" not in namespace:
            print("  [hot-reload] brain.py has no think() function!")
            return

        self._think_fn = namespace["think"]
        self._load_count += 1
        self._last_mtimes = self._get_mtimes()

        if self._load_count == 1:
            print(f"  [brain] Loaded brain.py")
        else:
            print(f"  [brain] Reloaded brain.py (v{self._load_count})")

    def check_reload(self):
        """Check if any .py files changed and reload if so."""
        current = self._get_mtimes()
        if current != self._last_mtimes:
            self._reload()

    def think(self, state):
        """Call the loaded think() function."""
        if self._think_fn is None:
            return None, None
        try:
            result = self._think_fn(state)
            if result is None:
                return None, None
            return result
        except Exception:
            print(f"  [brain] ERROR in think():")
            traceback.print_exc()
            return None, None


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Bot Runner (hot-reload)")
    parser.add_argument("--name", default=None, help="Override bot name")
    parser.add_argument("--server", default="http://localhost:33333", help="Server URL")
    args = parser.parse_args()

    if args.name:
        LOADOUT["name"] = args.name

    bot_name = LOADOUT.get("name", "Bot")

    print(f"")
    print(f"  ╔══════════════════════════════════════╗")
    print(f"  ║  {bot_name:^34}  ║")
    print(f"  ╠══════════════════════════════════════╣")
    print(f"  ║  Server: {args.server:<27} ║")
    print(f"  ║  Gear:   {str(list(LOADOUT.get('gear',{}).values()))[:27]:<27} ║")
    print(f"  ║                                      ║")
    print(f"  ║  Edit brain.py — auto-reloads live!   ║")
    print(f"  ╚══════════════════════════════════════╝")
    print(f"")

    # Join game
    player_id, token = join(args.server, LOADOUT)
    print(f"  Joined as {bot_name} ({player_id})")
    print(f"  Gear: {LOADOUT.get('gear', {})}")
    print(f"")

    # Init hot-reload brain
    brain = HotBrain(BOT_DIR)

    tick_count = 0
    last_score = 0
    reload_check_interval = 4  # check for file changes every N ticks

    while True:
        try:
            # Check for brain.py changes periodically (not every tick)
            if tick_count % reload_check_interval == 0:
                brain.check_reload()

            # Get state and think
            state = get_state(args.server, token)
            action, direction = brain.think(state)

            if action:
                send_action(args.server, token, action, direction)

            # Log status periodically
            tick_count += 1
            me = state.get("you", {})
            score = me.get("score", 0)

            if tick_count % 20 == 0:
                alive = "ALIVE" if me.get("alive?", False) else "DEAD "
                n_enemies = len(state.get("visible", {}).get("enemies", []))
                n_players = len(state.get("visible", {}).get("players", []))
                print(f"  tick {state.get('tick', '?'):>5} | {alive} | "
                      f"HP {me.get('hp', '?'):>4} | Score {score:>5} | "
                      f"Ammo {me.get('ammo', '?'):>2} | "
                      f"Enemies {n_enemies} | Players {n_players}")

            if score > last_score:
                diff = score - last_score
                print(f"  >>> +{diff} points! (total: {score})")
                last_score = score

            time.sleep(0.25)

        except KeyboardInterrupt:
            print(f"\n  {bot_name} stopped.")
            break
        except requests.exceptions.ConnectionError:
            print(f"  Connection lost — retrying in 2s...")
            time.sleep(2)
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()
