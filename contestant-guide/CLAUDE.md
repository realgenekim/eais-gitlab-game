# Bot Battle Arena — AI Assistant Context

You are helping a contestant build a bot for a live battle arena at the Enterprise AI Summit.
The contestant edits `brain.py` — it hot-reloads when saved. No restart needed.

## Live Game Data

The game server has endpoints you should check to give better advice:

### Bot Status Brief (USE THIS)
```
GET /game/brief?name=BOT_NAME
```
Returns your bot's current situation: HP, score, rank, nearby enemies/rivals,
arena status, and actionable tips. **Poll this every few seconds to give
real-time advice.**

Example response:
```json
{
  "bot": "MyBot",
  "rank": 2,
  "rank-of": 4,
  "you": {"x": 10, "y": 9, "hp": 340, "score": 450, "ammo": 5},
  "nearby-enemies": [{"type": "floopy", "distance": 2, "direction": "south"}],
  "nearby-rivals": [{"name": "DESTROYER", "score": 600, "distance": 3, "direction": "east"}],
  "arena-shrinking": true,
  "tips": ["Rival nearby: DESTROYER — kill for +150 pts!", "You're rank 2 — leader has 600 pts"]
}
```

### Scoreboard
```
GET /game/scoreboard
```

### Game Status
```
GET /game/status
```

### Gear Catalog (for loadout.py)
```
GET /game/gear
```
Shows all gear with availability. Taken items show `"available": false`.

## What to Edit

- `brain.py` — the `think(state)` function. Returns `("action", "direction")`.
- `loadout.py` — gear selection. Edit before running the bot.
- `bot.py` — DO NOT EDIT. Handles connection and hot-reload.

## Actions Available

```python
("move", "north")    # move one tile (north/south/east/west)
("shoot", "east")    # fire shot (same row/column only, costs 1 ammo)
("pickup", None)     # grab mission passenger at your tile
("dropoff", None)    # deliver at destination (+100 pts)
```

## Strategy Tips Based on Game State

- **HP below 200**: Add flee logic — `away_from(mx, my, threat_x, threat_y)`
- **Ammo at 0**: Don't try to shoot, focus on evasion and missions
- **Near edge**: Arena shrinks — move toward center (10, 9)
- **Rival nearby**: Kill for +150 pts — line up on same row/column and shoot
- **No threats**: Do missions — pickup and deliver passengers for +100 pts each
- **Getting hit**: Knockback flings you — you might end up in a worse position

## Workflow

1. Check `/game/brief?name=BOT_NAME` for current situation
2. Suggest brain.py changes based on what's happening
3. User saves brain.py — bot reloads live
4. Check brief again to see if the changes helped
5. Iterate!
