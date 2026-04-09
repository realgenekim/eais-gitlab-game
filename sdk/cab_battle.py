"""
Cab Battle SDK — handles all server communication.
You don't need to edit this file! Edit my_bot.py instead.
"""

import requests
import time
import sys


class CabBattleClient:
    """Client for the Cab Battle game server."""

    def __init__(self, server_url="http://localhost:33333"):
        self.server = server_url.rstrip("/")
        self.token = None
        self.player_id = None

    def join(self, name):
        """Join the game. Call this once at startup.

        Returns your player info dict, or exits with a helpful error.
        """
        try:
            resp = requests.post(
                f"{self.server}/game/join",
                json={"name": name},
                timeout=5,
            )
        except requests.ConnectionError:
            print(f"\n  Could not connect to {self.server}")
            print("  Is the game server running?\n")
            sys.exit(1)

        data = resp.json()
        if "error" in data:
            print(f"\n  Server said: {data['error']}\n")
            sys.exit(1)

        self.token = data["token"]
        self.player_id = data["player-id"]
        print(f"  Joined as '{name}' (id: {self.player_id})")

        # Verify we're actually registered by checking status
        status = self.get_status()
        if status and name in status.get("player-names", []):
            print(f"  Confirmed: '{name}' is in the lobby ({status['players']} players total)")
        else:
            print(f"  WARNING: Join succeeded but '{name}' not found in server status!")
            print(f"  Server says: {status}")

        return data

    def get_state(self):
        """Get your fog-of-war view of the game.

        Returns a dict with keys: tick, you, visible, map.
        Returns None if the request fails (server down, etc.).
        """
        try:
            resp = requests.get(
                f"{self.server}/game/state",
                headers={"Authorization": self.token},
                timeout=3,
            )
            return resp.json()
        except Exception:
            return None

    # --- Convenience action methods ---

    def move(self, direction):
        """Move your cab. direction: 'north', 'south', 'east', or 'west'."""
        return self._action({"action": "move", "direction": direction})

    def shoot(self, direction):
        """Shoot in a direction. direction: 'north', 'south', 'east', or 'west'."""
        return self._action({"action": "shoot", "direction": direction})

    def pickup(self):
        """Pick up a passenger at your current location."""
        return self._action({"action": "pickup"})

    def dropoff(self):
        """Drop off your passenger at their destination."""
        return self._action({"action": "dropoff"})

    def do_action(self, action_dict):
        """Send a raw action dict. Used by the game loop."""
        return self._action(action_dict)

    # --- Info endpoints (no auth needed) ---

    def get_scoreboard(self):
        """Get the current scoreboard."""
        try:
            resp = requests.get(f"{self.server}/game/scoreboard", timeout=3)
            return resp.json()
        except Exception:
            return None

    def get_map(self):
        """Get the full map layout (walls, dimensions)."""
        try:
            resp = requests.get(f"{self.server}/game/map", timeout=3)
            return resp.json()
        except Exception:
            return None

    def get_status(self):
        """Check if the game is running."""
        try:
            resp = requests.get(f"{self.server}/game/status", timeout=3)
            return resp.json()
        except Exception:
            return None

    # --- Internal ---

    def _action(self, action_dict):
        """Send an action to the server."""
        try:
            resp = requests.post(
                f"{self.server}/game/action",
                headers={"Authorization": self.token},
                json=action_dict,
                timeout=3,
            )
            return resp.json()
        except Exception:
            return None


def run_bot(name, decide_fn, server_url="http://localhost:33333", tick_delay=0.25):
    """Run your bot in a loop. This handles joining, polling, and error recovery.

    Args:
        name:        Your bot's display name (shown on the scoreboard)
        decide_fn:   Your strategy function: decide(game_state) -> action dict
        server_url:  Game server URL (default: http://localhost:33333)
        tick_delay:  Seconds between actions (default: 0.25 = match server tick rate)
    """
    client = CabBattleClient(server_url)

    print(f"\n  Cab Battle Bot: {name}")
    print(f"  Server: {server_url}")
    print()

    client.join(name)
    print(f"  Registered! Waiting for game to start...")
    print(f"  Watch at http://localhost:5173/?server")
    print(f"  Press Ctrl+C to stop.\n")

    # Wait for lobby phase to end
    game_started = False
    while not game_started:
        try:
            state = client.get_state()
            if state is None:
                time.sleep(1)
                continue
            phase = state.get("phase", "playing")
            if phase == "lobby":
                time.sleep(0.5)
            else:
                game_started = True
                print(f"  Game started! Let's go!")
        except KeyboardInterrupt:
            print("\n  Bot stopped.")
            return
        except Exception:
            time.sleep(1)

    # Main game loop
    while True:
        try:
            state = client.get_state()

            if state is None:
                print("  Lost connection, retrying...")
                time.sleep(2)
                continue

            # If game went back to lobby (restart), wait again
            if state.get("phase") == "lobby":
                print("  Game restarted. Waiting in lobby...")
                time.sleep(1)
                continue

            # Skip if dead (waiting for respawn)
            me = state.get("you", {})
            if not me.get("alive?", True):
                time.sleep(tick_delay)
                continue

            # Call the player's strategy
            action = decide_fn(state)

            if action:
                client.do_action(action)

            time.sleep(tick_delay)

        except KeyboardInterrupt:
            print("\n  Bot stopped. GG!")
            break
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)
