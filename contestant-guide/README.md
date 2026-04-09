# Bot Battle Arena: Contestant Guide

You're building an AI bot that fights in a live arena. Use an AI coding assistant to vibecode your strategy — no coding experience needed.

---

## Setup (3 minutes)

### 1. Get the files

You need these 3 files in a folder:

```
my-bot/
  bot.py       — the runner (DON'T EDIT)
  brain.py     — your strategy (EDIT THIS!)
  loadout.py   — your gear selection
```

Download from the shared link on the screen, or copy from the organizer.

### 2. Install Python dependency

```bash
pip install requests
```

---

## Connect to the Arena

The server IP is on the big screen. Run:

```bash
python bot.py --name "YourBotName" --server http://SERVER_IP:33333
```

Your bot joins the lobby. The audience sees you on the big screen. Wait for the organizer to start the match.

---

## Vibecode Your Strategy

**This is the game.** Open `brain.py` in your AI tool and make it smarter.

### With Claude Code (recommended):

```bash
claude
```

Then say:

> Read brain.py. Make my think() function smarter. I want to:
> - Shoot enemies and rivals when lined up on my row or column
> - Run away when my HP is below 200
> - Complete missions (deliver passengers) when it's safe
> - Stay near the center (the edges shrink over time)

Claude edits the file. **Your bot reloads automatically — no restart needed.**

### With ChatGPT / Claude.ai:

1. Copy `brain.py` contents
2. Paste with your strategy request
3. Copy the response back into `brain.py`
4. Save — your bot reloads live!

### With Cursor / VS Code:

Open `brain.py`, highlight `think()`, use the AI assistant.

---

## Pick Your Gear

Edit `loadout.py` to choose gear **before** you run your bot:

| Gear | Effect |
|------|--------|
| `plasma-rounds` | 2x shot damage |
| `titan-shield` | 50% damage reduction |
| `oracle-eye` | Double vision radius |
| `sprint-boots` | Move twice per tick (temporary) |
| `vampiric-rounds` | Heal 15 HP per hit |
| `juggernaut` | +300 bonus HP |
| `ammo-belt` | Double ammo regen |
| `cluster-shot` | Shots hit 3-wide (temporary) |

Gear is **first come, first served** — if someone took it, pick something else.

---

## Game Rules

- **500 HP** — die at 0, respawn in ~3 seconds
- **Ammo** regenerates over time (max 10)
- **Shooting** is line-of-sight: same row or column only
- **Getting hit** flings you away (knockback)
- **Arena shrinks** over time (battle royale)
- **Enemy waves** spawn throughout the match

### Scoring

| Action | Points |
|--------|--------|
| Complete a mission (deliver passenger) | **+100** |
| Eliminate a rival bot | **+150** |
| Eliminate an NPC enemy | **+10 to +50** |

---

## The think() Function

Your `brain.py` has one function. It's called 4 times per second:

```python
def think(state):
    # state["you"]                  — your position, HP, ammo, score
    # state["visible"]["enemies"]   — nearby NPC enemies
    # state["visible"]["players"]   — nearby rival bots
    # state["visible"]["passengers"]— missions to pick up
    # state["visible"]["shots"]     — incoming bullets

    return ("move", "north")   # action, direction
```

Return one of:
- `("move", "north/south/east/west")`
- `("shoot", "north/south/east/west")`
- `("pickup", None)`
- `("dropoff", None)`

---

## Strategy Prompts

Copy-paste to your AI tool:

**Assassin:** "Make my bot shoot everything. Chase targets to line up shots. Only do missions if nothing else is around."

**Strategist:** "Focus on missions for steady +100 points. Only fight in self-defense. Stay near center."

**Survivor:** "Stay alive above all else. Run from threats below 300 HP. Hug the center."

**Balanced:** "Shoot when lined up, do missions when safe, dodge bullets, avoid edges."

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "Could not connect" | Check server IP on screen. Same WiFi? |
| "Name already taken" | Pick a different name |
| Bot does nothing | brain.py needs a `think()` function that returns `(action, direction)` |
| Syntax error in brain.py | Fix and save — previous strategy stays active |
| Bot gets stuck | Ask your AI to add wall avoidance |

---

## Key Insight

**Save brain.py to hot-reload.** You don't restart your bot. Edit strategy, save, watch it change on the big screen. Iterate fast. That's how you win.
