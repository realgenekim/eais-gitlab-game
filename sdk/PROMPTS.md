# AI Prompt Templates for Cab Battle

Copy-paste any of these prompts into Claude, ChatGPT, or your favorite AI tool. They include all the game context the AI needs to write a good strategy.

## How to Use

1. Copy a prompt below
2. Paste it into your AI tool
3. Also paste the contents of `my_bot.py` so the AI can see the code
4. Copy the AI's response back into `my_bot.py`
5. Run `python my_bot.py --name "YourTeam"` to test

---

## Prompt 1: Taxi Driver (Score through deliveries)

```
I'm playing a game called Cab Battle. I control a cab on a grid maze through code.
The game ticks 4 times per second and I submit one action per tick.

I need you to rewrite the decide() function in my bot to be a great taxi driver:
- Pick up passengers and deliver them to their destinations (+100 points each)
- Pick the closest passenger first
- If I'm carrying a passenger, head straight to the destination
- Avoid enemies if possible (move away if one is within 2 tiles)
- If stuck (can't reach passenger), try a different direction

I can see 5 tiles around me (fog of war). My actions are:
- move north/south/east/west
- pickup (when on same tile as passenger)
- dropoff (when on same tile as destination)
- shoot north/south/east/west (costs 1 ammo)

Here is my current bot code:
[paste my_bot.py here]
```

## Prompt 2: Aggressive Hunter (Score through kills)

```
I'm playing a game called Cab Battle. I control a cab on a grid maze through code.
The game ticks 4 times per second and I submit one action per tick.

I need you to rewrite the decide() function to be an aggressive hunter:
- Kill other players (+150 points) and NPC enemies (+10-50 points)
- Shooting is line-of-sight: I can only hit targets on my exact row or column
- To shoot, I need to be on the same row (east/west) or column (north/south) as the target
- If a target is lined up, shoot it
- If no target is lined up, move to align with the nearest target
- Dodge bullets: if I see a shot path that includes my tile, move perpendicular
- My ammo regenerates (1 every 5 ticks, max 10), so don't hoard it

Each shot does 30 damage. Players have 500 HP. Enemy types:
- floopy: 20 HP, worth 10 points
- squanchy: 50 HP, worth 25 points
- scary: 80 HP, worth 50 points

Here is my current bot code:
[paste my_bot.py here]
```

## Prompt 3: Balanced (Shoot + Deliver)

```
I'm playing a game called Cab Battle. I control a cab on a grid maze.

I need a balanced strategy that maximizes total score:
- If an enemy or player is lined up on my row/column, shoot them (easy points)
- Otherwise, focus on passenger delivery (+100 per delivery)
- Dodge incoming bullets when I see shot paths crossing my tile
- Stay near the center of the map (the edges shrink over time — battle royale)
- Prefer killing enemies since they're easier targets than players

I can see 5 tiles around me. The can_shoot_at() helper function tells me if
a target is on my row or column. The move_toward() and move_away() helpers
handle basic movement.

Here is my current bot code:
[paste my_bot.py here]
```

## Prompt 4: Survivor (Stay alive, play safe)

```
I'm playing Cab Battle, a grid maze game with battle royale shrinking walls.

I need a survival-focused strategy:
- Priority 1: Don't die! Stay away from enemies and other players
- Priority 2: Move toward the center (walls close in from edges)
- Priority 3: If it's safe, pick up and deliver passengers for points
- Priority 4: Only shoot if a target is perfectly lined up AND I have 3+ ammo
- If my HP is below 200, flee from everything and head to the center

I have 500 max HP, respawn takes 10 ticks. Deaths lose time, not points.
The arena shrinks over time — outer walls close in, killing anyone caught.

Here is my current bot code:
[paste my_bot.py here]
```

## Prompt 5: Improve My Existing Strategy

```
I'm playing Cab Battle, a grid maze game. Here is my current bot code.
Can you make it smarter? Some ideas:

- Add pathfinding so I don't get stuck on walls
- Prioritize targets by distance and type
- Track where I've been to avoid revisiting empty areas
- Anticipate where enemies are moving
- Stay away from the edges (battle royale shrink)
- Shoot opportunistically when aligned with any target

Keep using the same helper functions but add new ones if needed.
The decide() function is called 4 times per second.

Here is my current bot code:
[paste my_bot.py here]
```

## Prompt 6: Add A* Pathfinding

```
My Cab Battle bot gets stuck on walls because it uses greedy movement.
I need A* pathfinding added. Here's what I need:

1. Add a pathfind(state, start_x, start_y, goal_x, goal_y) function that:
   - Uses A* with manhattan distance heuristic
   - Avoids walls (state["map"]["walls"] is not available in fog-of-war view,
     but I can track walls I've seen)
   - Returns the direction for the first step, or None if no path found
   
2. Update the decide() function to use pathfind() instead of move_toward()

The map is a grid. Walls block movement. I can only see 5 tiles around me,
so I need to remember walls I've discovered.

Here is my current bot code:
[paste my_bot.py here]
```

---

## Tips for Prompting

- **Always paste your bot code** so the AI has full context
- **Be specific** about what "good" means: more kills? more deliveries? survive longer?
- **Iterate** — run the bot, watch the spectator view, then ask the AI to fix what went wrong
- **Ask the AI to add print() statements** so you can see what your bot is thinking
- **Ask about edge cases**: "What happens when there are no passengers visible?"
