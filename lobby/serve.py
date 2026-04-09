#!/usr/bin/env python3
"""
Lobby server — watches a bot's loadout.py for changes and pushes updates
to the Phaser lobby scene over WebSocket. Handles deploy to game server.

Usage:
    python -m lobby.serve bots/starter_bot/
    python -m lobby.serve --server http://localhost:33333 bots/starter_bot/
"""

import asyncio
import base64
import json
import os
import subprocess
import sys
import time
import argparse
import traceback
from pathlib import Path

try:
    import websockets
    from websockets.asyncio.server import serve as ws_serve
except ImportError:
    print("Missing dependency: pip install websockets")
    sys.exit(1)

try:
    from watchdog.observers import Observer
    from watchdog.events import FileSystemEventHandler
except ImportError:
    print("Missing dependency: pip install watchdog")
    sys.exit(1)

from lobby.schema import validate_loadout, GEAR_CATALOG, BUDGET


# ---------------------------------------------------------------------------
# Loadout file loading
# ---------------------------------------------------------------------------

def load_loadout_from_file(bot_dir: Path) -> dict:
    """Load and execute loadout.py from the bot directory, extract LOADOUT dict."""
    loadout_file = bot_dir / "loadout.py"
    if not loadout_file.exists():
        return {"error": f"No loadout.py found in {bot_dir}"}

    namespace = {}
    try:
        code = loadout_file.read_text()
        exec(compile(code, str(loadout_file), "exec"), namespace)
    except Exception as e:
        return {"error": f"Error loading loadout.py: {e}\n{traceback.format_exc()}"}

    loadout_dict = namespace.get("LOADOUT")
    if not loadout_dict or not isinstance(loadout_dict, dict):
        return {"error": "loadout.py must define a LOADOUT dict"}

    return loadout_dict


AVATAR_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp', '.gif']
MIME_TYPES = {
    '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
    '.webp': 'image/webp', '.gif': 'image/gif',
}

def load_custom_avatar(bot_dir: Path) -> str | None:
    """Look for avatar.png/jpg/webp in bot dir, return as data URL or None."""
    for ext in AVATAR_EXTENSIONS:
        avatar_file = bot_dir / f"avatar{ext}"
        if avatar_file.exists():
            data = avatar_file.read_bytes()
            mime = MIME_TYPES.get(ext, 'image/png')
            b64 = base64.b64encode(data).decode('ascii')
            return f"data:{mime};base64,{b64}"
    return None


def build_lobby_message(bot_dir: Path, deploy_status: dict | None = None) -> str:
    """Load loadout, validate it, and build the JSON message for the lobby scene."""
    raw = load_loadout_from_file(bot_dir)

    if "error" in raw:
        msg = {
            "type": "loadout-update",
            "valid": False,
            "errors": [raw["error"]],
            "name": "???",
            "avatar": "TinyRick",
            "cost": 0,
            "budget": BUDGET,
            "stats": {},
            "gear": [],
            "items": [],
            "deploy": deploy_status or {"status": "idle"},
        }
        return json.dumps(msg)

    # Extract meta
    name = raw.get("name", "Unnamed Bot")
    avatar = raw.get("avatar", "TinyRick")

    # Build loadout for validation (just the gear part)
    gear = raw.get("gear", {})
    validation = validate_loadout(gear)

    # Build gear list with details for display
    gear_list = []
    for item_key in validation["items"]:
        item_info = GEAR_CATALOG.get(item_key, {})
        gear_list.append({
            "key": item_key,
            "slot": item_info.get("slot", "unknown"),
            "cost": item_info.get("cost", 0),
            "effects": item_info.get("effects", {}),
        })

    # Check for custom avatar image
    custom_avatar = load_custom_avatar(bot_dir)

    msg = {
        "type": "loadout-update",
        "valid": validation["valid"],
        "errors": validation["errors"],
        "name": name,
        "avatar": avatar,
        "cost": validation["cost"],
        "budget": validation["budget"],
        "stats": validation["stats"],
        "gear": gear_list,
        "items": validation["items"],
        "deploy": deploy_status or {"status": "idle"},
    }
    if custom_avatar:
        msg["avatar_image"] = custom_avatar
    return json.dumps(msg)


# ---------------------------------------------------------------------------
# Bot process management
# ---------------------------------------------------------------------------

bot_process: subprocess.Popen | None = None
deploy_state: dict = {"status": "idle"}  # idle | deploying | running | error


