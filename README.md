# Cab Battle: LLM Bot Arena

A multiplayer bot-programming game where players vibe code AI bots that battle in a grid arena. Built for the **Enterprise AI Summit** game night.

## How It Works

You write a Python bot that connects to a remote game server via REST API. Your bot reads game state each tick, decides what to do, and sends commands. The audience watches all bots battle it out on a live spectator view.

## Prerequisites

- **Python 3.10+** (check: `python3 --version`)
- **Node.js 18+** (check: `node --version`) — only needed for the lobby gear room UI

## Quick Start (Competitors)

```bash
# 1. Clone and set up your bot
git clone <repo-url>
cd eais-gitlab-game
make setup NAME=my-bot

# 2. Preview your bot in the gear room
#    Terminal 1: start the lobby UI server
cd spectator && npm install && npm run dev
#    Terminal 2: start the lobby backend (watches your bot for changes)
make lobby BOT=bots/my-bot
#    Open http://localhost:5173/?lobby in your browser

# 3. Deploy to the live arena
make deploy BOT=bots/my-bot
```

Steps 1 and 3 are the essentials. Step 2 (the lobby) is optional but recommended — it lets you preview your gear and stats live as you edit.

### What `make setup` Does

- Creates a Python virtual environment with dependencies
- Copies the starter bot template to `bots/my-bot/`
- You get these files:
  - **`loadout.py`** — pick your gear (weapons, armor, gadgets)
  - **`brain.py`** — your bot's brain (the `think()` function) — **this is where you spend your time**
  - **`bot.py`** — the runner framework (handles connection, hot-reload — don't edit this)

### Hot-Reload: Edit While Fighting

Once you `make deploy`, your bot joins the game and starts fighting. **You can keep editing `brain.py` while it runs.** Every time you save, your bot's strategy reloads instantly — same HP, same score, same position, just smarter.

This means you're not building then watching — you're iterating live:
1. Deploy early with the basic starter brain
2. Watch your bot fight on the spectator screen
3. See it make bad decisions? Edit `brain.py`, save, watch it adapt
4. Keep improving for the entire match

## The Gear System

Before entering the arena, you equip your bot with gear. You have **100 budget points** to spend.

### Weapons (pick one)

| Item | Cost | Effect |
|------|------|--------|
| Standard Blaster | 0 | 30 dmg, range 5 |
| Shotgun | 15 | 50 dmg, range 3 |
| Sniper Rifle | 20 | 40 dmg, range 12 |
| Plasma Cannon | 25 | 60 dmg, range 5, 2-tick cooldown |

### Armor (pick one)

| Item | Cost | Effect |
|------|------|--------|
| Light Vest | 10 | 600 HP (default 500) |
| Heavy Armor | 25 | 750 HP |
| Energy Shield | 20 | 50 shield HP — absorbs damage first |

### Movement (pick one)

| Item | Cost | Effect |
|------|------|--------|
| Speed Boost | 15 | Move 2 tiles per action |
| Teleporter | 30 | Random teleport, 20-tick cooldown |

### Utility (pick multiple)

| Item | Cost | Effect |
|------|------|--------|
| Radar | 15 | See 8 tiles (default 5) |
| Extra Ammo | 10 | Start with 10 ammo, max 15 |
| Grenades+ | 10 | Start with 5 grenades |
| Trap Mine | 15 | Place invisible mines (60 dmg) |
| Decoy | 20 | Fake blip that lures enemies |

### Example Loadout (`loadout.py`)

```python
LOADOUT = {
    "name": "my-bot",
    "avatar": "RickNinja",  # or drop an avatar.png in your bot folder

    "gear": {
        "weapon": "sniper-rifle",     # 20 pts
        "armor": "energy-shield",     # 20 pts
        "movement": "speed-boost",    # 15 pts
        "utility": [
            "radar",                  # 15 pts
            "extra-ammo",             # 10 pts
        ],
    },
    # Total: 80 / 100 pts
}
```

## The Lobby (Gear Room)

Run `make lobby BOT=bots/my-bot` and open `http://localhost:5173/?lobby`.

You'll see your bot's character in a Fortnite-style gear room with:
- Live stat bars (HP, damage, range, speed, vision, ammo, grenades, shield)
- Equipped gear list with costs
- Budget meter
- **DEPLOY button** to send your bot to the live arena

Every time you save `loadout.py`, the gear room updates instantly. Drop an `avatar.png` in your bot folder for a custom character image.

## Bot API

Your bot connects to the game server via HTTP:

```
POST /game/join         {name, loadout}               -> {player-id, token}
GET  /game/state        Authorization: <token>        -> {tick, you, visible, traps, map}
POST /game/action       {action, direction}           -> {status: "queued"}
GET  /game/scoreboard                                 -> {tick, scores}
GET  /game/status                                     -> {tick, players, running}
GET  /game/gear-catalog                               -> {budget, gear}
```

### Available Actions

| Action | Params | Notes |
|--------|--------|-------|
| `move` | `direction`: north/south/east/west | Speed boost = 2 tiles |
| `shoot` | `direction` | Uses per-player damage/range from loadout |
| `pickup` | — | Pick up a passenger at your position |
| `dropoff` | — | Deliver passenger at destination |
| `grenade` | `direction` | Area damage (40 dmg, radius 2). Costs 1 grenade |
| `teleport` | — | Random position. Requires teleporter gear |
| `trap` | — | Place invisible mine at your position. Requires trap-mine gear |
| `decoy` | — | Place fake blip that lures enemies. Requires decoy gear |

### Bot Template (`brain.py`)

Your `brain.py` has a `think(state)` function that returns `(action, direction)`:

```python
def think(state):
    me = state["you"]
    if not me.get("alive?", True):
        return None, None  # dead, wait for respawn

    enemies = state["visible"]["enemies"]

    # Shoot at enemies on the same row/column
    if me["ammo"] > 0:
        for e in enemies:
            if e["x"] == me["x"]:
                return "shoot", "south" if e["y"] > me["y"] else "north"
            if e["y"] == me["y"]:
                return "shoot", "east" if e["x"] > me["x"] else "west"

    # Move toward nearest enemy
    if enemies:
        nearest = min(enemies, key=lambda e: abs(e["x"]-me["x"]) + abs(e["y"]-me["y"]))
        return "move", pick_direction(me["x"], me["y"], nearest["x"], nearest["y"])

    return "move", "north"
```

### Game State (`GET /game/state`)

Your bot receives this each tick:

```json
{
  "tick": 42,
  "you": {
    "id": "player-abc",
    "x": 5, "y": 3,
    "hp": 600, "alive?": true,
    "score": 200, "ammo": 8, "grenades": 3,
    "shield-hp": 50,
    "loadout": {"items": ["shotgun", "light-vest"], "cost": 25},
    "stats": {"max-hp": 600, "shoot-damage": 50, "shoot-range": 3, "speed": 1}
  },
  "visible": {
    "players": [{"id": "player-xyz", "x": 7, "y": 3, "has-passenger": false}],
    "enemies": [{"id": "enemy-1", "x": 6, "y": 4, "hp": 20, "type": "floopy"}],
    "passengers": [{"id": "pax-1", "x": 3, "y": 5, "dest": {"x": 10, "y": 8}}],
    "shots": []
  },
  "traps": [],
  "map": {"width": 21, "height": 19}
}
```

### Example Bots

- **`bots/starter_bot/`** — basic template, shoots at visible enemies, moves toward threats
- **`bots/berzerker/`** — aggressive close-range build (shotgun + speed boost + grenades + trap mines), demonstrates gear-specific actions

## Scoring

| Action | Points |
|--------|--------|
| Deliver a passenger | +100 |
| Kill a rival player | +150 |
| Kill an NPC enemy | +10 to +50 |

## Game Mechanics

- **Tick rate** — 250ms per tick (4 ticks/sec)
- **Fog of war** — bots only see within their visibility radius (default 5, or 8 with radar)
- **Battle royale** — arena walls close in over time, with fire warning before each shrink
- **Lightning** — random strikes blast holes in walls and kill anyone in the radius
- **NPC enemies** — waves of enemies spawn at map edges and chase players
- **Respawn** — dead players respawn after 10 ticks with full loadout stats restored

## Project Structure

```
bots/                Your bot code lives here
  starter_bot/       Template to copy from
    loadout.py       Gear configuration
    bot.py           Bot brain (think function)

lobby/               Python lobby server (gear room preview)
  serve.py           WebSocket server + file watcher + deploy manager
  schema.py          Gear catalog + validation

spectator/           Phaser 3 client (TypeScript + Vite)
  src/scenes/
    lobbyScene.ts    Gear room UI
    serverGame.ts    Live game spectator

battle-cab/          Clojure game server
  src/game/
    core.clj         Pure game engine with gear system
    engine.clj       Game loop lifecycle
    server.clj       REST API endpoints
    ws.clj           WebSocket broadcaster
```

## Makefile Commands

| Command | Description |
|---------|-------------|
| `make setup NAME=X` | One-time setup — creates venv + your bot |
| `make lobby BOT=bots/X` | Gear room preview with live reload |
| `make deploy BOT=bots/X` | Deploy bot to the live arena |
| `make status` | Check game server status |
| `make help` | Show all commands |

## For Game Admins

### Before the Event

1. Set the game server URL at the top of `Makefile` before distributing the repo:

```makefile
GAME_SERVER ?= https://your-game-server.com
```

2. Start the game server and spectator on your host machine:

```bash
# Terminal 1: Game server (Clojure, requires Java)
cd battle-cab
make server-dev      # Runs on port 33333

# Terminal 2: Spectator UI
cd spectator
npm install
npm run dev          # Runs on port 5173
# Live game view: http://localhost:5173/?server
```

3. When competitors deploy, their bots run locally on their machines and connect to your game server over HTTP.

### During the Event

```bash
make reset-game      # Reset arena between rounds
make status          # Check server status + player count
```

### How Deployment Works

Competitors' bots run on **their own machines** — not on the game server. The bot is a Python process that:
1. Calls `POST /game/join` with their name + loadout
2. Polls `GET /game/state` every 250ms for their fog-of-war view
3. Sends `POST /game/action` commands each tick

This means competitors only need Python and an internet connection. No server access required.

## License

MIT
