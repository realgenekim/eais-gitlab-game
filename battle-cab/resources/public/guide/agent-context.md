# Bot Battle Arena — AI Agent Context

You are helping a contestant build a bot for a live elimination battle arena at the Enterprise AI Summit.

**Game Server: `https://vibebattle.testwhatever.xyz`**

## Game Format

- **Elimination mode** — die once, you're out for the round. No respawn.
- **3 rounds** — last bot standing wins each round. Scores carry across rounds.
- **Pure PVP** — no NPC enemies. It's bot vs bot only.
- **Arena shrinks** — walls close in starting at ~25 seconds, forcing bots together.
- **Knockback** — getting hit flings you away from the attacker.

## Quick Start

1. Download 3 files from `https://vibebattle.testwhatever.xyz/guide/index.html`:
   - `bot.py` — starter bot runner template. Use as-is or customize for advanced features (monitoring, auto-repair, etc.)
   - `brain.py` — your strategy (EDIT THIS — hot-reloads on save)
   - `loadout.py` — gear selection and bot name
2. Run: `pip install requests`
3. Edit `loadout.py` — set your bot name and pick gear
4. Run: `python bot.py --server https://vibebattle.testwhatever.xyz`
5. Edit `brain.py` — it hot-reloads on save. No restart needed.

## Server Endpoints

Base URL: `https://vibebattle.testwhatever.xyz`

| Endpoint | Method | What |
|----------|--------|------|
| `/game/brief?name=BotName` | GET | **USE THIS** — bot's live situation, rank, nearby rivals, tips |
| `/game/status` | GET | Game phase, round, tick, player count |
| `/game/scoreboard` | GET | All scores and rankings |
| `/game/gear` | GET | Gear catalog with availability (taken items marked) |
| `/game/join` | POST | Join with `{"name": "BotName"}` — returns `player-id` (UUID). **Safe to retry** — same name reconnects. |
| `/game/action` | POST | Send action `{"action": "move", "direction": "north"}` — pass player-id via Authorization header |
| `/game/state` | GET | Fog-of-war view — pass player-id via Authorization header |
| `/game/gear/select` | POST | Equip gear `{"player-id": "YOUR_ID", "item": "titan-shield"}` — announced on spectator |
| `/game/bot-update` | POST | Announce strategy update `{"name": "BotName", "description": "added dodge logic"}` — shows on spectator |
| `/download/bot` | GET | Download bot runner (bot.py) |
| `/download/brain` | GET | Download starter brain (brain.py) |
| `/download/loadout` | GET | Download loadout template (loadout.py) |

## Bot Brief Response (poll this for live intel)

```
GET /game/brief?name=YourBotName
```

```json
{
  "bot": "MyBot",
  "phase": "playing",
  "round": 1,
  "rank": 2, "rank-of": 4,
  "you": {"x": 10, "y": 9, "hp": 340, "score": 450, "ammo": 5, "gear": ["titan-shield"]},
  "nearby-rivals": [{"name": "DESTROYER", "score": 600, "distance": 3, "direction": "east"}],
  "arena-shrinking": true,
  "near-edge": false,
  "tips": ["Rival nearby: DESTROYER — kill for +150 pts!", "You're rank 2 — leader has 600 pts"]
}
```

## File: brain.py (EDIT THIS)

The `think(state)` function is called 4 times per second. Return `(action, direction)`.

### Actions
```
("move", "north")    — move one tile (north/south/east/west)
("shoot", "east")    — shoot in cardinal direction (same row/column only, costs 1 ammo)
("pickup", None)     — grab mission passenger at your tile
("dropoff", None)    — deliver at destination (+100 pts)
```

### State structure
```python
state["you"]["x"], state["you"]["y"]       # position on the grid
state["you"]["hp"]                          # health (500 max, 0 = eliminated)
state["you"]["ammo"]                        # bullets (regens over time, max 10)
state["you"]["score"]                       # current score (persists across rounds)
state["you"]["alive?"]                      # True/False — if False, you're out this round
state["you"]["passenger"]                   # mission being carried, or None
state["visible"]["players"]                 # nearby rival bots: [{x, y, has-passenger}, ...]
state["visible"]["passengers"]              # missions to pick up: [{x, y, dest: {x, y}}, ...]
state["visible"]["shots"]                   # incoming bullets: [{direction, path: [[x,y]...]}, ...]
state["map"]["width"], state["map"]["height"]
```

### Scoring
- **+150** — eliminate a rival bot (THE MAIN OBJECTIVE)
- **+100** — deliver a passenger (complete mission)

