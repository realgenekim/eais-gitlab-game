(ns game.server
  "HTTP server — REST API + spectator SSE. Delegates state to game.engine."
  (:require [game.core :as core]
            [game.maps :as maps]
            [game.engine :as engine]
            [game.sse :as sse]
            [game.views :as views]
            [org.httpkit.server :as http]
            [reitit.ring :as reitit]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ring.middleware.params :refer [wrap-params]]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Game State (mutable world)
;;; ---------------------------------------------------------------------------

(defonce game-state (atom nil))
(defonce recorder (atom nil))
(defonce command-queue (atom [])) ;; commands pending for next tick
(defonce token->player (atom {})) ;; token → player-id lookup
(defonce game-timer (atom nil))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn parse-json-body
  "Parse JSON request body into a map with string keys."
  [request]
  (when-let [body (:body request)]
    (try
      (cond
        (string? body) (json/read-str body)
        (instance? java.io.InputStream body)
        (json/read-str (slurp body))
        :else nil)
      (catch Exception _ nil))))

(defn wrap-json [handler]
  (fn [request]
    (let [request (if (and (some-> (get-in request [:headers "content-type"])
                                   (str/includes? "json"))
                           (:body request))
                    (assoc request :body (parse-json-body request))
                    request)]
      (handler request))))

(defn authenticate [request]
  (let [token (or (get-in request [:headers "authorization"])
                  (get-in request [:query-params "token"])
                  (get-in request [:params "token"])
                  (get-in request [:body "token"]))]
    (get @token->player token)))

;;; ---------------------------------------------------------------------------
;;; Route Handlers
;;; ---------------------------------------------------------------------------

(defn handle-join [request]
  (let [body (:body request)
        name (get body "name" "anonymous")
        state @game-state
        max-p (get-in state [:config :max-players] 8)]
    (if (>= (count (:players state)) max-p)
      (json-response 400 {:error "Game is full"})
      (let [[new-state creds] (core/add-player state name)]
        (reset! game-state new-state)
        (swap! token->player assoc (:token creds) (:id creds))
        ;; Update recorder's initial state if tick 0
        (when (zero? (:tick new-state))
          (swap! recorder assoc :initial-state new-state
                 :snapshots {0 new-state}))
        (json-response 200 {:player-id (:id creds)
                            :token (:token creds)
                            :message (str "Welcome, " name "!")})))))

(defn handle-state [request]
  (if-let [player-id (authenticate request)]
    (json-response 200 (core/player-view @game-state player-id))
    (json-response 401 {:error "Invalid token"})))

(defn handle-action [request]
  (if-let [player-id (authenticate request)]
    (let [body (:body request)
          action {:type (keyword (get body "action"))
                  :direction (get body "direction")
                  :angle (get body "angle")
                  :dx (get body "dx")
                  :dy (get body "dy")}]
      (swap! command-queue conj {:player-id player-id :action action})
      (json-response 200 {:status "queued" :tick (:tick @game-state)}))
    (json-response 401 {:error "Invalid token"})))

(defn handle-scoreboard [_request]
  (let [state @game-state
        scores (->> (:players state)
                    (map (fn [[id p]]
                           {:id id
                            :name (:name p)
                            :score (:score p)
                            :alive (:alive? p)
                            :kills 0})) ;; TODO track kills
                    (sort-by :score >))]
    (json-response 200 {:tick (:tick state)
                        :scores scores})))

(defn handle-map [_request]
  (let [state @game-state]
    (json-response 200 {:width (get-in state [:map :width])
                        :height (get-in state [:map :height])
                        :walls (vec (get-in state [:map :walls]))
                        :ascii (maps/render-state-ascii state)})))

(defn handle-status [_request]
  (let [state @game-state]
    (json-response 200
                   {:tick (:tick state)
                    :players (count (:players state))
                    :passengers (count (filter #(nil? (:picked-up-by %))
                                               (:passengers state)))
                    :running (some? @game-timer)})))

