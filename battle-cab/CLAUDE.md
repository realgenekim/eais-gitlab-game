# Cab Battle — EAIS Game Night

## What This Is
Multiplayer bot-programming game server for Enterprise AI Summit game night.
Players write bots (LLM or hand-coded) that control taxis on a grid maze via REST API.
Pick up passengers, deliver to destinations, shoot rival cabs.

## Architecture
- **Clojure backend** — http-kit + reitit
- **Phaser spectator** — `spectator/` Vite+TypeScript app, reads game state via WebSocket JSON
- **Tick-based game loop** — state advances every 250ms, pure functions
- **Full replay** — every command and state transition saved
- **SSE view is ABANDONED** — game.sse, game.views, game.ds are legacy; do not invest in them. Phaser is the spectator UI going forward.

## Namespace Layout
```
game.core       — Pure game engine (state transitions, no side effects)
game.maps       — ASCII map parser + built-in maps
game.replay     — Command logging + deterministic replay
game.engine     — Mutable game loop, system lifecycle (atoms, timers)
game.server     — REST API endpoints (Ring/reitit handlers)
game.sse        — Datastar SSE broadcaster for spectator view
game.views      — Hiccup templates (spectator page, scoreboard)
game.ds         — Datastar expression helpers
game.dm         — AI Dungeon Master agent (Claude API, game balancing)
game.commentator — AI color commentator (Claude → ElevenLabs TTS)
```

## Development Workflow
```bash
make nrepl           # Terminal 1: Start nREPL
make runtests        # Terminal 2: Watch tests
make server-dev      # Terminal 3: Dev server (ENV=dev, port 33333)
make restart         # Stop + restart dev server (needed for new routes/arities)
make mcp-configure   # One-time: Configure Clojure MCP for Claude Code
```

**IMPORTANT: Run server in background** so Claude can do other work while it runs:
```bash
lsof -ti:33333 | xargs kill -9 2>/dev/null; sleep 2
ENV=dev clojure -M:dev -m game.server 2>&1 &
```
Use top-level Makefile targets to manage: `make reset-game`, `make add-bots`, `make status`

**Checking game status from outside the JVM** (when nREPL is in a different process):
```bash
curl -s http://localhost:33333/game/status   # tick, players, running?
curl -s http://localhost:33333/game/ascii    # ASCII map render
curl -X POST http://localhost:33333/game/restart -H 'Content-Type: application/json' -d '{"map":"arena"}'
```

## Frame-by-Frame Replay (Debugging Power Tool)

The server saves every tick's full state JSON to an in-memory ring buffer (last 2000 frames).
This is the primary debugging tool for visual issues — inspect exactly what the server sent for any tick.

### How to use

**Check what frames are available:**
```bash
curl -s http://localhost:33333/game/frame | python3 -m json.tool
# {"latest-tick": 200, "frame-count": 200, "oldest-tick": 1}
```

**Fetch a specific frame:**
```bash
curl -s "http://localhost:33333/game/frame?tick=70" | python3 -m json.tool
```

**View in Phaser spectator (frozen, steppable):**
```
http://localhost:5173/?server&frame=70
```
Keyboard: J/Left = prev frame, K/Right = next, H = back 10, L = forward 10

**Inspect shot data in a frame (CLI):**
```bash
curl -s "http://localhost:33333/game/frame?tick=70" | python3 -c "
import sys, json
d = json.load(sys.stdin)
shots = d.get('recent-shots', [])
print(f'tick={d[\"tick\"]} shots={len(shots)}')
for s in shots:
    print(f'  fired-tick={s.get(\"fired-tick\")} dir={s[\"direction\"]} path={s[\"path\"]}')
"
```

### Why this matters

This tool caught and fixed the shot accumulation bug: shots had a backwards expiry filter
(`(> (+ tick 3) fired-tick)` is always true) causing shots to never expire. Frame inspection
showed tick=70 had 7 shots with fired-ticks=[18,22,22,30,30,50,69] — immediately obvious
that old shots were persisting. Fixed to `(>= (+ fired-tick 3) tick)`, verified same frame
now shows 3 shots (fired-ticks=[67,68,69]).

### Typical debugging workflow

1. Let bots play for a while: `make reset-game && make add-bots`
2. Notice visual bug in spectator
3. Check available frames: `curl http://localhost:33333/game/frame`
4. Open frame in browser: `http://localhost:5173/?server&frame=N`
5. Step through with J/K to find exact tick where issue appears
6. Fetch that frame's JSON via curl to inspect data
7. Fix server code, restart, verify same frame range looks correct

### Frame buffer clears on game restart (reset-game)

## Key Conventions
- Route handlers: named `defn handle-xxx`, referenced as `#'handle-xxx` in routes
- State: atoms managed through game.engine system map
- All game logic in game.core is **pure** — takes state + commands, returns new state
- Views: Hiccup templates pushed via Datastar SSE fragments
- Logging: Timbre with structured keyword args, never println

## Testing
```bash
make runtests-once   # Fast fail-first test run
```
Tests mirror source: `src/game/core.clj` → `test/game/core_test.clj`

## Logging
Use Timbre with structured keyword args, never println:
```clojure
(require '[taoensso.timbre :as log])
(log/info :server-started :port 8080)
(log/error :tick-error :msg (.getMessage e) :error e)
```

## Route Handler Convention
Always extract route handlers as named `defn handle-xxx` functions with `#'var` references in the route table for REPL reload:
```clojure
(defn handle-home [_] (resp/response (str (views/home-page))))
(defn make-routes [] [["/" {:get {:handler #'handle-home}}]])
```

## Reitit Hot-Reload
Use `reitit.ring/reloading-ring-handler` in dev mode so new route paths work without restart.

## ENV=dev Convention
Single env var gates all dev behavior: browser-reload, code reloading, DEV banner, auth bypass.

## REPL Workflow
- Always have nREPL running (`make nrepl`)
- Use `(comment ...)` blocks for REPL exploration
- Test functions in REPL before writing unit tests
- `dev/user.clj` loaded automatically for REPL utilities
