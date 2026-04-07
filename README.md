# Cab Battle: LLM Bot Arena

A multiplayer bot-programming game where players write AI bots (LLM-powered or hand-coded) that control taxis on a grid maze via REST API. Pick up passengers, deliver them to destinations, and shoot rival cabs. Built for the **Enterprise AI Summit** game night.

## The Concept

Think *Crazy Taxi* meets *battle royale* meets *Battlecode*. Players don't control their cab directly — they write a program (ideally powered by an LLM) that reads game state through an API and sends back commands each tick. The audience watches the chaos unfold on a live Phaser spectator view.

### Core Loop

Every 250ms the server ticks. Each tick:

1. **Bots poll** `GET /game/state` — fog-of-war view showing nearby players, passengers, walls, and incoming bullet tracers
2. **Bots act** `POST /game/action` — move (north/south/east/west), pickup, dropoff, or shoot
3. **Server resolves** — pure-function state machine applies all commands, spawns passengers, regens ammo, checks respawns
4. **Spectators see** — Phaser renders animated sprites, bullet tracers, wall shrink, and live scoreboard via WebSocket

### Scoring

| Action | Points |
|---|---|
| Deliver a passenger to their destination | +100 |
| Eliminate a rival cab | +150 |
| Kill an NPC enemy | +10 to +50 |

### Combat & Hazards

- **Shooting** — line-of-sight bullets with ammo management and auto-regen
- **Battle royale shrink** — the arena walls close in over time (~5 min total game), with a pulsing warning ring before each shrink. Game ends when the arena reaches minimum size.
- **Lightning strikes** — random environmental hazard that blasts holes in walls, opening up new paths and killing anyone caught in the radius
- **NPC enemies** — Floopy Doops and Squanchies roam the map, chasing and damaging players

## Bot API

```
POST /game/join         {name: "MyCab"}                       -> {player-id, token}
GET  /game/state        Authorization: <token>                -> {tick, you, visible, map}
POST /game/action       {action: "move", direction: "north"}  -> {status: "queued"}
POST /game/action       {action: "shoot", direction: "east"}
POST /game/action       {action: "pickup"}
POST /game/action       {action: "dropoff"}
GET  /game/scoreboard                                         -> {tick, scores}
GET  /game/map                                                -> {width, height, walls}
GET  /game/status                                             -> {tick, players, running}
```

The `state` endpoint returns a fog-of-war view (manhattan distance 5) — bots only see what's near them, so the audience always knows more than any single player.

## Project Structure

```
battle-cab/          Clojure game server (the brain)
  src/game/
    core.clj         Pure game engine: (state, commands) -> new-state
    engine.clj       Mutable game loop, system lifecycle (atoms, timers)
    server.clj       REST API endpoints (Ring/reitit)
    ws.clj           WebSocket broadcaster (JSON to Phaser)
    bots.clj         Built-in bot AI strategies
    maps.clj         ASCII map parser + built-in maps
    replay.clj       Command logging + deterministic replay
    views.clj        Server stats page
  resources/
    config.edn       All game balance knobs (tick rate, damage, shrink timing)
  starter-bots/
    python/bot.py    Starter bot template

spectator/           Phaser 3 spectator client (TypeScript + Vite)
  src/scenes/
    serverGame.ts    WebSocket-connected live spectator view
```

## Architecture

- **Clojure backend** — http-kit async server + reitit router
- **Pure game engine** — `game.core` is a pure state machine with zero side effects
- **Tick-based loop** — `game.engine` owns the atoms and timer; `game.core` stays pure
- **Phaser spectator** — TypeScript/Vite app renders game state via WebSocket JSON
- **ASCII map parser** — maps defined as ASCII art (`#` = wall, `.` = open, `S` = spawn)
- **Full replay** — every tick logged to JSONL for deterministic replay and frame-by-frame debugging
- **Frame buffer** — last 2000 ticks saved in memory; step through with `?server&frame=N`

## Running

### Server

```bash
cd battle-cab
make nrepl           # Terminal 1: Start nREPL
make server-dev      # Terminal 2: Dev server on port 33333
make runtests        # Terminal 3: Watch tests
```

### Spectator

```bash
cd spectator
npm install
npm run dev          # Vite dev server on port 5173
```

Open `http://localhost:5173/?server` to watch the game.

### Add Bots

```bash
# Built-in bots (via API)
curl -X POST http://localhost:33333/game/add-bot -H 'Content-Type: application/json' -d '{"name":"Bot-1"}'

# Python starter bot
cd battle-cab
make bot
```

## Game Balance (config.edn)

All game parameters are tunable without code changes:

| Parameter | Default | Description |
|---|---|---|
| `tick-ms` | 250 | Milliseconds per tick (4 ticks/sec) |
| `max-players` | 8 | Maximum concurrent players |
| `visibility-radius` | 5 | Fog-of-war manhattan distance |
| `shoot-range` | 20 | How far bullets travel |
| `shoot-damage` | 30 | Damage per hit (players have 100 HP) |
| `kill-bonus` | 150 | Points for killing another player |
| `shrink-start` | 200 | Tick when walls start closing in |
| `shrink-interval` | 150 | Ticks between each wall ring |
| `lightning-chance` | 0.008 | ~0.8% chance per tick of wall-destroying strike |

## What Makes It Fun for an Audience

- **Fog of war** — bots only see what's near them, so the audience knows more than any single player
- **Battle royale** — the arena shrinks over ~5 minutes, forcing bots into tighter combat
- **Lightning** — random strikes blow holes in walls, creating new paths and chaos
- **Deterministic replay** — every command logged for post-game analysis
- **Frame-by-frame debugging** — step through any tick in the Phaser spectator

## License

MIT
