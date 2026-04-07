# VS Arena — Phaser Spectator Client

Based on [Emanuele Feronato's Phaser VS prototype](https://emanueleferonato.com/2024/11/29/quick-html5-prototype-of-vampire-survivors-built-with-phaser-like-the-original-game/),
skinned with [rick-survival](https://github.com/yudinikita/rick-survival) sprite assets.

## URL Modes

| URL | Mode |
|-----|------|
| `http://localhost:5173` | MVP 1: standalone local game (WASD, auto-fire) |
| `http://localhost:5173/?server` | Live: connected to Clojure server via WebSocket |
| `http://localhost:5173/?server&frame=50` | Frame replay: view saved tick 50 (J/K to step) |

## Quick Start

```bash
# Terminal 1: Server
cd battle-cab && make server-dev

# Terminal 2: Spectator
cd spectator && make dev

# Terminal 3: Add bots
make reset-game && make add-bots
# Or: open http://localhost:5173/?server and click "+ Add Bot"
```

## Frame-by-Frame Replay

Add `&frame=N` to the URL to inspect a specific server tick:
```
http://localhost:5173/?server&frame=50
```

Keyboard controls in frame mode:
- **J / Left Arrow**: Previous frame
- **K / Right Arrow**: Next frame
- **H**: Back 10 frames
- **L**: Forward 10 frames

Server saves last 2000 frames. Check available frames:
```bash
curl http://localhost:33333/game/frame
# {"latest-tick":200, "frame-count":200, "oldest-tick":1}
```

## MVP Status

| MVP | Status | What |
|-----|--------|------|
| 1 | DONE | Standalone Phaser + rick-survival sprites |
| 2 | DONE | Server → WebSocket → Phaser pipeline |
| 3 | DONE | Server-side enemies, waves, shooting kills enemies |
| 4 | DONE | Manual targeting + smart bot (Python) |
| 5 | Next | Full game: teams, waves, shrink, 10-min round |

## Architecture

```
Bots ──REST──→ Clojure Server ──WebSocket──→ Phaser Spectator (render only)
                     │
              frame buffer (2000 ticks)
                     │
              GET /game/frame?tick=N ──→ Phaser frame replay mode
```
