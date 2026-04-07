# VS Arena — Phaser Spectator Client

Based on [Emanuele Feronato's Phaser VS prototype](https://emanueleferonato.com/2024/11/29/quick-html5-prototype-of-vampire-survivors-built-with-phaser-like-the-original-game/),
skinned with [rick-survival](https://github.com/yudinikita/rick-survival) sprite assets.

## Quick Start (MVP 1)

```bash
cd spectator
make install   # npm install (one time)
make dev       # Vite dev server on http://localhost:5173
```

Open http://localhost:5173 — WASD to move Rick, auto-fire kills FloopyDoops, collect XP gems.

## What's Working (MVP 1)

- RickDefault player with directional walk animations (up/down/left/right)
- FloopyDoops enemies swarming toward player
- Blaster bullet sprites from rick-survival atlas
- ExpGem drops on enemy kill with magnet pickup
- Kill counter HUD
- All sprites from rick-survival (TexturePacker → Phaser atlas format)

## MVP Progression

| MVP | Status | What it proves |
|-----|--------|----------------|
| 1   | DONE   | Rick-survival sprites work in Phaser VS prototype |
| 2   | next   | Clojure server → WebSocket → Phaser pipeline |
| 3   |        | Server-side enemies + wave spawning |
| 4   |        | Manual targeting (bots must aim, no auto-fire) |
| 5   |        | Full game: teams, waves, shrink, 10-min round |

## Architecture

Currently runs as a standalone client (Feronato's VS prototype + rick-survival sprites).
Will evolve into a render-only spectator that receives state from the Clojure server via WebSocket.

```
[Current: MVP 1]
  Phaser client runs game logic locally (enemy spawning, physics, etc.)

[Target: MVP 2+]
  Bots ──REST──→ Clojure Server ──WebSocket──→ Phaser Spectator (render only)
```
