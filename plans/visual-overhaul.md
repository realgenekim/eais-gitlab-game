# Visual Overhaul: Animated Sprites, Rankings, Mario Kart Feel

## The Problem
Tiny emoji on colored squares is boring to watch. No personality, no spectacle,
no way to tell who's who at a glance.

## The Vision

### Before (current):
```
 ######################
 #  .   .   .   .   . #
 #  .  [R] [G]  .   . #     SCOREBOARD
 #  .   .   .   .   . #     ----------
 #  .   $   .  [B]  . #     player-abc  150pts
 #  .   .   .   .   . #     player-def  100pts
 #  .  [Y]  .   .   . #     player-ghi   50pts
 #  .   .   .   $   . #     player-jkl    0pts
 ######################

 [R] = red bg cell with tiny emoji. Hard to read. Who is who?
```

### After:
```
 ######################
 #  .   .   .   .   . #
 #  .  @1   @2  .   . #      SCOREBOARD
 #  .   .   .   .   . #     .---------------------------.
 #  .   $   .  @3   . #     | #1  @  StarCab     150pts |  <-- big rank, sprite, name
 #  .   .   .   .   . #     | #2  @  ZoomTaxi    100pts |
 #  .  @4   .   .   . #     | #3  @  BoomCar      50pts |
 #  .   .   .   $   . #     | #4  @  SlowPoke      0pts |
 ######################      '---------------------------'

 @1 = animated sprite with small colored dot + rank number
      the NUMBER tells you rank at a glance
      the DOT tells you which color
      the SPRITE is a fun animated character
```

### Grid Cell Detail — What a player cell looks like:
```
  CURRENT:                    NEW:
  .-----------.               .-----------.
  | ///COLOR///|               |           |
  | ///BG//// |               |   ( @ )   |  <-- animated sprite
  |   [cab]   |               |    .1     |  <-- colored dot + rank
  | ///emoji//|               |           |
  '-----------'               '-----------'
  colored bg = hard           dark bg = easy on eyes
  to read emoji               sprite + number pop
```

### Sprite Ideas (CSS animated, cycling frames):
```
  Frame 1    Frame 2    Frame 3    Frame 4
   .---.      .---.      .---.      .---.
   |o o|      |^ ^|      |> <|      |o o|    <-- face animates
   | > |      | o |      | < |      | D |    <-- expression changes
   [===]      [===]      [===]      [===]    <-- car body stays
   o   o      o   o       o   o     o   o    <-- wheels wobble

  With passenger:
   .---.
   |$ $|      <-- dollar sign eyes when carrying passenger!
   | D |
   [===]
   o   o
```

### Whimsical Sprite Themes (pick per player):
```
  Rocket Car    Ghost Cab     Taco Truck    UFO Taxi
    /\            .-.          .=====.        ___
   /  \          ( O )        || TACO||      (o o)
  [====]         /-+-\        ||  :D ||      /===\
   o  o          o   o        o=====o       ~~~~~~~
```

### Scoreboard — Big and Bold:
```
  .-------------------------------------------.
  |                                           |
  |  1st   [sprite]  StarCab       150 pts    |  gold bg
  |                                           |
  |  2nd   [sprite]  ZoomTaxi      100 pts    |  silver bg
  |                                           |
  |  3rd   [sprite]  BoomCar        50 pts    |  bronze bg
  |                                           |
  |  4th   [sprite]  SlowPoke        0 pts    |  normal
  |                                           |
  '-------------------------------------------'

  - Rank numbers are HUGE (2rem+)
  - Each row has the player's color as left border
  - Sprite animates even in scoreboard
  - Dead players: sprite is upside-down or X eyes
```

### On the Grid — What you see:
```
  Each player cell shows:
  ┌─────────┐
  │  sprite  │  <-- animated character (CSS keyframes)
  │  ●  #1   │  <-- colored dot + rank number
  └─────────┘

  - Dark cell background (same as open cells)
  - Sprite is the visual star
  - Small colored dot for team identification
  - Rank number so spectators instantly know standings
  - When carrying passenger: sprite changes (dollar eyes, taxi light on)
  - When dead: skull sprite, greyed out
  - When hit: shake animation
  - When shooting: muzzle flash sprite
```

## Implementation Plan

### Phase 1: Sprite System
- Define 4-8 sprite characters as CSS animations (emoji sequences or small pixel art)
- Each player gets assigned a sprite on join
- Sprites cycle through 4 frames via CSS `@keyframes`
- Carrying passenger = alternate sprite set
- Dead = skull/ghost sprite

### Phase 2: Grid Cell Rework
- Remove colored background from player cells
- Player cell stays dark (same as open)
- Add sprite element (centered, large)
- Add rank badge: small colored circle + number
- Rank updates every tick based on score sorting

### Phase 3: Scoreboard Overhaul
- Big rows with rank numbers (1st/2nd/3rd with medal colors)
- Player sprite animates in scoreboard too
- Color bar on left edge of each row
- HP bar visual instead of text
- Dead players: crossed out, ghost sprite

### Phase 4: Hype Overlays (stretch)
- Full-screen "FIRST BLOOD!" banners
- Kill streak counters
- "FINAL LAP" mode change
