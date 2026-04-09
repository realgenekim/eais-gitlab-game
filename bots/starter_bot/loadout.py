"""
Your bot's loadout configuration.
Edit this file to equip different gear — the lobby UI updates live!

Budget: 100 points. Pick your gear wisely.

WEAPONS (pick one):
  "standard-blaster"  (0 pts)  - 30 dmg, range 5
  "shotgun"           (15 pts) - 50 dmg, range 3
  "sniper-rifle"      (20 pts) - 40 dmg, range 12
  "plasma-cannon"     (25 pts) - 60 dmg, range 5, 2-tick cooldown

ARMOR (pick one):
  "light-vest"        (10 pts) - 2500 HP (default 2000)
  "heavy-armor"       (25 pts) - 3000 HP
  "energy-shield"     (20 pts) - 200 shield HP absorbs damage first

MOVEMENT (pick one):
  "speed-boost"       (15 pts) - move 2 tiles per action
  "teleporter"        (30 pts) - random teleport, 20-tick cooldown

UTILITY (pick multiple):
  "radar"             (15 pts) - see 8 tiles (default 5)
  "extra-ammo"        (10 pts) - start with 10 ammo, max 15
  "grenades-plus"     (10 pts) - start with 5 grenades
  "trap-mine"         (15 pts) - place invisible mines (60 dmg)
  "decoy"             (20 pts) - fake blip that lures enemies
"""

LOADOUT = {
    "name": "StarterBot",
    "avatar": "TinyRick",   # TinyRick, PickleRick, CrowRick, RickNinja, RickRobot, RickWarrior, RickMiami
                             # Or drop an avatar.png in this folder for a custom image!

    "gear": {
        "weapon": "sniper-rifle",       # 20 pts
        "armor": "light-vest",          # 10 pts
        # "movement": "speed-boost",    # 15 pts
        "utility": [
            "radar",                    # 15 pts
            "extra-ammo",              # 10 pts
        ],
    },
    # Total: 55 / 100 pts
}
