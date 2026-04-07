# Server Operations

## Starting the Server

### Option 1: From command line (production-like)
```bash
make server-dev    # ENV=dev, port 33333
make server-prod   # production mode, port 33333
```

### Option 2: From REPL (development — recommended)
```bash
# Terminal 1: start nREPL
make nrepl

# Terminal 2: connect and start
```

```clojure
;; In REPL:
(require '[game.engine :as engine])
(require '[game.sse :as sse])
(require '[game.server :as server])
(require '[org.httpkit.server :as http])

;; Start game engine (tick loop + state management)
(engine/start-game! {:on-tick #'sse/on-tick})

;; Start HTTP server
(def srv (http/run-server #'server/app {:port 33333}))

;; Open browser
;; http://localhost:33333/       — spectator view
;; http://localhost:33333/game/ascii — terminal view
```

## Stopping the Server

```clojure
;; Stop game (saves replay)
(engine/stop-game!)

;; Stop HTTP server
(srv)   ;; call the stop fn returned by http/run-server
```

From command line:
```bash
make stop   # kills process on port 33333
```

## Running Bots (for testing/demos)

```clojure
;; Add players
(def bots (mapv #(engine/add-player! @engine/system %)
                ["Alpha" "Bravo" "Charlie" "Delta" "Echo"]))

;; Smart bot: shoots when enemy is in line of sight, otherwise roams
(defn start-smart-bot! [bot-creds]
  (let [dirs [:north :south :east :west]]
    (future
      (let [sys @engine/system
            id  (:id bot-creds)]
        (loop []
          (when (some? @(:game-timer sys))
            (let [state (engine/get-state)
                  me    (get-in state [:players id])
                  enemies (->> (:players state)
                               (remove (fn [[eid _]] (= eid id)))
                               (filter (fn [[_ p]] (:alive? p)))
                               (map (fn [[eid p]] {:id eid :x (:x p) :y (:y p)})))
                  shot-dir (some (fn [e]
                                   (cond
                                     (and (= (:y e) (:y me)) (< (:x e) (:x me))) :west
                                     (and (= (:y e) (:y me)) (> (:x e) (:x me))) :east
                                     (and (= (:x e) (:x me)) (< (:y e) (:y me))) :north
                                     (and (= (:x e) (:x me)) (> (:y e) (:y me))) :south))
                                 enemies)]
              (if (and shot-dir (:alive? me) (pos? (:ammo me)))
                (engine/enqueue-command! sys id {:type :shoot :direction shot-dir})
                (do
                  (engine/enqueue-command! sys id {:type :move :direction (rand-nth dirs)})
                  (when (< (rand) 0.4) (engine/enqueue-command! sys id {:type :pickup}))
                  (when (< (rand) 0.4) (engine/enqueue-command! sys id {:type :dropoff})))))
            (Thread/sleep 250)  ;; match tick rate
            (recur)))))))

;; Start all bots
(doseq [b bots] (start-smart-bot! b))
```

## Game Control During Play

```clojure
(engine/pause-game!)    ;; freeze ticks, server stays up
(engine/resume-game!)   ;; resume from where paused
(engine/stop-game!)     ;; end game, save replay

;; Start a fresh game (keeps HTTP server running)
(engine/start-game! {:on-tick #'sse/on-tick})
```

## Spectator Browser Management

```clojure
;; Force all spectator browsers to reload (after CSS/view changes)
(sse/reload-browsers!)

;; Check how many spectators are connected
(sse/subscriber-count)
```

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Spectator page (Vampire Survivors view) |
| `/spectate` | GET | SSE stream (Datastar connects automatically) |
| `/game/join` | POST | `{"name": "bot-name"}` → `{player-id, token}` |
| `/game/state` | GET | Player's fog-of-war view (needs token) |
| `/game/action` | POST | `{"token": "...", "action": "move", "direction": "north"}` |
| `/game/scoreboard` | GET | All player scores |
| `/game/map` | GET | Map data as JSON |
| `/game/status` | GET | Tick, player count, spectator count |
| `/game/ascii` | GET | ASCII art terminal view |

## Replay Files

Replays are saved to `replays/` when a game ends or is stopped:
```
replays/game-1774422656707.jsonl
```

Format: JSONL (one JSON object per line). First line is initial state, remaining lines are per-tick commands.

## Troubleshooting

**Browser shows "Waiting for game data"**: Game not running. Start with `(engine/start-game! ...)`.

**Port already in use**: `make stop` or `lsof -ti :33333 | xargs kill -9`.

**CSS changes not showing**: `(sse/reload-browsers!)` to force all spectators to refresh.

**View changes not taking effect**: `(require '[game.views :as views] :reload)`. No game restart needed (var deref).

**Game stuck / not ticking**: Check `(some? @(:game-timer @engine/system))`. If nil, game is paused or stopped.
