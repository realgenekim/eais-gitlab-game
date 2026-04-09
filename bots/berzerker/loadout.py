"""
BERZERKER — close-range chaos build.
Rushes enemies with shotgun + speed boost, throws grenades into crowds.
"""

LOADOUT = {
    "name": "Berzerker",
    "avatar": "RickWarrior",

    "gear": {
        "weapon": "shotgun",           # 15 pts — 50 dmg, range 3
        "armor": "light-vest",          # 10 pts — 600 HP
        "movement": "speed-boost",      # 15 pts — 2 tiles per move
        "utility": [
            "grenades-plus",            # 10 pts — 5 grenades
            "extra-ammo",              # 10 pts — 10 ammo, max 15
            "trap-mine",               # 15 pts — invisible mines
        ],
    },
    # Total: 75 / 100 pts
}
