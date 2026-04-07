# Game Settings Reference

## Game Config (game.core/make-initial-state)

Set in `src/game/core.clj` — requires game restart to take effect.

| Setting | Default | Description |
|---------|---------|-------------|
| `:tick-ms` | 250 | Milliseconds per tick. Lower = faster. 500=chill, 250=frenetic |
| `:game-duration-ticks` | 1000 | Ticks before game over. At 250ms = ~4 min per round |
| `:max-players` | 8 | Max simultaneous players |
| `:max-passengers` | 6 | Passengers on map at once |
| `:shoot-range` | 20 | How far bullets travel (cells). 20 = nearly full map |
| `:shoot-damage` | 30 | HP damage per hit. 100hp / 30dmg = 4 hits to kill |
| `:visibility-radius` | 5 | Fog of war radius for player API (spectator sees all) |
| `:ammo-regen-ticks` | 5 | Regen 1 ammo every N ticks |
| `:max-ammo` | 10 | Ammo cap |

## Bullet Tracer Visuals (game.views/game-map-fragment)

Set in `src/game/views.clj` — reload views, no restart needed.

| Setting | Default | Description |
|---------|---------|-------------|
| `tracer-length` | 5 | Beam length — cells lit at once (the "laser") |
| `tracer-travel` | 10 | Cells the beam sweeps through per tick animation |

**How they interact:**

```
tracer-length=5, tracer-travel=10:
A 5-cell beam slides across 10 cells during the tick.

t=0.0  S . . . . . . . . . .
t=0.2  S = = = . . . . . . .    (beam forming)
t=0.5  S . . = = = = = . . .    (full beam, sweeping)
t=0.8  S . . . . . . = = = =    (arriving at target)
t=1.0  S . . . . . . . . . .    (faded)

tracer-length=3, tracer-travel=15:
A short 3-cell bolt zipping across 15 cells. Faster, snappier.

tracer-length=8, tracer-travel=8:
All 8 cells light up at once and fade together. No sweep, just flash.
```

After editing, reload with no restart:
```clojure
(require '[game.views :as views] :reload)
;; Next tick uses new values automatically
```

## Player Stats (game.core/add-player)

| Stat | Value | Notes |
|------|-------|-------|
| HP | 100 | 4 hits to kill (at 30 damage) |
| Ammo | 5 | Starting ammo |
| Grenades | 2 | Not yet implemented |
| Respawn delay | 10 ticks | At 250ms = 2.5 seconds |

## Scoring

| Action | Points |
|--------|--------|
| Passenger delivery | +100 |
| Kill | +50 |

## REPL Controls

```clojure
;; Game lifecycle
(engine/start-game! {:on-tick #'sse/on-tick})
(engine/pause-game!)     ;; freeze ticks, server stays up
(engine/resume-game!)    ;; resume from where paused
(engine/stop-game!)      ;; stop + save replay

;; Browser control
(sse/reload-browsers!)   ;; force all spectators to refresh

;; Hot reload (no restart needed)
(require '[game.views :as views] :reload)   ;; view changes
(require '[game.sse :as sse] :reload)       ;; SSE logic
(require '[game.core :as core] :reload)     ;; game rules
```
