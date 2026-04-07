# VS-Mode: Vampire Survivors-Style Bot Arena

## Vision

30-minute team coding competition for Enterprise AI Summit game night.
4 teams of 2 write LLM bots that control characters on a grid via REST API.
Enemies swarm in waves. Bots must move AND manually aim/shoot at specific targets.
Audience watches on a single full-screen Phaser spectator view — no camera operator.

## Key Design Decision: Manual Targeting

Unlike Vampire Survivors (auto-fire), bots must **choose what to shoot**:

```
POST /game/action  {action: "shoot", direction: "east"}
```

The bot must:
1. **Perceive** — scan visible enemies from fog-of-war state
2. **Decide** — which enemy to target (closest? lowest HP? highest threat?)
3. **Aim** — compute the cardinal direction that lines up with the target
4. **Time** — shoot vs. move vs. dodge (can only do one action per tick)

This creates skill expression: a dumb bot moves randomly, a medium bot shoots nearest,
a good bot prioritizes targets, kites, and coordinates team focus fire.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Clojure Server (battle-cab)                    │
│  ┌───────────┐  ┌──────────┐  ┌──────────────┐ │
│  │ game.core │  │ REST API │  │  WebSocket   │ │
│  │ (pure fn) │  │ (bots)   │  │ (spectator)  │ │
│  └───────────┘  └──────────┘  └──────────────┘ │
│       │              ▲               │          │
│  tick loop      bot commands    state JSON      │
│       │              │               │          │
└───────┼──────────────┼───────────────┼──────────┘
        │              │               │
        │         ┌────┴────┐    ┌─────┴──────┐
        │         │ LLM Bot │    │  Phaser 3  │
        │         │ (teams) │    │ Spectator  │
        │         └─────────┘    └────────────┘
        │                              │
   500ms ticks              60fps interpolated rendering
                            rick-survival sprites
```

**Keep from battle-cab:** tick loop, REST API, fog of war, battle royale shrink, replay, auth
**Replace:** Datastar SSE HTML spectator → Phaser 3 WebSocket spectator
**Add:** enemies, waves, auto-aim-free weapons, XP/items, team scoring

## Sprite Assets (from rick-survival)

- **45 player sprite sheets** — directional walk anims (up/down/left/right × 4 frames)
  - Assign distinct Rick variants per team (PickleRick, CrowRick, CommanderRick, etc.)
- **24 enemy sprite sheets** — same directional anims, different types per wave
  - Wave 1: FloopyDoops (slow, weak)
  - Wave 3: Squanchy (medium)
  - Wave 5: ScaryTerry (fast)
  - Wave 8: Jaguar (boss-tier)
- **Bullets.png** — projectile atlas
- **5 blood splat animations** — enemy death effects
- **Items atlas** — ExpGem, GoldCoin, PistolHealth drops
- **Tilemaps** — level01.json, world_01.json (can use or make custom 20×20)

## Spectator View

- 20×20 grid at 64px/tile = 1280×1280px — fits on one screen
- Fixed camera, no scrolling, no camera operator needed
- Phaser interpolates grid positions between ticks for smooth movement
- Name labels + team color outlines on player sprites
- HP bars above players and enemies
- Kill feed overlay
- Team scoreboard overlay
- Wave counter ("WAVE 5 — ScaryTerry incoming!")

---

## MVP Plan

### MVP 1: Phaser Renders Battle-Cab State
**Proves:** rick-survival sprites work in our architecture
**Effort:** 2-3 hours

Deliverable: Static HTML page with Phaser 3

- Hardcoded JSON game state (one tick snapshot)
- 20×20 tilemap rendered from battle-cab map format
- 2 Rick player sprites at grid positions with directional walk animation
- 5 enemy sprites (FloopyDoops) at grid positions
- Uses rick-survival's actual PNG sprite sheets + TexturePacker JSON atlases
- No server, no networking

Files to create:
- `spectator/index.html` — Phaser bootstrap
- `spectator/src/scenes/LoadingScene.ts` — preload rick-survival assets
- `spectator/src/scenes/ArenaScene.ts` — render game state
- `spectator/src/config/` — sprite mappings, constants
- Copy needed sprite sheets from `rick-survival/public/assets/` into `spectator/public/`

Success criteria: Screenshot of Rick sprites on a tile grid with enemies. Looks like a game.

### MVP 2: Server Pushes State to Phaser via WebSocket
**Proves:** Clojure-to-Phaser pipeline works end-to-end
**Effort:** 2-3 hours
**Depends on:** MVP 1

Deliverable: Live-updating Phaser spectator connected to battle-cab server

Server changes:
- Add WebSocket endpoint to `game.server` (http-kit supports WebSocket natively)
- Broadcast full game state JSON each tick (alongside existing SSE)
- State format: `{tick, players: {id: {x,y,hp,alive,sprite,...}}, enemies: [...], map: {...}}`

Client changes:
- Phaser client connects to `ws://localhost:8080/spectate-ws`
- On each message: update sprite positions with tween interpolation
- Test with existing battle-cab bots (2 players moving on grid)

