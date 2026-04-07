# Cab Battle: LLM Bot Arena

A multiplayer bot-programming game where players write AI bots (LLM-powered or hand-coded) that control taxis on a grid maze via REST API. Pick up passengers, deliver them to destinations, and shoot rival cabs. Built for the Enterprise AI Summit game night.

## The Concept

Think *Crazy Taxi* meets *battle royale* meets *Battlecode*. Players don't control their cab directly — they write a program (ideally powered by an LLM) that reads game state through an API and sends back commands each tick. The audience watches the chaos unfold on a live spectator view.

### Core Loop

Every 500ms the server ticks. Each tick:

1. **Bots poll** `GET /game/state` — fog-of-war view showing nearby players, passengers, walls, and incoming bullet tracers
2. **Bots act** `POST /game/action` — move (north/south/east/west), pickup, dropoff, or shoot
3. **Server resolves** — pure-function state machine applies all commands, spawns passengers, regens ammo, checks respawns
4. **Spectators see** — Datastar SSE pushes live HTML fragments to the browser (god-mode view with all players visible)

### Scoring

- **+100 pts** — deliver a passenger to their destination
- **+50 pts** — eliminate a rival cab

### Combat & Hazards

- **Shooting** — line-of-sight bullets with ammo management and auto-regen
- **Battle royale shrink** — the arena walls close in over time, with a fire-warning ring before each shrink
- **Lightning strikes** — random environmental hazard that blasts craters in the map, killing anyone caught in the radius
- **Crater fires** — freshly-struck areas burn for several ticks

### What Makes It Fun for an Audience

- **AI Dungeon Master** — a Claude-powered agent that monitors game balance and can trigger lightning strikes, map swaps, or other interventions to keep things interesting
- **AI Commentator** — a Claude-powered color commentator that narrates the action (with ElevenLabs TTS)
- **Spectator view** — animated pixel-art sprites, laser tracers, explosion effects, live scoreboard
- **Fog of war** — bots only see what's near them, so the audience knows more than any single player
- **Deterministic replay** — every command and state transition is logged for post-game replay

## Bot API

```
POST /game/join         {name: "MyCab"}          -> {player-id, token}
GET  /game/state        Authorization: <token>    -> {tick, you, visible: {players, passengers, shots}, map}
POST /game/action       {action: "move", direction: "north"}
POST /game/action       {action: "pickup"}
POST /game/action       {action: "dropoff"}
POST /game/action       {action: "shoot", direction: "east"}
GET  /game/scoreboard                             -> {tick, scores}
GET  /game/map                                    -> {width, height, walls, ascii}
```

## Architecture

- **Clojure backend** — http-kit + reitit + Datastar SSE
- **Pure game engine** — `game.core` is a pure state machine: `(state, commands) -> new-state`
- **Tick-based loop** — mutable game loop in `game.engine`, pure logic in `game.core`
- **ASCII map parser** — maps defined as ASCII art (`#` = wall, `.` = open, `S` = spawn, `P` = passenger spawn)
- **Full replay** — every tick logged to JSONL for deterministic replay

## Running

```bash
make nrepl           # Start nREPL
make server-dev      # Dev server on port 8080
make runtests        # Watch tests
```
