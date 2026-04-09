"""
YOUR BOT'S LOADOUT — pick your gear before battle!

Gear is first-come-first-served. If someone else already took it,
pick something different.

AVAILABLE GEAR:
  "plasma-rounds"    — 2x shot damage (permanent)
  "titan-shield"     — 50% damage reduction (permanent)
  "oracle-eye"       — Double vision radius (permanent)
  "sprint-boots"     — Move twice per tick (temporary)
  "vampiric-rounds"  — Heal 15 HP per hit (permanent)
  "juggernaut"       — +300 bonus HP (instant)
  "ammo-belt"        — Double ammo regen (permanent)
  "cluster-shot"     — Shots hit 3-wide (temporary)

TIPS:
  - Pick gear that matches your strategy in brain.py
  - Aggressive? Try plasma-rounds + vampiric-rounds
  - Defensive? Try titan-shield + juggernaut
  - Sneaky? Try oracle-eye + sprint-boots
"""

LOADOUT = {
    # Your bot's name on the scoreboard
    "name": "CLAUDE-AGENT",

    # Pick 1-3 gear items from the list above
    "gear": [
        "plasma-rounds",
        "oracle-eye",
        "ammo-belt",
    ],
}
