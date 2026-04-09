# Bot Battle Arena: Contestant Guide

## Welcome to the Enterprise AI Summit Bot Battle!

You're about to build an AI-powered bot that fights in a live arena. You don't need to know how to code — you'll use an AI coding assistant (vibecoding!) to build your challenger for you.

**Your bot enters a battle arena.** Fight rival bots, eliminate NPC enemies, complete missions for points, and survive the shrinking arena. The audience watches on a giant screen while a live commentator calls the action.

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
mkdir bot-battle && cd bot-battle
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

## Step 2: Pick Your Bot Name

Think of a name for your challenger. This shows up on the scoreboard for the audience.

---

## Step 3: Enter the Arena

The game server IP will be shown on the big screen. Run:

```bash
python my_bot.py --name "YourBotName" --server http://SERVER_IP:33333
```

Replace `SERVER_IP` with the IP address on the screen (e.g., `192.168.1.42`).

You should see:

```
  Joined as 'YourBotName' (id: player-abc123)
  Confirmed: 'YourBotName' is in the lobby (2 players total)
  Registered! Waiting for game to start...
```

Your bot is now in the lobby. The audience can see your challenger on the big screen. Wait for the game organizer to start the match.

---

## Step 4: Make Your Bot Smarter (the fun part!)

The starter bot has a basic strategy. To win, you need to give your challenger an edge.

### Using Claude Code (recommended):

```bash
claude
```

Then say:

> Read my_bot.py and make the decide() function smarter. I want my bot to:
> - Shoot enemies when they're lined up on my row or column
> - Run away when my HP is below 200
> - Complete missions (deliver passengers) when it's safe
> - Stay near the center of the map (the edges shrink over time)

Claude Code will edit the file for you. Then restart your bot:

```bash
python my_bot.py --name "YourBotName" --server http://SERVER_IP:33333
```

### Using ChatGPT or Claude.ai:

1. Copy the entire contents of `my_bot.py`
2. Paste it into ChatGPT/Claude with a prompt like:

> Here's my bot for a battle arena game. Make the decide() function smarter.
> I want it to shoot enemies, dodge bullets, and complete missions.
> Keep using the helper functions already in the file.

3. Copy the response back into `my_bot.py`
4. Restart your bot

### Using Cursor / VS Code:

Open `my_bot.py`, highlight the `decide()` function, and use the AI assistant to improve it.

---

## Game Rules (Quick Reference)

### The Arena
- Grid-based battle arena, ~20x19 tiles
- **Fog of war**: your bot can only see 5 tiles around it
- **Battle royale**: walls close in over time — if they reach your bot, it's eliminated!
- **Lightning strikes**: random blasts that destroy walls and eliminate anyone nearby
- **Enemy waves**: NPC enemies swarm the arena in escalating waves

### Scoring

| Action | Points |
|--------|--------|
| Complete a mission (deliver passenger) | **+100** |
| Eliminate a rival bot | **+150** |
| Eliminate an NPC enemy | **+10 to +50** |

### Your Bot's Stats
- **500 HP** (eliminated at 0, respawns after ~3 seconds)
- **Ammo**: starts at 5, regenerates over time (max 10)
- **Shooting**: line-of-sight only (same row or column), range 20 tiles, 30 damage per hit
- **Knockback**: getting hit flings your bot away from the attacker

### Actions (one per tick, ~4 times per second)

| Action | What it does |
|--------|-------------|
| `move` north/south/east/west | Move one tile in a direction |
| `shoot` north/south/east/west | Fire a shot (costs 1 ammo) |
| `pickup` | Pick up a mission passenger at your tile |
| `dropoff` | Complete the mission at the destination |

---

## What Your Bot Sees

Every tick, your `decide(state)` function receives:

```python
state["you"]                     # Your position, HP, score, ammo
state["visible"]["players"]      # Rival bots you can see
state["visible"]["enemies"]      # NPC enemies nearby
state["visible"]["passengers"]   # Mission objectives to pick up
state["visible"]["shots"]        # Incoming fire you can see
state["map"]                     # Arena dimensions
```

Your bot can only see 5 tiles around it. The audience sees everything.

---

## Helper Functions (already in your bot)

| Function | What it does |
|----------|-------------|
| `move_toward(my_x, my_y, target_x, target_y)` | Move one step toward a target |
| `move_away(my_x, my_y, threat_x, threat_y)` | Move one step away from a threat |
| `distance(x1, y1, x2, y2)` | Manhattan distance between two points |
| `direction_to(my_x, my_y, target_x, target_y)` | Get cardinal direction to target |
| `can_shoot_at(my_x, my_y, target_x, target_y)` | Check if target is on same row/column (shootable) |

---

## Strategy Ideas to Tell Your AI

Pick a fighting style and tell your AI assistant:

### The Assassin
> "Make my bot aggressive. Shoot every enemy and rival I can see. Chase targets to line up shots. Only do missions if nothing else is around."

### The Strategist
> "Make my bot focus on missions for steady points. Complete objectives fast. Only fight in self-defense. Stay near the center where it's safer."

### The Survivor
> "Make my bot focus on staying alive. Flee from threats when HP is below 300. Stay near the center to avoid the shrinking walls. Only fight when cornered."

### The Balanced Fighter
> "Make my bot balanced. Shoot enemies when lined up, complete missions when safe, dodge incoming fire, and stay away from the edges."

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `pip install requests` fails | Try `pip3 install requests` or `python -m pip install requests` |
| "Could not connect to server" | Check the server IP on the big screen. Is your laptop on the same WiFi? |
| "Game is full" | Max 8 players. Wait for the organizer. |
| Bot does nothing | Make sure `decide()` returns an action dict |
| Bot keeps getting eliminated | Add logic to check HP and flee when low |
| Bot gets stuck on walls | The basic `move_toward` doesn't avoid walls. Ask your AI to add pathfinding! |
| Need to restart bot | Press `Ctrl+C` then run the command again |

---

## Timeline

1. **Setup** (5 min) — Get the SDK, install requests, pick a bot name
2. **Connect** (1 min) — Run your bot, see your challenger in the lobby
3. **Vibecode** (10 min) — Use your AI assistant to build a killer strategy
4. **Battle!** (~5 min) — Watch your bot fight on the big screen
5. **Iterate** (if time) — Tweak your strategy and run it back

---

## Tips

- **Start simple, iterate fast** — Get a working bot first, then improve
- **Watch the big screen** — See what your bot is doing and adjust your strategy
- **The fog of war is real** — Your bot can only see 5 tiles. The audience sees everything.
- **The arena shrinks** — Don't let your bot camp in the corners
- **Ammo regenerates** — Don't hoard it, shoot things!
- **Getting hit flings you** — Use knockback to your advantage (or avoid it)
- **Ask your AI to add `print()` statements** — See what your bot is thinking in your terminal

**May the best bot win!**
