(ns game.engine
  "Mutable game state management. System lifecycle with start/stop.
   Owns the atoms, tick timer, event log. game.core stays pure."
  (:require [game.core :as core]
            [game.maps :as maps]
            [game.replay :as replay]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.util Timer TimerTask]))

;;; ---------------------------------------------------------------------------
;;; System State
;;; ---------------------------------------------------------------------------

(defonce system (atom nil))

(defn get-state
  "Current game state."
  []
  (some-> @system :game-state deref))

(defn get-recorder []
  (some-> @system :recorder))

;;; ---------------------------------------------------------------------------
;;; Event Log — decouples tick loop from DM, commentator, SSE
;;; ---------------------------------------------------------------------------

(defn append-events!
  "Append events to the event log. Each event is a map with :type and details."
  [sys events]
  (when (seq events)
    (swap! (:event-log sys) into events)))

(defn drain-events!
  "Drain and return all events since last drain."
  [sys]
  (let [events @(:event-log sys)]
    (reset! (:event-log sys) [])
    events))

(defn recent-events
  "Return the last N events without draining."
  [sys n]
  (let [events @(:event-log sys)]
    (take-last n events)))

;;; ---------------------------------------------------------------------------
;;; Command Queue
;;; ---------------------------------------------------------------------------

(defn enqueue-command!
  "Queue a player command for the next tick."
  [sys player-id action]
  (swap! (:command-queue sys) conj {:player-id player-id :action action}))

(defn drain-commands!
  "Drain and return all queued commands."
  [sys]
  (let [cmds @(:command-queue sys)]
    (reset! (:command-queue sys) [])
    cmds))

;;; ---------------------------------------------------------------------------
;;; Player Management
;;; ---------------------------------------------------------------------------

(defn add-player!
  "Add a player. Returns {:id ... :token ...} or nil if full."
  [sys player-name]
  (let [state @(:game-state sys)
        max-p (get-in state [:config :max-players] 8)]
    (when (< (count (:players state)) max-p)
      (let [[new-state creds] (core/add-player state player-name)]
        (reset! (:game-state sys) new-state)
        ;; Update recorder's initial state if tick 0
        (when (zero? (:tick new-state))
          (swap! (:recorder sys) assoc :initial-state new-state
                 :snapshots {0 new-state}))
        ;; Track token → player-id
        (swap! (:token->player sys) assoc (:token creds) (:id creds))
        ;; Log event
        (append-events! sys [{:type :player-joined
                              :player-id (:id creds)
                              :name player-name
                              :tick (:tick new-state)}])
        creds))))

(defn authenticate
  "Look up player-id from a token."
  [sys token]
  (get @(:token->player sys) token))

(declare stop-game!)

;;; ---------------------------------------------------------------------------
;;; Tick — the heartbeat
;;; ---------------------------------------------------------------------------

(defn- detect-events
  "Compare old and new state, return event maps."
  [old-state new-state commands]
  (let [tick (:tick old-state)
        events (transient [])]
    ;; Commands executed
    (doseq [{:keys [player-id action]} commands]
      (conj! events {:type :command :tick tick
                     :player-id player-id :action action}))
    ;; Deaths
    (doseq [[id player] (:players new-state)]
      (when (and (not (:alive? player))
                 (get-in old-state [:players id :alive?]))
        (conj! events {:type :kill :tick tick :victim-id id})))
    ;; Deliveries (score increased by 100)
    (doseq [[id player] (:players new-state)]
      (let [old-score (get-in old-state [:players id :score] 0)]
        (when (> (:score player) old-score)
          (conj! events {:type :delivery :tick tick :player-id id
                         :points (- (:score player) old-score)}))))
    ;; Respawns
    (doseq [[id player] (:players new-state)]
      (when (and (:alive? player)
                 (not (get-in old-state [:players id :alive?])))
        (conj! events {:type :respawn :tick tick :player-id id})))
    (persistent! events)))

