"""
Gear catalog and loadout validation — mirrors battle-cab/src/game/core.clj gear-catalog.
This is the source of truth for the Python side (lobby server + bots).
"""

BUDGET = 100

GEAR_CATALOG = {
    # Weapons (pick one)
    "standard-blaster": {"slot": "weapon",   "cost": 0,  "effects": {"shoot-damage": 30, "shoot-range": 5}},
    "shotgun":          {"slot": "weapon",   "cost": 15, "effects": {"shoot-damage": 50, "shoot-range": 3}},
    "sniper-rifle":     {"slot": "weapon",   "cost": 20, "effects": {"shoot-damage": 40, "shoot-range": 12}},
    "plasma-cannon":    {"slot": "weapon",   "cost": 25, "effects": {"shoot-damage": 60, "shoot-range": 5, "shoot-cooldown": 2}},

    # Armor (pick one)
    "light-vest":       {"slot": "armor",    "cost": 10, "effects": {"max-hp": 600}},
    "heavy-armor":      {"slot": "armor",    "cost": 25, "effects": {"max-hp": 750, "speed": 0}},
    "energy-shield":    {"slot": "armor",    "cost": 20, "effects": {"shield-hp": 50}},

    # Movement (pick one)
    "speed-boost":      {"slot": "movement", "cost": 15, "effects": {"speed": 2}},
    "teleporter":       {"slot": "movement", "cost": 30, "effects": {"can-teleport": True, "teleport-cooldown": 20}},

    # Utility (pick multiple)
    "radar":            {"slot": "utility",  "cost": 15, "effects": {"visibility-radius": 8}},
    "extra-ammo":       {"slot": "utility",  "cost": 10, "effects": {"start-ammo": 10, "max-ammo": 15}},
    "grenades-plus":    {"slot": "utility",  "cost": 10, "effects": {"start-grenades": 5}},
    "trap-mine":        {"slot": "utility",  "cost": 15, "effects": {"can-trap": True, "max-traps": 3}},
    "decoy":            {"slot": "utility",  "cost": 20, "effects": {"can-decoy": True, "decoy-cooldown": 15}},
}

# Default stats (no gear equipped)
DEFAULT_STATS = {
    "max-hp": 500,
    "shoot-damage": 30,
    "shoot-range": 5,
    "shoot-cooldown": 0,
    "speed": 1,
    "visibility-radius": 5,
    "start-ammo": 5,
    "max-ammo": 10,
    "start-grenades": 2,
    "shield-hp": 0,
    "can-teleport": False,
    "can-trap": False,
    "can-decoy": False,
}


def validate_loadout(loadout):
    """
    Validate a loadout dict. Returns:
    {
        "valid": bool,
        "errors": [...],
        "cost": int,
        "effects": {...},  # merged stat effects
        "stats": {...},    # final resolved stats
        "items": [...]     # list of item keys
    }
    """
    if not loadout:
        return {
            "valid": True, "errors": [], "cost": 0,
            "effects": {}, "stats": dict(DEFAULT_STATS), "items": [],
        }

    weapon = loadout.get("weapon", "standard-blaster")
    armor = loadout.get("armor")
    movement = loadout.get("movement")
    utility = loadout.get("utility", [])
    if isinstance(utility, str):
        utility = [utility]

    all_items = [weapon]
    if armor:
        all_items.append(armor)
    if movement:
        all_items.append(movement)
    all_items.extend(utility)

    errors = []

    # Check all items exist
    unknown = [i for i in all_items if i not in GEAR_CATALOG]
    if unknown:
        errors.append(f"Unknown gear: {unknown}")

    # Check slot constraints
    slots = {}
    for item in all_items:
        if item in GEAR_CATALOG:
            slot = GEAR_CATALOG[item]["slot"]
            slots.setdefault(slot, []).append(item)

    for slot in ["weapon", "armor", "movement"]:
        if len(slots.get(slot, [])) > 1:
            errors.append(f"Can only equip one {slot}")

    # Calculate cost
    total_cost = sum(GEAR_CATALOG.get(i, {}).get("cost", 0) for i in all_items)
    if total_cost > BUDGET:
        errors.append(f"Over budget: {total_cost}/{BUDGET}")

    # Merge effects
    effects = {}
    for item in all_items:
        if item in GEAR_CATALOG:
            effects.update(GEAR_CATALOG[item]["effects"])

    # Heavy armor blocks speed boost
    if armor == "heavy-armor" and effects.get("speed", 1) == 0:
        effects["speed"] = 1

    # Resolve final stats
    stats = dict(DEFAULT_STATS)
    stats.update(effects)

    return {
        "valid": len(errors) == 0,
        "errors": errors,
        "cost": total_cost,
        "budget": BUDGET,
        "effects": effects,
        "stats": stats,
        "items": all_items,
    }
