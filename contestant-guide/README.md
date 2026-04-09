# Cab Battle: Contestant Guide

## Welcome to the Enterprise AI Summit Bot Battle!

You're about to build an AI-powered bot that fights in a live arena. You don't need to know how to code — you'll use an AI coding assistant to build your bot for you.

**Your bot drives a cab through a battle arena.** Pick up passengers, deliver them for points, and shoot rivals. The audience watches on a giant screen while a commentator calls the action.

---

## What You Need

- A laptop with Python installed
- An AI coding tool (pick one):
  - **Claude Code** (recommended) — `npm install -g @anthropic-ai/claude-code` then run `claude`
  - **ChatGPT / Claude.ai** — just use the web interface and copy-paste code
  - **Cursor / VS Code + Copilot** — any AI-assisted editor works

---

## Step 1: Get the SDK (2 minutes)

Open your terminal and run:

```bash
mkdir cab-battle && cd cab-battle
```

Create two files. Copy-paste these exactly:

### File 1: `cab_battle.py` (the SDK — don't edit this)

Download from the game organizer, or copy from the shared link provided on the screen.

### File 2: `my_bot.py` (your bot — edit this!)

Download from the game organizer, or copy from the shared link provided on the screen.

### Install the one dependency:

```bash
pip install requests
```

That's it. You're ready.

---

## Step 2: Pick Your Team Name

Think of a fun team name. This shows up on the scoreboard for the audience.

---

## Step 3: Connect to the Arena

The game server IP will be shown on the big screen. Run:

```bash
python my_bot.py --name "YourTeamName" --server http://SERVER_IP:33333
```

Replace `SERVER_IP` with the IP address on the screen (e.g., `192.168.1.42`).

You should see:

```
  Joined as 'YourTeamName' (id: player-abc123)
  Confirmed: 'YourTeamName' is in the lobby (2 players total)
  Registered! Waiting for game to start...
```

Your bot is now in the lobby. The audience can see you on the big screen. Wait for the game organizer to start the match.

---

## Step 4: Make Your Bot Smarter (the fun part!)

The starter bot has a basic strategy: pick up passengers and deliver them. To win, you need a better strategy.

### Using Claude Code (recommended):

```bash
claude
```

Then say:

> Read my_bot.py and make the decide() function smarter. I want my bot to:
> - Shoot enemies when they're lined up on my row or column
> - Run away when my HP is below 200
> - Deliver passengers when it's safe
> - Stay near the center of the map (the edges shrink over time)

Claude Code will edit the file for you. Then restart your bot:

```bash
python my_bot.py --name "YourTeamName" --server http://SERVER_IP:33333
```

### Using ChatGPT or Claude.ai:

1. Copy the entire contents of `my_bot.py`
2. Paste it into ChatGPT/Claude with a prompt like:

> Here's my bot for a game called Cab Battle. Make the decide() function smarter.
> I want it to shoot enemies, dodge bullets, and deliver passengers.
> Keep using the helper functions already in the file.

3. Copy the response back into `my_bot.py`
4. Restart your bot

### Using Cursor / VS Code:

Open `my_bot.py`, highlight the `decide()` function, and use the AI assistant to improve it.

---

## Game Rules (Quick Reference)

### The Arena
- Grid-based maze, ~20x19 tiles
- **Fog of war**: you can only see 5 tiles around you
- **Battle royale**: walls close in over time — if they reach you, you die!
- **Lightning strikes**: random blasts that destroy walls and kill nearby players

### Scoring

| Action | Points |
|--------|--------|
| Deliver a passenger | **+100** |
| Kill a rival player | **+150** |
| Kill an NPC enemy | **+10 to +50** |

### Your Bot Stats
- **500 HP** (die at 0, respawn after ~3 seconds)
- **Ammo**: starts at 5, regenerates over time (max 10)
- **Shooting**: line-of-sight only (same row or column), range 20 tiles, 30 damage
- **Carry 1 passenger** at a time

### Actions (one per tick, ~4 times per second)

| Action | How |
|--------|-----|
| Move | `{"action": "move", "direction": "north"}` (north/south/east/west) |
| Shoot | `{"action": "shoot", "direction": "east"}` (costs 1 ammo) |
| Pick up passenger | `{"action": "pickup"}` (must be on same tile) |
| Drop off passenger | `{"action": "dropoff"}` (must be at destination) |

---

## What Your Bot Sees

Every tick, your `decide(state)` function receives:

```python
state["you"]             # Your position, HP, score, ammo, passenger
state["visible"]["players"]    # Other players you can see
state["visible"]["enemies"]    # NPC enemies nearby
state["visible"]["passengers"] # Passengers to pick up
state["visible"]["shots"]      # Bullet tracers
state["map"]             # Map dimensions
```

You can only see 5 tiles around you. The audience sees everything.

---

## Helper Functions (already in your bot)

| Function | What it does |
|----------|-------------|
| `move_toward(my_x, my_y, target_x, target_y)` | Move one step toward a target |
| `move_away(my_x, my_y, threat_x, threat_y)` | Move one step away from a threat |
| `distance(x1, y1, x2, y2)` | Manhattan distance between two points |
| `direction_to(my_x, my_y, target_x, target_y)` | Get cardinal direction to target |
| `can_shoot_at(my_x, my_y, target_x, target_y)` | Check if target is on same row/column |

---

## Strategy Ideas to Tell Your AI

Pick a style and tell your AI assistant:

### The Assassin
> "Make my bot aggressive. Shoot every enemy and player I can see. Chase targets to line up shots. Only pick up passengers if nothing else is around."

### The Taxi Driver
> "Make my bot focus on passenger delivery. Pick up the closest passenger, deliver them fast. Only fight in self-defense. Stay near the center."

### The Survivor
> "Make my bot focus on staying alive. Run from enemies when HP is below 300. Stay near the center to avoid the shrinking walls. Only fight when cornered."

### The Balanced Pro
> "Make my bot balanced. Shoot enemies when lined up, deliver passengers when safe, dodge incoming bullets, and stay away from the edges."

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `pip install requests` fails | Try `pip3 install requests` or `python -m pip install requests` |
| "Could not connect to server" | Check the server IP on the big screen. Is your laptop on the same WiFi? |
| "Game is full" | Max 8 players. Wait for the organizer. |
| Bot does nothing | Make sure `decide()` returns an action dict |
| Bot keeps dying | Add logic to check HP and run away when low |
| Bot gets stuck on walls | The basic `move_toward` doesn't avoid walls. Ask your AI to add pathfinding! |
| Need to restart bot | Press `Ctrl+C` then run the command again |

---

## Timeline

1. **Setup** (5 min) — Get the SDK, install requests, pick a team name
2. **Connect** (1 min) — Run your bot, see yourself in the lobby
3. **Vibecode** (10 min) — Use your AI assistant to build a strategy
4. **Battle!** (~5 min) — Watch your bot fight on the big screen
5. **Iterate** (if time) — Tweak your strategy and battle again

---

## Tips

- **Start simple, iterate fast** — Get a working bot first, then improve
- **Watch the spectator screen** — See what your bot is doing and adjust
- **The fog of war is real** — You can only see 5 tiles around you. The audience sees everything.
- **The arena shrinks** — Don't camp in the corners
- **Ammo regenerates** — Don't hoard it, shoot things!
- **Ask your AI to add `print()` statements** — See what your bot is thinking in your terminal

**Have fun and may the best bot win!**