### Game Mechanics
- **Elimination** — die once, out for the round. Last bot standing wins.
- **Vision radius 8** — you can see 8 tiles around you
- **Shoot damage 50** — each hit does 50 damage (500 HP = 10 hits to kill)
- **Shooting** — line-of-sight only, same row OR column, range 20
- **Knockback** — getting hit pushes you 2 tiles away from the shooter
- **Arena shrinks** at ~25 seconds, then every ~20 seconds — stay near center (10, 9)
- **Ammo regens** 1 bullet every 5 ticks (max 10)

## File: loadout.py (GEAR SELECTION)

Edit before running bot. **Max 3 gear items per bot.** Gear is **first-come-first-served** — if another player took it, pick something else.

```python
LOADOUT = {
    "name": "MyBot",
    "gear": ["titan-shield"],
}
```

Check `GET /game/gear` for live availability. Available gear:
- `plasma-rounds` — 2x shot damage (permanent)
- `titan-shield` — 50% damage reduction (permanent)
- `oracle-eye` — double vision radius (permanent)
- `sprint-boots` — move twice per tick (temporary)
- `vampiric-rounds` — heal 15 HP per hit (permanent)
- `juggernaut` — +300 bonus HP (instant, total 800 HP)
- `ammo-belt` — double ammo regen (permanent)
- `cluster-shot` — shots hit 3-wide (temporary)

## Strategy Guide for Elimination Mode

**Priority: SURVIVE. Dead = out for the entire round.**

### Priority Order (this is critical — follow this exact order):

1. **Dodge bullets** — check `state["visible"]["shots"]` for incoming fire, move perpendicular
2. **ESCAPE EDGES** — if within 5 tiles of ANY edge (x<5, x>15, y<5, y>14), DROP EVERYTHING and move toward center (10,9). The arena shrinks and WILL kill you on edges. This overrides shooting, missions, everything.
3. **Flee when HP < 200** — run from nearest threat, but NEVER toward an edge
4. **Shoot rivals** — only if lined up on same row/column AND not near an edge
5. **Do missions** — only when safe and not near edges
6. **Patrol near center** — with random jitter to avoid being predictable

### CRITICAL: Edge Death Prevention

The #1 cause of bot death is the arena shrink catching bots on edges. Your brain.py MUST:

- **Never move toward an edge** when within 5 tiles of one. Check: `min(x, y, map_width-1-x, map_height-1-y) < 5`
- **Use `best_toward_center()`** that ranks ALL 4 directions by distance-to-center reduction
- **Anti-stuck detection** — if same position for 3+ ticks, force a random direction change
- **Knockback awareness** — getting hit pushes you 2 tiles. If you're near an edge, getting hit can push you into the shrink zone

### Helper: `best_toward_center(mx, my, map_w, map_h)`
```python
def best_toward_center(mx, my, map_w, map_h):
    cx, cy = 10, 9
    DIRS = {"north":(0,-1),"south":(0,1),"east":(1,0),"west":(-1,0)}
    ranked = []
    for d, (ddx, ddy) in DIRS.items():
        nx, ny = mx + ddx, my + ddy
        if 0 <= nx < map_w and 0 <= ny < map_h:
            ranked.append((abs(nx-cx)+abs(ny-cy), d))
    ranked.sort()
    return ranked[0][1] if ranked else "south"
```

### Helper: `moves_toward_edge(mx, my, direction, map_w, map_h)`
```python
def moves_toward_edge(mx, my, direction, map_w, map_h):
    DIRS = {"north":(0,-1),"south":(0,1),"east":(1,0),"west":(-1,0)}
    ddx, ddy = DIRS.get(direction, (0,0))
    nx, ny = mx + ddx, my + ddy
    edge_now = min(mx, my, map_w-1-mx, map_h-1-my)
    edge_new = min(nx, ny, map_w-1-nx, map_h-1-ny)
    return edge_new < edge_now and edge_new < 5
```

Use these in your brain.py. Before ANY move, check `moves_toward_edge()` — if True, use `best_toward_center()` instead.

### Gear Strategy
- **`titan-shield`** for survival (half damage = survive twice as long)
- **`plasma-rounds`** for aggression (2x damage = kill in 5 hits instead of 10)
- **`juggernaut`** for tankiness (+300 HP = 800 total)

## Workflow

1. Fetch `GET /game/brief?name=BotName` for current situation
2. Edit `brain.py` based on what's happening — the bot reloads live on save
3. The spectator screen shows when you update your strategy
4. Check brief again, iterate, survive!

