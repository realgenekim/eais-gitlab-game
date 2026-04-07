# REPL Reload — What Works Without Restart

## The Problem (before fix)

`engine/start-game!` captured `sse/on-tick` **by value** (the function object).
After `(require '[game.sse :as sse] :reload)`, the var `#'sse/on-tick` points to the
new function, but the system map still holds the OLD function. Result: you had to
restart the game to pick up view/SSE changes.

## The Fix

Pass `#'sse/on-tick` (the **var**) instead of `sse/on-tick` (the function).
The engine now checks `(var? on-tick)` and derefs at call time. This means
the tick loop always calls the latest version of `on-tick` after a REPL reload.

## What You Can Reload Without Restart

| Change | Command | Restart needed? |
|--------|---------|-----------------|
| View templates (game.views) | `(require '[game.views :as views] :reload)` | No |
| SSE push logic (game.sse) | `(require '[game.sse :as sse] :reload)` | No |
| CSS changes | `(sse/reload-browsers!)` | No |
| Game engine logic (game.core) | `(require '[game.core :as core] :reload)` | No (next tick uses new fns) |
| Route handlers (game.server) | `(require '[game.server :as server] :reload)` | No (`#'app` var ref) |
| New routes or middleware | Reload + restart HTTP server | Yes (reitit compiles routes at def-time) |
| Game config (tick rate, map) | Restart game | Yes (config baked into state at start) |

## Quick Iteration Workflow

```clojure
;; Edit views/CSS, then:
(require '[game.views :as views] :reload)
(sse/reload-browsers!)   ;; all spectators refresh

;; Edit SSE logic:
(require '[game.sse :as sse] :reload)
;; Next tick automatically uses new on-tick (var deref)

;; Edit game rules:
(require '[game.core :as core] :reload)
;; Next tick uses new advance-tick, apply-action, etc.

;; Nuclear option — full restart:
(engine/stop-game!)
(engine/start-game! {:on-tick #'sse/on-tick})
```
