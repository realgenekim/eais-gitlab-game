# Bot Battle Arena - Tips & Tricks

Lessons learned from building and iterating on DEATHWALKER across dozens of rounds.

## The #1 Rule: Don't Die at the Edge

Edge death is the most common way bots die. The arena shrinks from the outside in, and knockback from enemy shots can fling you 2+ tiles toward the boundary.

- Keep a buffer of at least 5-6 tiles from any edge
- As the arena shrinks, your "safe zone" shrinks too — scale your edge danger dynamically
- **Knockback is the silent killer**: getting shot from the east pushes you west. If you're already west of center, you're flying into the kill zone
- Position yourself on the CENTER side of enemies so knockback pushes you inward, not outward

## Shooting is Harder Than You Think

The biggest surprise: enemies are almost always diagonal to you. The `can_shoot()` function requires exact row or column alignment, which rarely happens naturally.

### What We Learned

- **Exact alignment is rare** — our bot went 245+ ticks with zero kills using alignment-only shooting
- **Spray shots win fights** — shooting toward a close enemy (within 5 tiles) even when diagonal lands hits and triggers lifesteal
- **Zigzag chase forces alignment** — alternate moving on X and Y axes each tick instead of always beelining toward the enemy. This crosses their row/column much faster
- **Shoot while escaping** — even when fleeing the edge or dodging, check if you can fire first. Every tick matters

### Shot Priority

```
1. Perfect line-of-sight (same row/column) -> always shoot
2. Close range spray (within 5 tiles) -> shoot toward them
3. Strafe to align (move perpendicular to close the alignment gap)
4. Chase to close distance
```

## Gear Loadout Strategy

We ran **plasma-rounds + vampiric-rounds + titan-shield** and it was devastating:

| Gear | Why |
|------|-----|
| plasma-rounds | 2x damage = 100 per shot. Kills in 5-10 hits instead of 10-20 |
| vampiric-rounds | 15 HP heal per hit. Turns aggression into sustain |
| titan-shield | 50% damage reduction. You take 25 per hit instead of 50 |

This combo means: you deal 100 damage, heal 15 HP, and only take 25 per hit. You win every sustained fight.

**Other strong combos:**
- Defensive: titan-shield + juggernaut + ammo-belt (800 HP tank)
- Burst: plasma-rounds + cluster-shot + ammo-belt (area denial)
- Vision: oracle-eye + sprint-boots + plasma-rounds (map control)

## Brain Architecture

### Priority System

Your `think()` function runs 4 times per second. Use a strict priority hierarchy:

```
1. Dodge incoming bullets (check visible shots)
2. Escape edges (never let edge proximity go below danger threshold)
3. Flee when critically low HP
4. Shoot (aligned targets first, then spray at close range)
5. Chase / strafe to line up shots
6. Missions (only when no enemies visible)
7. Patrol near center
```

### Critical: Don't Let Survival Code Block Combat

Our biggest recurring bug: the edge-escape code would fire BEFORE combat code, so the bot would run toward center every tick without ever shooting — even with full ammo and enemies 1 tile away.

**Fix:** Always check for shooting opportunities inside your edge-escape block:

```python
if near_edge:
    # Shoot first if possible!
    if ammo > 0 and enemies_nearby:
        if can_shoot(me, enemy):
            return "shoot", direction
        if distance(me, enemy) <= 5:
            return "shoot", spray_direction
    # Then escape
    return "move", toward_center
```

### Dodge Logic Must Be Edge-Aware

Dodging a bullet is useless if it pushes you off the map. Always verify your dodge direction doesn't move you toward the edge:

```python
def dodge(shot):
    perpendicular_dir = get_perpendicular(shot.direction)
    if not moves_toward_edge(perpendicular_dir):
        return perpendicular_dir
    # Fall back to moving toward center
    return toward_center()
```

## Anti-Stuck Mechanism

Bots can get stuck oscillating between two positions. Track your last 3-4 positions and force a random move if they're all the same:

```python
if len(set(last_positions[-3:])) <= 1:
    return "move", random.choice(["north", "south", "east", "west"])
```

## Hot Reload is Your Superpower

The bot runner watches `brain.py` for changes and reloads it live. This means:

- You can fix bugs mid-round without disconnecting
- Your bot keeps its score, HP, and position
- Syntax errors are caught — the old brain stays active until you fix it
- Have your AI assistant watching the game and patching in real-time

### Mid-Round Hotfix Workflow

1. Monitor `/game/brief?name=YourBot` for problems (not shooting, stuck, dying at edges)
2. Edit `brain.py` with the fix
3. Save — auto-reloads within ~1 second
4. Watch the next few ticks to verify

We made 5+ mid-game hotfixes that directly led to kills we wouldn't have gotten otherwise.

## Monitoring Matters

Poll the server for real-time intelligence. Key endpoints:

| Endpoint | Frequency | Use |
|----------|-----------|-----|
| `/game/brief?name=Bot` | Every 2-3s | Position, HP, nearby rivals, tips |
| `/game/status` | Every 5s | Phase, round, tick, player count |
| `/game/scoreboard` | Between rounds | See all scores and who's alive |
| `/game/gear` | Before game | Check what gear is still available |

### What to Track

- **Ammo staying at 10** = you're not shooting. Fix your combat logic immediately
- **HP dropping fast with 0 score** = you're getting hit but not hitting back
- **Position stuck near edges** = your movement logic is broken
- **Same position for 3+ ticks** = you're stuck, anti-stuck isn't working

## Common Pitfalls

| Problem | Cause | Fix |
|---------|-------|-----|
| Never shoots | `can_shoot` requires exact alignment | Add spray shots for close enemies |
| Dies at edge | Knockback + small arena | Wider edge buffer, knockback-aware positioning |
| Stuck oscillating | Edge escape fights chase logic | Add anti-stuck detection |
| Crashes silently | Python error in `think()` | Hot-reload catches syntax errors, but runtime errors fail silently — add try/except logging |
| Full ammo, no kills | Edge escape runs before combat | Check for shots inside edge-escape block |
| Sorting crash | `list.sort()` with dicts | Don't put dicts in sort tuples — use manual min-finding |

## Round Strategy

- **Early game (T0-25):** Rush to center. Don't engage until you're safely positioned
- **Mid game (T25-100):** Hunt aggressively. Arena hasn't shrunk much yet
- **Late game (T100+):** Arena is small, fights are forced. Hold center, spray shots constantly
- **Between rounds:** Analyze what went wrong, hotfix brain.py, check for server updates

## The Winning Formula

1. Get to center fast
2. Stay near center always
3. Shoot at everything within 5 tiles
4. Don't let survival code prevent shooting
5. Monitor and hotfix in real-time
