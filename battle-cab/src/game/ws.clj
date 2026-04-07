(ns game.ws
  "WebSocket broadcaster. Pushes JSON game state to Phaser spectator clients."
  (:require [org.httpkit.server :as http]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Subscriber Management
;;; ---------------------------------------------------------------------------

(defonce ws-clients (atom #{}))

(defn ws-client-count [] (count @ws-clients))

(defn handle-ws-spectate
  "WebSocket endpoint for Phaser spectator clients.
   Upgrades HTTP to WebSocket, adds to broadcast set."
  [request]
  (http/with-channel request channel
    (if (http/websocket? channel)
      (do
        (swap! ws-clients conj channel)
        (log/info :ws-connect :clients (ws-client-count))
        (http/on-close channel
                       (fn [_status]
                         (swap! ws-clients disj channel)
                         (log/info :ws-disconnect :clients (ws-client-count))))
        (http/on-receive channel
                         (fn [_data]
                           ;; Spectators don't send commands, ignore
                           nil)))
      ;; Not a WebSocket upgrade — return 400
      {:status 400 :body "WebSocket upgrade required"})))

;;; ---------------------------------------------------------------------------
;;; State Broadcast
;;; ---------------------------------------------------------------------------

(defn- state->json
  "Convert game state to JSON for Phaser spectator.
   Sends full state including players, enemies, map."
  [game-state]
  (let [players (->> (:players game-state)
                     (map (fn [[id p]]
                            {:id id
                             :name (:name p)
                             :x (:x p)
                             :y (:y p)
                             :hp (:hp p)
                             :alive (:alive? p)
                             :score (:score p)
                             :has-passenger (some? (:passenger p))
                             :ammo (:ammo p)}))
                     vec)
        enemies (->> (or (:enemies game-state) {})
                     (map (fn [[id e]]
                            {:id id
                             :x (:x e)
                             :y (:y e)
                             :hp (:hp e)
                             :max-hp (:max-hp e)
                             :type (name (:type e))}))
                     vec)
        passengers (->> (:passengers game-state)
                        (filter #(nil? (:picked-up-by %)))
                        (map #(select-keys % [:id :x :y :dest]))
                        vec)]
    (json/write-str
     {:type "state"
      :tick (:tick game-state)
      :players players
      :enemies enemies
      :passengers passengers
      :map {:width (get-in game-state [:map :width])
            :height (get-in game-state [:map :height])}
      :recent-shots (:recent-shots game-state)})))

(defn broadcast-state!
  "Push game state JSON to all connected WebSocket spectators."
  [game-state]
  (when (pos? (ws-client-count))
    (let [payload (state->json game-state)]
      (doseq [ch @ws-clients]
        (try
          (http/send! ch payload)
          (catch Exception e
            (log/warn :ws-send-error :msg (.getMessage e))
            (swap! ws-clients disj ch)))))))

;;; ---------------------------------------------------------------------------
;;; Tick Hook
;;; ---------------------------------------------------------------------------

(defn on-tick
  "Called by engine after each tick. Pushes state to WebSocket spectators.
   Can be composed with sse/on-tick."
  [_sys game-state]
  (broadcast-state! game-state))