(defn tick!
  "Advance the game one tick. Called by the timer."
  [sys]
  (try
    (let [commands (drain-commands! sys)
          old-state @(:game-state sys)
          new-state (core/advance-tick old-state commands)
          events (detect-events old-state new-state commands)]
      ;; Record for replay
      (replay/record-tick! (:recorder sys) (:tick old-state) commands new-state)
      ;; Advance state
      (reset! (:game-state sys) new-state)
      ;; Append events
      (append-events! sys (conj events {:type :tick :tick (:tick new-state)}))
      ;; Notify SSE subscribers (deref var for REPL reload)
      (when-let [on-tick (:on-tick sys)]
        (if (var? on-tick)
          (@on-tick sys new-state)
          (on-tick sys new-state)))
      ;; Check game over
      (when (>= (:tick new-state) (get-in new-state [:config :game-duration-ticks]))
        (log/info :game-over :tick (:tick new-state))
        (append-events! sys [{:type :game-over :tick (:tick new-state)
                              :scores (->> (:players new-state)
                                           (map (fn [[id p]] {:id id :name (:name p) :score (:score p)}))
                                           (sort-by :score >))}])
        (stop-game! sys)))
    (catch Exception e
      (log/error :tick-error :msg (.getMessage e) :error e))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(defn start-game!
  "Start a new game. Returns the system map."
  ([] (start-game! {}))
  ([opts]
   (let [game-map (:game-map opts maps/arena-map)
         on-tick (:on-tick opts)
         state (core/make-initial-state game-map)
         sys {:game-state (atom state)
              :recorder (replay/make-recorder state)
              :command-queue (atom [])
              :token->player (atom {})
              :event-log (atom [])
              :game-timer (atom nil)
              :on-tick on-tick}
         tick-ms (get-in state [:config :tick-ms] 500)
         timer (Timer. "game-tick" true)
         task (proxy [TimerTask] []
                (run [] (tick! sys)))]
     (.scheduleAtFixedRate timer task (long tick-ms) (long tick-ms))
     (reset! (:game-timer sys) timer)
     (reset! system sys)
     (log/info :game-started :tick-ms tick-ms :map-size
               (str (get-in state [:map :width]) "x" (get-in state [:map :height])))
     sys)))

(defn pause-game!
  "Pause the tick timer. Game state frozen, server still responds."
  ([] (pause-game! @system))
  ([sys]
   (when-let [timer @(:game-timer sys)]
     (.cancel timer)
     (reset! (:game-timer sys) nil)
     (log/info :game-paused :tick (:tick @(:game-state sys))))))

(defn resume-game!
  "Resume a paused game."
  ([] (resume-game! @system))
  ([sys]
   (when (and sys (nil? @(:game-timer sys)))
     (let [tick-ms (get-in @(:game-state sys) [:config :tick-ms] 500)
           timer (Timer. "game-tick" true)
           task (proxy [TimerTask] []
                  (run [] (tick! sys)))]
       (.scheduleAtFixedRate timer task (long tick-ms) (long tick-ms))
       (reset! (:game-timer sys) timer)
       (log/info :game-resumed :tick (:tick @(:game-state sys)))))))

(defn stop-game!
  "Stop the current game, save replay."
  ([] (stop-game! @system))
  ([sys]
   (when sys
     (when-let [timer @(:game-timer sys)]
       (.cancel timer)
       (reset! (:game-timer sys) nil))
     ;; Save replay
     (let [filepath (str "replays/game-" (System/currentTimeMillis) ".jsonl")]
       (io/make-parents filepath)
       (replay/save-recording! (:recorder sys) filepath)
       (log/info :replay-saved :path filepath))
     ;; Append shutdown event
     (append-events! sys [{:type :game-stopped}]))))

(defn restart-game!
  "Stop current game and start a new one."
  ([] (restart-game! {}))
  ([opts]
   (stop-game!)
   (start-game! opts)))
