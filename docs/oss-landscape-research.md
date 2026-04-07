# OSS Landscape: Bot-Programming Arenas & VS-Style Games

Research into existing open-source projects for a multiplayer bot-programming game
with Vampire Survivors-style visuals.

## The Design Space

Our target sits at a very specific intersection:
- HTTP bot API (bots are web clients, not compiled/sandboxed code)
- Real-time tick-based loop (not turn-based, not compile-and-submit)
- Rich combat (LOS shooting, ammo, enemies)
- Spectator streaming (live web view)
- Deterministic replay

This combination is **surprisingly rare** in OSS.

---

## Closest Matches: HTTP Bots + Real-Time Loop + Spectating

### BattleSnake
The closest canonical match to our architecture.

- **Architecture:** Each bot is a web server. Game engine calls your endpoint every tick.
- **Tick loop:** ~100-500ms per move
- **Gameplay:** Grid-based, multiplayer, hazards, shrinking zones (in some modes)
- **Spectating:** Strong live spectator UI with replays
- **Gap:** No pickup-and-deliver. No ammo/LOS shooting (hazards approximate combat).

Structurally almost exactly our system.

### Screeps
Different flavor, but architecturally relevant.

- Persistent world, not match-based
- Bots run continuously (JS runtime), not HTTP polling
- Real-time ticks (~1/sec)
- Strong spectator/debug tooling
- **Why it matters:** Shows how far "AI agents in a shared world" scales
- **Gap:** Not HTTP. Embeds execution instead.

---

## Close But Not HTTP / Not Real-Time Polling

### Halite
- Turn-based engine, bots read/write via stdin/stdout
- Strong replay system and spectator tooling
- Grid, navigation, resource collection
- **Gap:** Not HTTP. Not real-time ticking with live bot endpoints.

### Battlecode
- Rich combat + navigation on grid
- Deterministic simulation
- Replay + visualization
- **Gap:** Bots compiled and sandboxed, not networked services. No REST.

### CodeCombat
- Real-time-ish gameplay with scripting
- Spectator-friendly UI
- **Gap:** Not external bots via HTTP. Educational scripting, not competitive infra.

---

## Frameworks / Engines (Building Blocks)

### Pommerman
Grid-based multi-agent RL environment inspired by Bomberman.
Developed by Facebook AI Research (2018). MIT License.

- Grid world, bomb-placing, power-ups, navigation
- Supports team-based and free-for-all modes
- Partial observability, delayed rewards, opponent modeling
- Integrates with OpenAI Gym interfaces
- Hosted competitions at NeurIPS and ICLR
- **Gap:** Not HTTP-based bots. Primarily RL-focused, not web-integrated.
- **Relevance:** Multi-agent coordination research. Combat + navigation on grid.

### OpenAI Gym
- Tick-based simulation abstraction, deterministic stepping
- **Gap:** No multiplayer or HTTP orchestration. No spectator UI.

### Kaggle / CodinGame / AI Arena
- Often support multiplayer + replays
- Almost always compile-and-submit or sandboxed execution
- Not live HTTP bots

---

## The Structural Split

There are two categories in this ecosystem:

**1. Compile-and-Submit Worlds**
Battlecode, Halite, CodinGame.
Deterministic, fair, reproducible. Harder to integrate with LLM agents.

**2. Live HTTP Bot Worlds (our category)**
BattleSnake is the standout. Rare because:
- Introduces latency + fairness issues
- Harder to sandbox
- Harder to ensure determinism

Our game sits squarely in category 2, which is **still underexplored**.

---

## What's Missing (Our Opportunity)

No widely adopted OSS project combines ALL of:
- HTTP bot API (like BattleSnake)
- Rich combat (LOS shooting, ammo)
- Pickup/delivery objectives
- Battle royale mechanics
- Deterministic replay with full logs
- First-class spectator streaming

That combination is genuinely novel.

---

## Hard Tensions to Watch

### 1. Determinism vs HTTP
BattleSnake sidesteps with strict timeouts + simple state.
True determinism gets tricky with network jitter and non-idempotent bot logic.

### 2. Fairness
HTTP bots introduce latency asymmetry and compute differences.
Most systems avoid this by sandboxing instead.

### 3. Spectator Model
Best systems treat the game engine as:
- Authoritative state machine
- Append-only event log
- Stream events via SSE/WebSocket
Our Datastar/SSE background is an advantage here.

---

## VS-Style Visual Projects

For Vampire Survivors-like visuals that could be adapted to a REST-driven bot loop:

### Rick Survival (chosen)
- **Stack:** Phaser 3 + TypeScript + Electron
- **Why:** Browser-native rendering. 45 player sprites, 24 enemy types with directional
  walk animations. Blood effects, bullet sprites, item drops. Web build means
  wiring to REST/WebSocket spectator is natural.
- **Status:** Cloned to `./rick-survival/`. Using sprite assets for our Phaser spectator.

### Sentaur Survivors
- Unity C#. Nicest-looking OSS VS clone.
- Fast 2D shoot-em-up, multiple weapons, enemies, pickups.
- **Gap:** Unity makes REST retrofit harder. Repo soft-archived.

### Pied Piper / SurvivorsClone_Complete
- Godot-based VS-style projects
- MIT / CC0 licensed — permissive for heavy modification
- Lighter than Unity but more awkward than Phaser for web spectating

### Minimal Survivors
- Simple 2D action shooter prototype, public domain
- Good skeleton but much less visual polish

### Practical Ranking for Our Use Case

| Priority | Project | Why |
|----------|---------|-----|
| 1st | **Rick Survival** | Browser-native (Phaser), richest sprite library, easiest REST adaptation |
| 2nd | **Sentaur Survivors** | Best VS feel, but Unity = harder networking retrofit |
| 3rd | **Pied Piper** | Permissive license, good starter, but Godot less web-friendly |

**Decision:** Use Rick Survival's sprite assets with a new Phaser spectator client
connected to our Clojure server via WebSocket. Browser-native rendering aligns
with our architecture (bots over HTTP, spectators watching live web view).

---

## Composable Starting Points

If building from pieces:
- **Game loop + API model:** BattleSnake
- **Combat complexity:** Battlecode / Pommerman
- **Replay system:** Halite-style event logs
- **UI streaming:** SSE (our Datastar model)
- **Visual assets:** Rick Survival sprite sheets