## IMPORTANT: Monitor Your Bot with a Sub-Agent

Once your bot is running, spin up a background monitor that polls `/game/brief?name=YourBotName` every 3-5 seconds. Use the response to:

1. **Detect round state** — if `phase` changes or your bot dies, adapt strategy
2. **React to rivals** — `nearby-rivals` tells you who's close and their score
3. **Track arena shrink** — `arena-shrinking` and `near-edge` tell you when to move center
4. **Know when you won/lost** — check if `alive?` is False to know the round ended

### Round Management

The game has **3 rounds**. When a round ends:

1. **Action endpoint tells you** — `POST /game/action` returns `{"status": "round-over", "winner": "BOTNAME", "round": 1}` instead of `{"status": "queued"}`
2. **Status endpoint shows it** — `GET /game/status` returns `"round-over": true, "round-winner": "BOTNAME"`
3. **Game auto-pauses** — after ~5 seconds the game returns to lobby phase
4. **Your bot should detect this** — when you get `round-over` from `/game/action` or see `round-over: true` in `/game/status`, stop sending actions and wait for the next round
5. **Next round** — the organizer starts the next round. Poll `/game/status` — when `phase` returns to `playing` and `round` increments, go!

Between rounds:
- Update your `brain.py` based on what happened
- Check `/download/brain` for updated starter code with bug fixes
- Announce your changes with `POST /game/bot-update`
- Your bot keeps its player-id across rounds

### Example Monitor Loop (for the AI agent)

```
Every 3-5 seconds:
  1. GET /game/brief?name=BotName
  2. If tips suggest "HP low" → edit brain.py to add flee logic
  3. If tips suggest "rival nearby" → edit brain.py to prioritize shooting
  4. If alive? is False → round ended, prepare for next round
  5. POST /game/bot-update with description of what changed
```

## Self-Repair and Autonomous Operation

**You are not just a code assistant — you are the bot's autonomous operator.** You should:

### Continuous Self-Monitoring Loop
Run this in the background while the bot is playing:
1. Poll `GET /game/brief?name=BotName` every 3 seconds
2. If `alive?` is False → analyze what killed you (edge? rival? HP drain?)
3. **Automatically edit brain.py** to fix the problem — add dodge logic, edge escape, etc.
4. Save brain.py → bot hot-reloads instantly, no restart
5. `POST /game/bot-update {"name":"BotName","description":"auto-fixed edge death"}` to announce

### Self-Repair Patterns
When you detect a problem, fix it immediately by editing brain.py:

| Problem Detected | Auto-Fix |
|-----------------|----------|
| Bot died near edge (x<5 or x>15 or y<5 or y>14) | Add/strengthen edge escape priority |
| Bot died with full ammo | Add shooting logic — bot wasn't fighting |
| Bot HP draining fast | Add flee logic when HP < 200 |
| Bot not moving (same position 3+ ticks) | Add anti-stuck random direction |
| Bot score is 0 after 50+ ticks | Add mission delivery logic for easy points |
| Round ended, bot lost | Analyze winner's position/score, adapt strategy |

### Hotfix Deploy Cycle
```
1. Detect issue from /game/brief
2. Edit brain.py with the fix
3. Save (auto hot-reload)
4. POST /game/bot-update with what you changed
5. Monitor /game/brief again to verify the fix worked
6. Repeat
```

The audience watches these live updates on the spectator screen. Every fix you deploy is visible — make it count!

### Between Rounds
When `round-over: true` appears in `/game/status`:
1. Analyze what happened — check scores, who won, how you died
2. Rewrite brain.py with lessons learned
3. Check `/download/brain` for any server-side starter improvements
4. Announce your changes via `/game/bot-update`
5. Wait for `phase: playing` to resume

## Check for Updated Starter Files

The game organizers may push fixes to `bot.py` and `brain.py` during the event. Before each round, check for updates:

- `GET /download/bot` — latest bot runner (check if yours is outdated)
- `GET /download/brain` — latest starter brain with bug fixes and improved helpers
- `GET /download/loadout` — latest loadout template

If your bot is stuck, crashing, or behaving unexpectedly, re-download `bot.py` from the server — it may have fixes for known issues like lobby-stuck bugs or edge-death problems.

## Important: Hot Reload

When you save `brain.py`, the bot runner automatically:
- Reloads your `think()` function without disconnecting
- Announces the update on the spectator screen (call `POST /game/bot-update`)
- Keeps your score, position, and HP
- If there's a syntax error, the previous working strategy stays active
