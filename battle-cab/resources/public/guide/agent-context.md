# Bot Battle Arena — AI Agent Context

You are helping a contestant build a bot for a live battle arena at the Enterprise AI Summit.

**Game Server: `https://vibebattle.testwhatever.xyz`**

## Quick Start

1. Create 3 files: `bot.py`, `brain.py`, `loadout.py` (download from `https://vibebattle.testwhatever.xyz/guide/index.html`)
2. Run: `pip install requests`
3. Run: `python bot.py --name "BotName" --server https://vibebattle.testwhatever.xyz`
4. Edit `brain.py` — it hot-reloads on save. No restart needed.

## Server Endpoints

Base URL: `https://vibebattle.testwhatever.xyz`

| Endpoint | Method | What |
|----------|--------|------|
| `/game/brief?name=BotName` | GET | **USE THIS** — bot's live situation, tips, nearby threats |
| `/game/status` | GET | Game phase, tick, player count |
| `/game/scoreboard` | GET | All scores and rankings |
| `/game/gear` | GET | Gear catalog with availability |
| `/game/join` | POST | Join with `{"name": "BotName"}` |
| `/game/action` | POST | Send action (needs auth token from join) |
| `/game/state` | GET | Fog-of-war view (needs auth token) |
| `/game/gear/select` | POST | Equip gear `{"item": "titan-shield"}` (needs auth token) |
| `/guide/bot.py` | GET | Download bot runner |
| `/guide/brain.py` | GET | Download starter brain |
| `/guide/loadout.py` | GET | Download loadout template |

## Bot Brief Response (poll this for live intel)

```
GET /game/brief?name=YourBotName
```

```json
{
  "bot": "MyBot",
  "rank": 2, "rank-of": 4,
  "you": {"x": 10, "y": 9, "hp": 340, "score": 450, "ammo": 5, "gear": ["titan-shield"]},
  "nearby-enemies": [{"type": "floopy", "distance": 2, "direction": "south", "hp": 5}],
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
state["you"]["x"], state["you"]["y"]       # position
state["you"]["hp"]                          # health (500 max, 0 = dead)
state["you"]["ammo"]                        # bullets (regens over time)
state["you"]["score"]                       # current score
state["you"]["alive?"]                      # True/False
state["you"]["passenger"]                   # mission being carried, or None
state["you"]["passenger"]["dest"]["x"]      # mission destination
state["visible"]["enemies"]                 # [{x, y, hp, type}, ...]
state["visible"]["players"]                 # [{x, y, has-passenger}, ...]
state["visible"]["passengers"]              # [{x, y, dest: {x, y}}, ...]
state["visible"]["shots"]                   # [{direction, path: [[x,y]...]}, ...]
state["map"]["width"], state["map"]["height"]
```

### Scoring
- +100 — deliver a passenger (complete mission)
- +150 — eliminate a rival bot
- +5 to +30 — eliminate an NPC enemy

### Game mechanics
- Fog of war: can only see 5 tiles around you
- Arena shrinks over time (battle royale) — stay near center (10, 9)
- Getting hit causes knockback (flung away from attacker)
- Ammo regenerates over time (max 10)
- Shooting: line-of-sight only, same row OR column, range 20
- Respawn after ~3 seconds when eliminated

## File: loadout.py (GEAR SELECTION)

Edit before running bot. Gear is first-come-first-served.

```python
LOADOUT = {
    "name": "MyBot",
    "gear": ["titan-shield"],  # pick from gear catalog
}
```

Available gear (check /game/gear for live availability):
- `plasma-rounds` — 2x shot damage
- `titan-shield` — 50% damage reduction
- `oracle-eye` — double vision radius
- `sprint-boots` — move twice per tick (temporary)
- `vampiric-rounds` — heal 15 HP per hit
- `juggernaut` — +300 bonus HP
- `ammo-belt` — double ammo regen
- `cluster-shot` — shots hit 3-wide (temporary)

## Strategy Tips

- **HP below 200**: flee from threats
- **Ammo at 0**: evade until it regens
- **Near edge**: move toward center (10, 9) — arena shrinks
- **Rival nearby**: line up on same row/column and shoot for +150 pts
- **No threats visible**: do missions (pickup → deliver) for +100 pts
- **Getting hit**: knockback flings you — plan for it

## Workflow

1. Fetch `/game/brief?name=BotName` for current situation
2. Edit brain.py based on what's happening
3. Save — bot reloads live, no restart
4. Check brief again, iterate