async def deploy_bot(bot_dir: Path, server_url: str, python_bin: str):
    """Launch the bot.py process against the game server."""
    global bot_process, deploy_state

    # Kill existing bot if running
    await kill_bot()

    bot_script = bot_dir / "bot.py"
    if not bot_script.exists():
        deploy_state = {"status": "error", "message": f"No bot.py found in {bot_dir}"}
        await broadcast_deploy_status()
        return

    # Validate loadout first
    raw = load_loadout_from_file(bot_dir)
    if "error" in raw:
        deploy_state = {"status": "error", "message": raw["error"]}
        await broadcast_deploy_status()
        return

    gear = raw.get("gear", {})
    validation = validate_loadout(gear)
    if not validation["valid"]:
        deploy_state = {"status": "error", "message": f"Invalid loadout: {validation['errors']}"}
        await broadcast_deploy_status()
        return

    deploy_state = {"status": "deploying", "message": "Starting bot..."}
    await broadcast_deploy_status()

    try:
        bot_name = raw.get("name", "Bot")
        bot_process = subprocess.Popen(
            [python_bin, str(bot_script), "--name", bot_name, "--server", server_url],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        deploy_state = {
            "status": "running",
            "message": f"{bot_name} is live!",
            "pid": bot_process.pid,
            "server": server_url,
        }
        print(f"  [deploy] Bot launched: pid={bot_process.pid} server={server_url}")
        await broadcast_deploy_status()

        # Monitor the process in background
        asyncio.get_event_loop().run_in_executor(None, _monitor_bot_process)

    except Exception as e:
        deploy_state = {"status": "error", "message": str(e)}
        await broadcast_deploy_status()


def _monitor_bot_process():
    """Watch the bot process and update status when it exits."""
    global bot_process, deploy_state
    if not bot_process:
        return
    bot_process.wait()
    exit_code = bot_process.returncode
    output = ""
    try:
        output = bot_process.stdout.read()[-500:] if bot_process.stdout else ""
    except Exception:
        pass
    if deploy_state.get("status") == "running":
        deploy_state = {
            "status": "stopped",
            "message": f"Bot exited (code {exit_code})",
            "output": output,
        }
        print(f"  [deploy] Bot exited: code={exit_code}")
    bot_process = None


async def kill_bot():
    """Stop the running bot process."""
    global bot_process, deploy_state
    if bot_process and bot_process.poll() is None:
        bot_process.terminate()
        try:
            bot_process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            bot_process.kill()
        print(f"  [deploy] Bot stopped")
    bot_process = None
    deploy_state = {"status": "idle"}


async def broadcast_deploy_status():
    """Push deploy status update to all connected clients."""
    msg = json.dumps({"type": "deploy-status", **deploy_state})
    await broadcast(msg)
    # Also rebuild full lobby message with deploy status
    global current_message
    current_message = build_lobby_message(_bot_dir, deploy_state)


# ---------------------------------------------------------------------------
# WebSocket server
# ---------------------------------------------------------------------------

connected_clients = set()
current_message = None  # latest loadout message, sent on connect
_bot_dir: Path = Path(".")  # set in main()
_server_url: str = "http://localhost:33333"
_python_bin: str = sys.executable


async def ws_handler(websocket):
    """Handle a single WebSocket connection."""
    connected_clients.add(websocket)
    print(f"  [lobby] Client connected ({len(connected_clients)} total)")

    # Send current state immediately
    if current_message:
        try:
            await websocket.send(current_message)
        except Exception:
            pass

    # Send gear catalog so the UI knows all available items
    catalog_msg = json.dumps({
        "type": "gear-catalog",
        "budget": BUDGET,
        "gear": GEAR_CATALOG,
    })
    try:
        await websocket.send(catalog_msg)
    except Exception:
        pass

    try:
        async for raw_msg in websocket:
            # Handle commands from the lobby UI
            try:
                cmd = json.loads(raw_msg)
                await handle_ws_command(cmd)
            except Exception as e:
                print(f"  [lobby] Command error: {e}")
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_clients.discard(websocket)
        print(f"  [lobby] Client disconnected ({len(connected_clients)} total)")


async def handle_ws_command(cmd: dict):
    """Handle a command from the lobby UI."""
    action = cmd.get("action")
    if action == "deploy":
        await deploy_bot(_bot_dir, _server_url, _python_bin)
    elif action == "stop":
        await kill_bot()
        await broadcast_deploy_status()
    elif action == "status":
        await broadcast_deploy_status()


async def broadcast(message: str):
    """Push a message to all connected WebSocket clients."""
    global current_message
    current_message = message

    if not connected_clients:
        return

    dead = set()
    for ws in connected_clients:
        try:
            await ws.send(message)
        except Exception:
            dead.add(ws)
    connected_clients.difference_update(dead)


# ---------------------------------------------------------------------------
# File watcher
# ---------------------------------------------------------------------------

class LoadoutWatcher(FileSystemEventHandler):
    """Watches bot directory for file changes and triggers reload."""

    def __init__(self, bot_dir: Path, loop: asyncio.AbstractEventLoop):
        self.bot_dir = bot_dir
        self.loop = loop
        self._debounce_time = 0

    def on_modified(self, event):
        watched = ('.py', '.png', '.jpg', '.jpeg', '.webp', '.gif')
        if not any(event.src_path.endswith(ext) for ext in watched):
            return
        # Debounce — editors sometimes trigger multiple saves
        now = time.time()
        if now - self._debounce_time < 0.3:
            return
        self._debounce_time = now

        filename = os.path.basename(event.src_path)
        print(f"  [lobby] File changed: {filename} — reloading loadout...")
        asyncio.run_coroutine_threadsafe(self._reload(), self.loop)

    def on_created(self, event):
        self.on_modified(event)

    async def _reload(self):
        message = build_lobby_message(self.bot_dir, deploy_state)
        parsed = json.loads(message)
        if parsed["valid"]:
            print(f"  [lobby] Loadout valid: {parsed['name']} | "
                  f"{parsed['cost']}/{parsed['budget']} pts | "
                  f"items: {parsed['items']}")
        else:
            print(f"  [lobby] Loadout INVALID: {parsed['errors']}")
        await broadcast(message)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def main(bot_dir: Path, port: int, server_url: str):
    global current_message, _bot_dir, _server_url
    _bot_dir = bot_dir
    _server_url = server_url

    print(f"=== Battle Cab Lobby Server ===")
    print(f"  Bot directory:  {bot_dir.resolve()}")
    print(f"  Game server:    {server_url}")
    print(f"  WebSocket:      ws://localhost:{port}")
    print(f"  Lobby UI:       http://localhost:5173/?lobby")
    print()

    # Initial load
    current_message = build_lobby_message(bot_dir, deploy_state)
    parsed = json.loads(current_message)
    if parsed["valid"]:
        print(f"  Loadout loaded: {parsed['name']} | "
              f"{parsed['cost']}/{parsed['budget']} pts | "
              f"items: {parsed['items']}")
    else:
        print(f"  Loadout errors: {parsed['errors']}")
    print()
    print(f"  Deploy from UI:   Click DEPLOY in the lobby")
    print(f"  Deploy from CLI:  make deploy BOT={bot_dir}")
    print(f"  Deploy from code: curl -X POST http://localhost:{port + 1}/deploy")
    print()

    # Start file watcher
    loop = asyncio.get_event_loop()
    watcher = LoadoutWatcher(bot_dir, loop)
    observer = Observer()
    observer.schedule(watcher, str(bot_dir), recursive=False)
    observer.start()
    print(f"  Watching {bot_dir} for changes...")

    # Start WebSocket server
    async with ws_serve(ws_handler, "localhost", port):
        print(f"  WebSocket server running on port {port}")
        print()
        print("  Edit your bot's loadout.py — changes auto-push to the lobby UI!")
        print()
        await asyncio.Future()  # run forever


def cli():
    parser = argparse.ArgumentParser(
        description="Lobby server — live-preview your bot's loadout"
    )
    parser.add_argument("bot_dir", help="Path to your bot directory (e.g., bots/starter_bot/)")
    parser.add_argument("--port", type=int, default=9876, help="WebSocket port (default: 9876)")
    parser.add_argument("--server", default="http://localhost:33333", help="Game server URL")
    args = parser.parse_args()

    bot_dir = Path(args.bot_dir)
    if not bot_dir.is_dir():
        print(f"Error: {bot_dir} is not a directory")
        sys.exit(1)

    try:
        asyncio.run(main(bot_dir, args.port, args.server))
    except KeyboardInterrupt:
        print("\nLobby server stopped.")
        # Clean up bot process
        if bot_process and bot_process.poll() is None:
            bot_process.terminate()


if __name__ == "__main__":
    cli()
