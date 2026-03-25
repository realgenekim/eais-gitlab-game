# Cab Battle — EAIS Game Night

## What This Is
Multiplayer bot-programming game server for Enterprise AI Summit game night.
Players write bots (LLM or hand-coded) that control taxis on a grid maze via REST API.
Pick up passengers, deliver to destinations, shoot rival cabs.

## Architecture
- **Clojure backend** — http-kit + reitit + Datastar SSE
- **Tick-based game loop** — state advances every 500ms, pure functions
- **Full replay** — every command and state transition saved
- **Datastar SSE spectator view** — live HTML fragment push to browser

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
make server-dev      # Terminal 3: Dev server (ENV=dev, port 8080)
make mcp-configure   # One-time: Configure Clojure MCP for Claude Code
```

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