Success criteria: Two Rick sprites moving smoothly on the grid, positions driven by server ticks.

### MVP 3: Enemies Spawn and Swarm
**Proves:** the VS feel — hordes on screen
**Effort:** 2-3 hours
**Depends on:** MVP 2

Server changes to `game.core`:
- Add `:enemies` map to game state: `{enemy-id {:x :y :hp :type :speed}}`
- Enemy AI: each tick, move one cell toward nearest alive player
- Enemy collision: if enemy reaches player cell, deal damage
- Wave spawner in `game.engine`: escalating counts on timer
  - Tick 0: 5 FloopyDoops
  - Tick 20: 10 Shnyuks
  - Tick 50: 20 ScaryTerrys
  - Enemies spawn at random edge cells
- Enemy death: remove from state, emit `:enemy-killed` event
- XP gem drop at death position (item entity)

Phaser client changes:
- Render enemy sprites (different sprite sheet per type)
- Animate enemy walk direction based on movement vector
- Blood splat animation on enemy death
- XP gem sprite at drop location

Success criteria: Screen fills with 50+ enemies swarming toward player Ricks. VS vibes.

### MVP 4: Manual Targeting Works
**Proves:** the core competitive mechanic
**Effort:** 1-2 hours
**Depends on:** MVP 3

Server changes:
- Extend existing `apply-action :shoot` to check enemy hits (not just players)
- Line-of-sight trace: bullet travels in cardinal direction, first enemy in path takes damage
- Enemy HP system: FloopyDoops = 20hp, Squanchy = 50hp, ScaryTerry = 80hp
- Score: +10 per enemy kill, attributed to shooting player's team
- Ammo: same regen system as battle-cab (forces bots to be selective)

Bot API state addition:
- `visible.enemies` in fog-of-war response: `[{id, x, y, hp, type}]`
- Bot must compute direction to target: "enemy is 3 cells east of me → shoot east"

Phaser client changes:
- Render bullet tracer (Phaser tween from shooter to impact point)
- Floating damage number on hit
- Kill effect (blood splat + score popup)

Success criteria: Bot shoots east, kills one FloopyDoop. Three approaching from north untouched.
A bot that doesn't aim dies. A bot that aims well survives.

### MVP 5: Full Game Loop — Teams, Waves, 10-Minute Round
**Proves:** it's a real competition, ready for game night
**Effort:** 3-4 hours
**Depends on:** MVP 4

Game setup:
- 4 teams × 2 players = 8 Ricks
- Each team assigned distinct Rick sprites + team color
- Teams spawn in corners of 20×20 arena

Wave progression (10 minutes = 1200 ticks at 500ms):
- Ticks 0-120: FloopyDoops (learn the API phase)
- Ticks 120-360: Squanchy + Shnyuk (ramp up)
- Ticks 360-720: ScaryTerry + Hemorrhage (intense)
- Ticks 720-960: Mixed hordes + Jaguar bosses
- Ticks 960-1200: Battle royale shrink + final wave

Scoring:
- +10 per enemy kill
- +50 per XP gem collected (pickup action)
- +200 survival bonus per wave completed
- -100 on death (respawn after 10 ticks)
- Team score = sum of both players

Spectator UI (Phaser overlays):
- Team scoreboard (top-right)
- Wave indicator ("WAVE 5 — ScaryTerry!")
- Kill feed (bottom-left)
- Timer countdown
- Battle royale shrink warning ring

Server:
- Game lifecycle: lobby → countdown → waves → shrink → game over
- Replay log (existing system, works as-is)

Success criteria: Full 10-minute playthrough with simple bots. Escalating chaos.
Shrink forces final confrontation. It looks fun. It looks like VS. It's a competition.

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| Phaser can't load rick-survival sprite atlases | Blocks MVP 1 | Test atlas format first; worst case, slice PNGs manually |
| WebSocket from http-kit to Phaser has latency | Choppy visuals | Interpolation covers 500ms gaps; can reduce tick to 250ms |
| 50+ enemies per tick bloats state JSON | Slow broadcasts | Only send enemy diffs, or cap visible enemies in fog-of-war |
| Grid-based movement looks jerky even with interpolation | Doesn't feel like VS | Tween duration = tick interval; enemies move every 2nd tick for slower approach |
| 30 minutes isn't enough for teams to code manual aiming | Competition falls flat | Provide starter bot with basic "shoot nearest" logic; teams improve from there |
| Rick & Morty IP concerns for conference | Legal risk | Re-skin with generic sprites or AI-generated alternatives before event |

## Out of Scope (for now)

- AI Dungeon Master (battle-cab has this designed but not critical for MVP)
- AI Commentator with TTS
- Player evolution / weapon upgrades (VS feature, adds complexity)
- Continuous 2D physics (grid is simpler and good enough)
- Mobile spectator view
