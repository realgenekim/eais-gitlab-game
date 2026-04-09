# EAIS Game Night — Event Guide

## The Concept

Six coders take the stage. They have 10 minutes to vibe code a battle bot with an AI agent. Then the arena goes live for 20 minutes — bots fight while the coders hot-reload strategy changes in real time. The audience watches the chaos unfold on a big screen. Highest score wins.

---

## Timeline

| Time | What Happens |
|------|-------------|
| 0:00 | Coders take stage, open laptops |
| 0:00 | MC explains the game (2 min) |
| 0:02 | Coders run `make setup NAME=their-name` |
| 0:02–0:10 | **BUILD PHASE** — coders vibe code their bot's loadout + brain |
| 0:10 | MC counts down, coders run `make deploy` |
| 0:10 | **ARENA GOES LIVE** — all bots enter the game simultaneously |
| 0:10–0:30 | **BATTLE PHASE** — bots fight, coders hot-reload improvements |
| ~0:20 | Shrink ring starts closing, forcing bots together |
| ~0:28 | Arena at minimum size, final chaos |
| 0:30 | Game ends, final scores displayed |
| 0:30 | Winner announced |

---

## What the Audience Sees

The spectator view (http://localhost:5173/?server) on the big screen shows:

- **All bots** as animated Rick characters moving around a grid arena
- **Bullet tracers** — orange lines showing who's shooting who
- **HP bars** above each bot — draining as they take damage
- **Enemy waves** — NPC monsters spawning at the edges and chasing bots
- **The shrink ring** — walls closing in with a pulsing fire warning
- **Lightning strikes** — random explosions blowing holes in the map
- **Scoreboard** — live scores updating as bots score kills and deliveries
- **Death and respawn** — bots die, disappear, then reappear at spawn points

The audience knows more than any single bot (fog of war) — they can see the whole arena while each bot only sees 5-8 tiles around itself.

---

## What the Coders Do

### Build Phase (Minutes 0–10)

Each coder gets a laptop with the repo cloned. They run:

```bash
make setup NAME=my-bot
```

This creates their bot at `bots/my-bot/` with three files:

| File | What It Does | Edit? |
|------|-------------|-------|
| `loadout.py` | Gear selection — weapons, armor, movement, utility | Yes |
| `brain.py` | The bot's strategy — the `think()` function | Yes |
| `bot.py` | Connection + hot-reload framework | No |

**They spend these 10 minutes:**
1. Choosing gear in `loadout.py` (2-3 min)
2. Writing their `think()` function in `brain.py` with their AI agent (7-8 min)

Optionally, they can preview their loadout in the gear room:
```bash
make lobby BOT=bots/my-bot
# Open http://localhost:5173/?lobby
```

### Deploy (Minute 10)

```bash
make deploy BOT=bots/my-bot
```

The bot joins the arena and starts fighting immediately.

### Battle Phase (Minutes 10–30)

**The bot keeps running. The coder keeps coding.**

Every time they save `brain.py`, the bot's strategy hot-reloads without disconnecting. Same score, same HP, same position — just smarter logic.

This is the core loop:
1. Watch your bot on screen — see it make a bad decision
2. Edit `brain.py` — fix the logic
3. Save — bot immediately uses the new strategy
4. Repeat

The audience sees bots evolving in real time. Early bots walk into walls and waste ammo. By minute 25, the best coders have bots that kite, grenade clusters, lay trap lines, and deliver passengers for bonus points.

---

## The Gear System

**100 budget points** to spend across 4 gear slots.

### Weapons (pick one)

| Item | Cost | Damage | Range | Notes |
|------|------|--------|-------|-------|
| Standard Blaster | 0 | 30 | 5 | Free default |
| Shotgun | 15 | 50 | 3 | High damage, point-blank |
| Sniper Rifle | 20 | 40 | 12 | Long range |
| Plasma Cannon | 25 | 60 | 5 | Highest damage, 2-tick cooldown |

### Armor (pick one)

| Item | Cost | HP | Notes |
|------|------|----|-------|
| None | 0 | 2000 | Default |
| Light Vest | 10 | 2500 | Solid upgrade |
| Heavy Armor | 25 | 3000 | Tank build, can't use speed boost |
| Energy Shield | 20 | 2000 + 200 shield | Shield absorbs damage first, restores on respawn |

### Movement (pick one)

| Item | Cost | Effect |
|------|------|--------|
| None | 0 | 1 tile per move |
| Speed Boost | 15 | 2 tiles per move |
| Teleporter | 30 | Random teleport action, 20-tick cooldown |

### Utility (pick multiple)

| Item | Cost | Effect |
|------|------|--------|
| Radar | 15 | See 8 tiles instead of 5 |
| Extra Ammo | 10 | Start with 10 ammo, max 15 |
| Grenades+ | 10 | Start with 5 grenades |
| Trap Mine | 15 | Place invisible mines (60 dmg) |
| Decoy | 20 | Place fake blip that lures enemies |

### Example Builds

**The Sniper** (55 pts): sniper-rifle + light-vest + radar + extra-ammo
- Long range, sees far, lots of ammo. Picks off enemies from a distance.

**The Tank** (60 pts): standard-blaster + heavy-armor + grenades-plus + extra-ammo
- 3000 HP, grenades for area damage, outlasts everyone.

**The Speedster** (65 pts): shotgun + energy-shield + speed-boost + trap-mine
- Rushes in at 2x speed, shotguns point-blank, drops mines while retreating.

**The Ghost** (90 pts): plasma-cannon + teleporter + decoy + radar
- Teleports around, places decoys, 60 dmg plasma cannon. Hard to pin down.

---

## Available Actions

The `think(state)` function returns `(action, direction)`:

| Action | Direction | What It Does |
|--------|-----------|-------------|
| `"move"` | `"north"` / `"south"` / `"east"` / `"west"` | Move 1 tile (2 with speed boost) |
| `"shoot"` | direction | Shoot a bullet — only hits targets on same row/column |
| `"pickup"` | `None` | Pick up a passenger at your position |
| `"dropoff"` | `None` | Deliver passenger at destination (+100 pts) |
| `"grenade"` | direction | Throw grenade — area damage, radius 2 (costs 1 grenade) |
| `"teleport"` | `None` | Random teleport (requires teleporter gear) |
| `"trap"` | `None` | Place invisible mine at your position (requires trap-mine) |
| `"decoy"` | `None` | Place fake blip that lures enemies (requires decoy) |

## Game State

Each tick, the bot receives:

```python
state["you"]             # Your bot: x, y, hp, ammo, grenades, score, alive?
state["visible"]["enemies"]   # Nearby NPCs: x, y, hp, type
state["visible"]["players"]   # Nearby rival bots: x, y
state["visible"]["passengers"] # Nearby passengers: x, y, dest
state["visible"]["shots"]     # Incoming bullets
state["map"]             # Arena dimensions: width, height
```

---

## Scoring

| Event | Points |
|-------|--------|
| Kill a rival player | +150 |
| Deliver a passenger | +100 |
| Kill NPC enemy (Floopy) | +10 |
| Kill NPC enemy (Squanchy) | +25 |
| Kill NPC enemy (Scary) | +50 |

Highest total score after 20 minutes of arena play wins.

---

## Game Mechanics

- **Tick rate**: 250ms (4 ticks per second)
- **Base HP**: 2000 (varies with armor)
- **Fog of war**: Bots only see 5 tiles around them (8 with radar)
- **Ammo**: Starts at 5, regenerates 1 every 5 ticks, max 10
- **Respawn**: Dead bots respawn after 10 ticks with full stats
- **Enemy waves**: NPC enemies spawn every 40 ticks, escalating difficulty
- **Shrink ring**: Walls close in starting at tick 200, every 150 ticks
- **Lightning**: Random strikes (~0.8% per tick) blast holes in walls

---

## Admin Setup (Before the Event)

### 1. Set the game server URL

Edit the `GAME_SERVER` variable at the top of `Makefile`:
```makefile
GAME_SERVER ?= http://your-server-ip:33333
```

### 2. Start the game server

```bash
cd battle-cab
export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"  # if needed
ENV=dev clojure -M:dev -m game.server
```

### 3. Start the spectator (for the big screen)

```bash
cd spectator
npm install
npm run dev
# Open http://localhost:5173/?server on the projector
```

### 4. Prepare competitor laptops

Each laptop needs:
- Python 3.10+
- Node.js 18+ (only if they want the lobby gear room UI)
- The repo cloned
- Internet connection to reach the game server

### 5. Game control during the event

```bash
make reset-game              # Reset between rounds
make status                  # Check player count and tick
```

### 6. Optional: Add house bots for more action

```bash
# Add AI bots with different loadouts to fill the arena
curl -X POST http://localhost:33333/game/add-bot \
  -H 'Content-Type: application/json' \
  -d '{"name":"House-Bot","loadout":{"weapon":"shotgun","armor":"light-vest"}}'
```

---

## MC Script (Suggested)

> "Welcome to the EAIS Bot Arena! Six coders are about to go head to head — but they won't be playing the game themselves. They're going to vibe code an AI bot that fights for them.
>
> Here's how it works: each coder has 10 minutes to build their bot. They pick their gear — weapons, armor, gadgets — and write the strategy that controls how their bot thinks. Then we drop all six bots into the arena and let them fight for 20 minutes.
>
> But here's the twist — they can keep coding while the game is running. Every time they save their code, their bot gets smarter instantly. So you'll see bots evolving in real time on screen.
>
> The arena gets more dangerous over time. Enemy waves spawn. The walls close in. Lightning strikes blow holes in the map. And the bots have to deal with all of it.
>
> Highest score after 20 minutes wins. Let's go!"

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `make setup` fails with pip error | Run `make lobby-install` first |
| Bot can't connect to server | Check `GAME_SERVER` in Makefile matches the actual server URL |
| Bot deploys but does nothing | Check `brain.py` has a `think()` function that returns `(action, direction)` |
| Hot-reload not working | Make sure you're editing `brain.py`, not `bot.py` |
| Lobby UI won't connect | Make sure lobby server is running (`make lobby BOT=bots/X`) |
| Game server won't start | Check Java is installed: `java -version` |
| Spectator blank | Make sure `npm install` was run in `spectator/` |
