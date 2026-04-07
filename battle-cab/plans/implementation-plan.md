# Cab Battle — Implementation Plan

## Vision
Multiplayer bot-programming game for EAIS Game Night. Players write bots (LLM or hand-coded)
that control taxis on a grid maze. AI Dungeon Master balances gameplay in real-time.
AI Commentator narrates over speakers via ElevenLabs. Spectator screen has Vampire Survivors energy.

## Three Layers
1. **Players build bots** — REST API, poll state, send actions
2. **AI Dungeon Master** — Claude watches game, drops powerups, reshapes map for balance
3. **AI Commentator** — Claude narrates events → ElevenLabs TTS → speakers

---

## Phase 1: PLAYABLE ✅ DONE
Server owns game state, REST API for bots to interact.

- [x] `game.core` — Pure game engine (move, pickup, dropoff, shoot, fog of war)
- [x] `game.maps` — ASCII map parser, 20x20 arena, 12x12 small map
- [x] `game.replay` — Command log + deterministic replay
- [x] `game.server` — REST API (join, state, action, scoreboard, map, ascii)
- [x] `starter-bots/python/bot.py` — Starter bot for players
- [x] Tests: 6 tests, 19 assertions

## Phase 2: WATCHABLE — Spectator Screen
Datastar SSE pushes live game view to browser. Vampire Survivors aesthetic.

### 2a. Extract game.engine (system lifecycle)
- [ ] Move mutable state (atoms, timers) from `game.server` into `game.engine`
- [ ] System map pattern: `start-system!` / `stop-system!`
- [ ] Each subsystem is a map with `:stop` fn
- [ ] Event log atom — decouples tick loop from DM and commentator

### 2b. Datastar SSE spectator view
- [ ] `game.sse` — SSE broadcaster using Datastar SDK (`dev.data-star.clojure/http-kit`)
  - Subscriber tracking (atom of SSE generators)
  - Push fragments every tick to all subscribers
  - Heartbeat keepalive
- [ ] `game.views` — Hiccup templates for spectator page
  - Full map (god mode, no fog of war) with player positions
  - Scoreboard (live-updating)
  - Event feed (kill feed, pickups, deliveries)
  - DM reasoning panel (Phase 3)
- [ ] Spectator HTML page with Datastar JS
  - `data-star-init="@get('/spectate')"` opens SSE connection
  - Fragments morph in place via idiomorph

### 2c. Vampire Survivors visual effects (CSS/JS)
- [ ] CSS animations on kill events (screen shake, flash)
- [ ] Floating damage/score numbers (+50 KILL, +100 DELIVERY)
- [ ] Combo counter for streaks
- [ ] Color-coded players on the map grid
- [ ] Responsive grid rendering (scales to projector)

## Phase 3: SPECTACULAR — AI Agents
AI Dungeon Master and Commentator make it unforgettable.

### 3a. Powerup system in game.core
- [ ] Powerup types: Turbo (2x speed), Shield, Magnet, X-Ray, Ammo Crate, Bounty
- [ ] Powerup pickup mechanics (walk over to collect)
- [ ] Timed effects (duration in ticks)
- [ ] DM mutation endpoint: `POST /game/dm/mutate` (god-mode token)
  - `add_walls`, `remove_walls` — reshape map
  - `spawn_powerup` — drop powerup at location
  - `spawn_passenger` — special high-value passenger
  - `announce` — broadcast message to spectator screen

### 3b. AI Dungeon Master (`game.dm`)
- [ ] Separate loop: reads full game state every 5 ticks
- [ ] Calls Claude API with game summary + available actions
- [ ] Prompt: "Keep game dramatic and competitive. Help lagging players."
- [ ] DM reasoning displayed on spectator screen
- [ ] Actions: powerups near struggling players, walls to slow leaders,
      high-value bounty passengers, corridor changes

### 3c. AI Commentator (`game.commentator`)
- [ ] Reads event log every 3-5 seconds
- [ ] Calls Claude: "You are an excited sports commentator. 1-2 sentences. Be dramatic."
- [ ] Pipes text to ElevenLabs streaming TTS API
- [ ] Audio output over speakers (~200ms latency)
- [ ] Commentary displayed on spectator screen as subtitles

## Phase 4: POLISH (if time permits)
- [ ] Multiple game modes: delivery, last-cab-standing, battle royale (shrinking arena)
- [ ] Player registration web page (join via browser, get token)
- [ ] Replay viewer (step through saved games)
- [ ] Sound effects (kills, pickups, powerups)
- [ ] Tournament bracket for multiple rounds

---

## Architecture

```
┌──────────────┐
│  Game Server  │ ← Clojure, http-kit + reitit + Datastar SSE
│  (authority)  │
│  Port 33333   │
└──────┬───────┘
       │ REST API
       ├─────────── Player bots (4-8 clients, polling every 500ms)
       │
       ├─────────── Spectator browsers (SSE, Datastar fragment push)
       │
       ├─────────── DM Agent (1 client, god-mode, Claude API)
       │
       └─────────── Commentator Agent (1 client, read-only, Claude → ElevenLabs)
```

## Namespace Plan

```
game.core         — Pure game engine (DONE)
game.maps         — ASCII map parser (DONE)
game.replay       — Command log + replay (DONE)
game.engine       — System lifecycle, atoms, tick loop, event log
game.server       — REST API endpoints (DONE, needs refactor)
game.sse          — Datastar SSE broadcaster
game.views        — Hiccup spectator page + components
game.ds           — Datastar expression helpers (DONE, from template)
game.dm           — AI Dungeon Master agent
game.commentator  — AI color commentator
```

## Key Decisions
- **No Stuart Sierra component** — lightweight system map with `:stop` fns
- **Event log as decoupling layer** — tick loop appends events, DM and commentator consume
- **Datastar SSE** (not WebSocket) — matches existing patterns from social-media-writer
- **Port 33333** — avoids conflicts
- **500ms ticks** — generous for LLM agents, smooth for spectators
