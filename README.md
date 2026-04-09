# Cab Battle: LLM Bot Arena

A multiplayer bot-programming game where players vibe code AI bots that battle in a grid arena. Built for the **Enterprise AI Summit** game night.

## How It Works

You write a Python bot that connects to a remote game server via REST API. Your bot reads game state each tick, decides what to do, and sends commands. The audience watches all bots battle it out on a live spectator view.

## Quick Start (Competitors)

```bash
# 1. Clone and set up your bot
git clone <repo-url>
cd eais-gitlab-game
make setup NAME=my-bot

# 2. Preview your bot in the gear room (edit loadout.py, see changes live)
#    Open http://localhost:5173/?lobby in your browser
make lobby BOT=bots/my-bot

# 3. Deploy to the live arena
make deploy BOT=bots/my-bot
```

That's it. Three commands.

### What `make setup` Does

- Creates a Python virtual environment with dependencies
- Copies the starter bot template to `bots/my-bot/`
- You get two files to edit:
  - **`loadout.py`** — pick your gear (weapons, armor, gadgets)
  - **`bot.py`** — code your bot's brain (the `think()` function)

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

### Bot Template (`bot.py`)

The starter bot has a `think(state)` function that returns `(action, direction)`:

```python
def think(state):
    me = state["you"]
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

Set the game server URL at the top of `Makefile` before distributing:

```makefile
GAME_SERVER ?= https://your-game-server.com
```

### Running the Game Server

```bash
cd battle-cab
make nrepl           # Terminal 1: nREPL
make server-dev      # Terminal 2: Dev server on port 33333
```

### Running the Spectator

```bash
cd spectator
npm install
npm run dev          # Vite dev server on port 5173
# Open http://localhost:5173/?server
```

## License

MIT
