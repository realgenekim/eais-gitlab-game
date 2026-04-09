# Cab Battle Bot SDK

Build a bot that competes in the Cab Battle arena! Your bot drives a cab through a maze, picks up passengers, delivers them for points, and fights other players and NPC enemies.

**You don't need to know how to code.** Use Claude, ChatGPT, or any AI tool to write your strategy. See [PROMPTS.md](PROMPTS.md) for ready-to-paste prompts.

## Quick Start

### 1. Install Python

You need Python 3.8+ and the `requests` library:

```bash
pip install requests
```

### 2. Run your bot

```bash
python my_bot.py --name "YourTeamName"
```

### 3. Watch it play

Open the spectator view in your browser: **http://localhost:5173/?server**

### 4. Make it smarter

Edit the `decide()` function in `my_bot.py` — that's where your strategy lives. Or paste the file into an AI tool and ask it to improve your strategy!

## Game Rules

### The Arena
- Grid-based maze (20x19 tiles)
- **Fog of war**: you can only see 5 tiles around you
- **Battle royale**: walls close in over time (you die if caught!)
- **Lightning strikes**: random blasts destroy walls and kill nearby players

### Scoring
| Action | Points |
|--------|--------|
| Deliver a passenger to their destination | **+100** |
| Kill a rival player | **+150** |
| Kill an NPC enemy (floopy/squanchy/scary) | **+10 to +50** |

### Your Cab
- **500 HP** (you die at 0, respawn after 10 ticks)
- **Ammo**: starts at 5, regenerates 1 every 5 ticks (max 10)
- **Shooting**: line-of-sight, range 20 tiles, 30 damage per hit
- **Carry 1 passenger** at a time

### Actions (one per tick)
| Action | What it does |
|--------|-------------|
| `move` north/south/east/west | Move one tile |
| `shoot` north/south/east/west | Fire a bullet (costs 1 ammo) |
| `pickup` | Pick up a passenger at your tile |
| `dropoff` | Drop off passenger at their destination |

## Files

| File | What it is |
|------|-----------|
| `my_bot.py` | **Your bot — edit this!** The `decide()` function is your strategy. |
| `cab_battle.py` | SDK client — handles server communication. Don't edit. |
| `requirements.txt` | Python dependencies (just `requests`). |
| `PROMPTS.md` | AI prompt templates — paste into Claude/ChatGPT to improve your bot. |

## How to Vibecode Your Bot

1. Open `my_bot.py` in a text editor (or just copy-paste its contents)
2. Go to [claude.ai](https://claude.ai) or your favorite AI tool
3. Paste the contents of `my_bot.py` and ask the AI to improve the `decide()` function
4. Copy the AI's response back into `my_bot.py`
5. Run `python my_bot.py --name "YourTeam"` to test
6. Repeat!

See [PROMPTS.md](PROMPTS.md) for ready-to-use prompts.

## API Reference

Your `decide(state)` function receives the game state and returns an action.

### State Structure

```python
{
    "tick": 42,                    # Current game tick
    "you": {
        "x": 5, "y": 10,          # Your position on the grid
        "hp": 500,                 # Health points (0 = dead)
        "score": 200,              # Your score
        "ammo": 5,                 # Bullets remaining
        "alive?": True,            # Are you alive?
        "passenger": {             # Passenger you're carrying (or None)
            "dest": {"x": 18, "y": 3}
        }
    },
    "visible": {
        "players": [               # Other players you can see
            {"id": "player-xxx", "x": 7, "y": 10, "has-passenger": true}
        ],
        "enemies": [               # NPC enemies you can see
            {"id": "enemy-1", "x": 3, "y": 8, "hp": 20, "type": "floopy"}
        ],
        "passengers": [            # Available passengers to pick up
            {"id": "pax-1", "x": 2, "y": 15, "dest": {"x": 18, "y": 3}}
        ],
        "shots": [                 # Bullet tracers you can see
            {"shooter-id": "player-yyy", "direction": "east",
             "path": [[6,10], [7,10], [8,10]]}
        ]
    },
    "map": {"width": 20, "height": 19}
}
```

### Action Dict (return from decide)

```python
{"action": "move",    "direction": "north"}   # Move up
{"action": "move",    "direction": "south"}   # Move down
{"action": "move",    "direction": "east"}    # Move right
{"action": "move",    "direction": "west"}    # Move left
{"action": "shoot",   "direction": "north"}   # Shoot up
{"action": "pickup"}                          # Pick up passenger
{"action": "dropoff"}                         # Drop off passenger
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "Could not connect to server" | Make sure the game server is running |
| "Game is full" | Wait for a game restart, or ask the organizer |
| Bot does nothing | Check that `decide()` returns an action dict |
| Bot moves but doesn't pick up passengers | You need to be on the same tile as the passenger, then return `{"action": "pickup"}` |
| Bot gets stuck on walls | Add pathfinding! The basic `move_toward` helper doesn't avoid walls |