(defn handle-ascii [_request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (str (maps/render-state-ascii @game-state) "\n"
              "Tick: " (:tick @game-state) "\n"
              "Scores: "
              (str/join ", "
                        (map (fn [[id p]] (str (:name p) ":" (:score p)))
                             (:players @game-state))))})

;;; ---------------------------------------------------------------------------
;;; Router
;;; ---------------------------------------------------------------------------

(def app
  (reitit/ring-handler
   (reitit/router
    [["/game/join" {:post {:handler #'handle-join}}]
     ["/game/state" {:get {:handler #'handle-state}}]
     ["/game/action" {:post {:handler #'handle-action}}]
     ["/game/scoreboard" {:get {:handler #'handle-scoreboard}}]
     ["/game/map" {:get {:handler #'handle-map}}]
     ["/game/status" {:get {:handler #'handle-status}}]
     ["/game/ascii" {:get {:handler #'handle-ascii}}]])
   (reitit/create-default-handler)
   {:middleware [wrap-params wrap-json]}))

;;; ---------------------------------------------------------------------------
;;; Game Loop
;;; ---------------------------------------------------------------------------

(declare stop-game!)

(defn tick! []
  (let [commands (let [cmds @command-queue]
                   (reset! command-queue [])
                   cmds)
        old-state @game-state
        new-state (core/advance-tick old-state commands)]
    ;; Record for replay
    (replay/record-tick! @recorder (:tick old-state) commands new-state)
    ;; Advance state
    (reset! game-state new-state)
    ;; Check game over
    (when (>= (:tick new-state) (get-in new-state [:config :game-duration-ticks]))
      (println "\n🏁 GAME OVER!")
      (println (maps/render-state-ascii new-state))
      (doseq [[id p] (sort-by (comp :score val) > (:players new-state))]
        (println (format "  %s (%s): %d points" (:name p) id (:score p))))
      (stop-game!))))

(defn start-game!
  "Start the game tick loop."
  ([] (start-game! maps/arena-map))
  ([game-map]
   (let [state (core/make-initial-state game-map)]
     (reset! game-state state)
     (reset! recorder (replay/make-recorder state))
     (reset! command-queue [])
     (reset! token->player {})
     (let [timer (Timer. true)
           tick-ms (get-in state [:config :tick-ms] 500)
           task (proxy [TimerTask] []
                  (run [] (try (tick!)
                               (catch Exception e
                                 (println "Tick error:" (.getMessage e))))))]
       (.scheduleAtFixedRate timer task (long tick-ms) (long tick-ms))
       (reset! game-timer timer)
       (println (str "🎮 Game started! Tick every " tick-ms "ms"))
       (println (maps/render-state-ascii @game-state))))))

(defn stop-game! []
  (when-let [timer @game-timer]
    (.cancel timer)
    (reset! game-timer nil)
    ;; Save replay
    (let [filepath (str "replays/game-" (System/currentTimeMillis) ".jsonl")]
      (io/make-parents filepath)
      (replay/save-recording! @recorder filepath)
      (println (str "💾 Replay saved to " filepath)))))

;;; ---------------------------------------------------------------------------
;;; Main
;;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (start-game!)
  (let [port (Integer/parseInt (or (System/getenv "PORT") "33333"))]
    (http/run-server #'app {:port port})
    (println (str "🚕 Cab Battle server running on http://localhost:" port))
    (println "Endpoints:")
    (println "  POST /game/join        {\"name\": \"your-name\"}")
    (println "  GET  /game/state       ?token=YOUR_TOKEN")
    (println "  POST /game/action      {\"token\": \"X\", \"action\": \"move\", \"direction\": \"north\"}")
    (println "  GET  /game/scoreboard")
    (println "  GET  /game/map")
    (println "  GET  /game/ascii       (live view in terminal)")))
