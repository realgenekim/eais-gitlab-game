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
   - `bot.py` — runner (DON'T EDIT)
   - `brain.py` — your strategy (EDIT THIS)
   - `loadout.py` — gear selection
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
| `/game/join` | POST | Join with `{"name": "BotName"}` — returns `player-id`, `token`, gear catalog. **Safe to retry** — same name reconnects with existing token. |
| `/game/action` | POST | Send action `{"action": "move", "direction": "north"}` (needs auth token) |
| `/game/state` | GET | Fog-of-war view (needs auth token) |
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

Edit before running bot. Gear is **first-come-first-served** — if another player took it, pick something else.

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

1. **Dodge bullets** — check `state["visible"]["shots"]` for incoming fire, move perpendicular
2. **Stay near center** — the arena shrinks from the edges, don't get caught
3. **Shoot rivals** — line up on the same row or column, fire for +150 pts and to eliminate them
4. **Manage HP** — if below 200, flee and play defensive until you find an opening
5. **Use knockback** — hitting a rival pushes them toward the shrinking edge
6. **Do missions opportunistically** — +100 pts when safe, but survival comes first
7. **Pick gear wisely** — `titan-shield` for survival, `plasma-rounds` for aggression

## Workflow

1. Fetch `GET /game/brief?name=BotName` for current situation
2. Edit `brain.py` based on what's happening — the bot reloads live on save
3. The spectator screen shows when you update your strategy
4. Check brief again, iterate, survive!

## Important: Hot Reload

When you save `brain.py`, the bot runner automatically:
- Reloads your `think()` function without disconnecting
- Announces the update on the spectator screen
- Keeps your score, position, and HP
- If there's a syntax error, the previous working strategy stays active
