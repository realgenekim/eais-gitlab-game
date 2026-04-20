(ns game.server
  "HTTP server — REST API + spectator SSE + WebSocket. Delegates state to game.engine."
  (:require [game.core :as core]
            [game.maps :as maps]
            [game.engine :as engine]
            [game.sse :as sse]
            [game.ws :as ws]
            [game.views :as views]
            [game.test-views :as test-views]
            [game.sprite-viewer :as sprite-viewer]
            [org.httpkit.server :as http]
            [reitit.ring :as reitit]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Request Rate Tracking
;;; ---------------------------------------------------------------------------

;; Ring buffer of [timestamp-ms endpoint] for computing requests/sec.
(defonce request-log (atom []))

(def ^:private rate-window-ms
  "How far back to look when computing rates (10 seconds)."
  10000)

(defn record-request!
  "Record an API request for rate tracking."
  [endpoint]
  (let [now (System/currentTimeMillis)]
    (swap! request-log
           (fn [log]
             (let [cutoff (- now (* 2 rate-window-ms))]
               ;; Prune old entries, append new
               (conj (into [] (filter #(> (first %) cutoff)) log)
                     [now endpoint]))))))

(defn compute-rates
  "Returns {:total-rps N :by-endpoint {path rps}} over the last window."
  []
  (let [now (System/currentTimeMillis)
        cutoff (- now rate-window-ms)
        window-secs (/ rate-window-ms 1000.0)
        recent (filter #(> (first %) cutoff) @request-log)
        total (count recent)
        by-ep (frequencies (map second recent))]
    {:total-rps (/ total window-secs)
     :by-endpoint (into (sorted-map)
                        (map (fn [[k v]] [k (/ v window-secs)]))
                        by-ep)
     :window-secs window-secs
     :sample-count total}))

(defn wrap-request-tracking
  "Middleware that records every request for rate computation."
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      ;; Track API and page requests, skip static assets
      (when-not (or (str/starts-with? uri "/css/")
                    (str/starts-with? uri "/sprites/")
                    (str/starts-with? uri "/vendor/")
                    (str/starts-with? uri "/favicon"))
        (record-request! uri))
      (handler request))))

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

(defn- sys [] @engine/system)

(defonce commentary-text (atom {:text "" :timestamp 0}))

;; Signup queue — persistent to file, max 6 contestants for first battle.
(def signup-file "signups.edn")
(def max-signups 6)

(defn load-signups []
  (try
    (if (.exists (clojure.java.io/file signup-file))
      (clojure.edn/read-string (slurp signup-file))
      [])
    (catch Exception _ [])))

(defn save-signups! [queue]
  (spit signup-file (pr-str queue)))

(defonce signup-queue (atom (load-signups)))

(defn handle-signup [request]
  (let [body (:body request)
        contestant-name (get body "name" "")]
    (cond
      (or (empty? contestant-name) (> (count contestant-name) 30))
      (json-response 400 {:error "Name must be 1-30 characters"})

      (some #(= (:name %) contestant-name) @signup-queue)
      (json-response 400 {:error (str "'" contestant-name "' is already signed up!")})

      (>= (count @signup-queue) max-signups)
      ;; Waitlist — accept but mark as waitlisted
      (let [entry {:name contestant-name
                   :timestamp (System/currentTimeMillis)
                   :waitlist true}
            new-queue (swap! signup-queue conj entry)]
        (save-signups! new-queue)
        (let [waitlist-pos (count (filter :waitlist new-queue))]
          (log/info :contestant-waitlist :name contestant-name :waitlist-pos waitlist-pos)
          (json-response 200 {:status "waitlisted"
                              :name contestant-name
                              :waitlist-position waitlist-pos
                              :message (str "Battle 1 is full! You're #" waitlist-pos " on the waitlist.")})))

      :else
      (let [entry {:name contestant-name
                   :timestamp (System/currentTimeMillis)}
            new-queue (swap! signup-queue conj entry)]
        (save-signups! new-queue)
        (log/info :contestant-signup :name contestant-name
                  :queue-size (count new-queue))
        (json-response 200 {:status "registered"
                            :name contestant-name
                            :position (count new-queue)
                            :spots-remaining (- max-signups (count new-queue))
                            :queue (mapv :name new-queue)})))))

(defn handle-signup-list [_request]
  (let [all @signup-queue
        contestants (vec (remove :waitlist all))
        waitlist (vec (filter :waitlist all))]
    (json-response 200 {:queue (mapv :name contestants)
                         :waitlist (mapv :name waitlist)
                         :count (count contestants)
                         :waitlist-count (count waitlist)
                         :max max-signups
                         :spots-remaining (max 0 (- max-signups (count contestants)))})))

(defn handle-signup-clear [_request]
  (reset! signup-queue [])
  (save-signups! [])
  (json-response 200 {:status "cleared"}))

(defn authenticate [request]
  (let [token (or (get-in request [:headers "authorization"])
                  (get-in request [:query-params "token"])
                  (get-in request [:params "token"])
                  (get-in request [:body "token"]))]
    (engine/authenticate (sys) token)))

;;; ---------------------------------------------------------------------------
;;; Route Handlers — Bot API
;;; ---------------------------------------------------------------------------

(defn handle-join [request]
  (let [body (:body request)
        player-name (get body "name" "anonymous")
        result (engine/add-player! (sys) player-name)]
    (if (:error result)
      (json-response 400 result)
      (do
        (when-not (:reconnected result)
          (reset! commentary-text
                  {:text (str "A NEW CHALLENGER HAS ENTERED: " (str/upper-case player-name) "!")
                   :timestamp (System/currentTimeMillis)}))
        (json-response 200 {:player-id (:id result)
                            :token (:token result)
                            :reconnected (boolean (:reconnected result))
                            :message (if (:reconnected result)
                                       (str "Reconnected as " player-name ".")
                                       (str "Welcome, " player-name "!"))
                            :gear-catalog (:gear-catalog result)})))))

(defn handle-state [request]
  (if-let [player-id (authenticate request)]
    (let [state (engine/get-state)
          phase (engine/get-phase)]
      (if (not= phase :playing)
        ;; Not playing — tell bot to wait
        (json-response 200 {:phase (name phase)
                            :round (or (:round state) 1)
                            :round-over (boolean (:round-over state))
                            :round-winner (:round-winner state)
                            :message "Waiting for next round. Do not send actions."
                            :you {:alive? false}})
        ;; Playing — normal fog-of-war view
        (let [view (core/player-view state player-id)]
          (json-response 200 (-> view
                                  (assoc :phase "playing")
                                  (assoc :round (or (:round state) 1))
                                  (assoc :round-over (boolean (:round-over state)))
                                  (assoc :round-winner (:round-winner state)))))))
    (json-response 401 {:error "Invalid token"})))

(defn handle-bot-brief [request]
  "Rich status for a bot's AI coding tool — what's happening, what to improve.
   No auth needed — just pass ?name=BotName. Returns tactical advice."
  (let [params (:query-params request)
        bot-name (get params "name")
        state (engine/get-state)
        phase (name (engine/get-phase))
        players (:players state)
        player-entry (first (filter (fn [[_ p]] (= (:name p) bot-name)) players))
        enemies (or (:enemies state) {})
        scoreboard (->> players
                        (map (fn [[id p]] {:name (:name p) :score (:score p)
                                           :alive (:alive? p) :hp (:hp p)}))
                        (sort-by :score >)
                        vec)]
    (if-not player-entry
      (json-response 404 {:error (str "Bot '" bot-name "' not found")
                          :available-players (vec (map :name (vals players)))})
      (let [[pid player] player-entry
            px (:x player) py (:y player)
            nearby-enemies (->> enemies
                                (filter (fn [[_ e]]
                                          (<= (core/manhattan-distance
                                               [px py] [(:x e) (:y e)]) 6)))
                                (map (fn [[id e]]
                                       {:type (name (:type e)) :hp (:hp e)
                                        :distance (core/manhattan-distance
                                                   [px py] [(:x e) (:y e)])
                                        :direction (cond
                                                     (< (:x e) px) "west"
                                                     (> (:x e) px) "east"
                                                     (< (:y e) py) "north"
                                                     :else "south")}))
                                vec)
            nearby-players (->> players
                                (remove (fn [[id _]] (= id pid)))
                                (filter (fn [[_ p]]
                                          (and (:alive? p)
                                               (<= (core/manhattan-distance
                                                    [px py] [(:x p) (:y p)]) 8))))
                                (map (fn [[_ p]]
                                       {:name (:name p) :score (:score p)
                                        :hp (:hp p)
                                        :distance (core/manhattan-distance
                                                   [px py] [(:x p) (:y p)])
                                        :direction (cond
                                                     (< (:x p) px) "west"
                                                     (> (:x p) px) "east"
                                                     (< (:y p) py) "north"
                                                     :else "south")}))
                                vec)
            rank (inc (.indexOf (mapv :name scoreboard) bot-name))
            {:keys [width height]} (:map state)
            edge-danger (or (< px 3) (> px (- width 4))
                            (< py 3) (> py (- height 4)))]
        (json-response 200
                       {:bot bot-name
                        :phase phase
                        :tick (:tick state)
                        :wave (or (:wave-number state) 0)
                        :rank rank
                        :rank-of (count players)
                        :you {:x px :y py
                              :hp (:hp player)
                              :alive (:alive? player)
                              :score (:score player)
                              :ammo (:ammo player)
                              :gear (vec (map name (or (:gear player) [])))}
                        :scoreboard scoreboard
                        :nearby-enemies nearby-enemies
                        :nearby-rivals nearby-players
                        :total-enemies (count enemies)
                        :arena-shrinking (>= (:tick state)
                                             (get-in state [:config :shrink-start] 200))
                        :near-edge edge-danger
                        :tips (cond-> []
                                (< (:hp player) 200)
                                (conj "HP is low! Add flee logic when hp < 200")
                                (zero? (:ammo player))
                                (conj "Out of ammo! Move away from threats until it regens")
                                edge-danger
                                (conj "Near the edge! The arena shrinks — move toward center")
                                (> (count nearby-enemies) 3)
                                (conj "Surrounded by enemies! Consider adding dodge/flee behavior")
                                (empty? nearby-enemies)
                                (conj "No enemies nearby — good time to do missions for +100 pts")
                                (seq nearby-players)
                                (conj (str "Rival nearby: " (:name (first nearby-players))
                                           " — kill for +150 pts!"))
                                (> rank 1)
                                (conj (str "You're rank " rank " — leader has "
                                           (:score (first scoreboard)) " pts"))
                                ;; Breadcrumb for secret items
                                (>= (:tick state) 50)
                                (conj "The arena whispers of hidden power... GET /game/secrets"))})))))

(defn handle-action [request]
  (let [state (engine/get-state)
        phase (engine/get-phase)]
    ;; Reject actions when round is over or not playing
    (if (:round-over state)
      (json-response 200 {:status "round-over"
                          :winner (:round-winner state)
                          :round (:round state)
                          :message (str "Round " (:round state) " is over! "
                                        (:round-winner state) " won. Waiting for next round.")})
      (if (not= phase :playing)
        (json-response 200 {:status "waiting"
                            :phase (name phase)
                            :message "Game is not active. Waiting in lobby."})
        (if-let [player-id (authenticate request)]
          (let [body (:body request)
                action {:type (keyword (get body "action"))
                        :direction (get body "direction")
                        :angle (get body "angle")
                        :dx (get body "dx")
                        :dy (get body "dy")}]
            (engine/enqueue-command! (sys) player-id action)
            (json-response 200 {:status "queued" :tick (:tick state)}))
          (json-response 401 {:error "Invalid token"}))))))

(defn handle-scoreboard [_request]
  (let [state (engine/get-state)
        scores (->> (:players state)
                    (map (fn [[id p]]
                           {:id id
                            :name (:name p)
                            :score (:score p)
                            :alive (:alive? p)
                            :kills 0}))
                    (sort-by :score >))]
    (json-response 200 {:tick (:tick state)
                        :scores scores})))

(defn handle-map [_request]
  (let [state (engine/get-state)]
    (json-response 200 {:width (get-in state [:map :width])
                        :height (get-in state [:map :height])
                        :walls (vec (get-in state [:map :walls]))
                        :ascii (maps/render-state-ascii state)})))

(defn handle-status [_request]
  (let [state (engine/get-state)]
    (json-response 200
                   {:tick (:tick state)
                    :phase (name (engine/get-phase))
                    :round (or (:round state) 1)
                    :round-over (boolean (:round-over state))
                    :round-winner (:round-winner state)
                    :players (count (:players state))
                    :player-names (vec (map :name (vals (:players state))))
                    :passengers (count (filter #(nil? (:picked-up-by %))
                                               (:passengers state)))
                    :spectators (sse/subscriber-count)
                    :running (some? @(:game-timer (sys)))})))

(defn handle-ascii [_request]
  (let [state (engine/get-state)]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (str (maps/render-state-ascii state) "\n"
                "Tick: " (:tick state) "\n"
                "Scores: "
                (str/join ", "
                          (map (fn [[id p]] (str (:name p) ":" (:score p)))
                               (:players state))))}))

;;; ---------------------------------------------------------------------------
;;; Route Handlers — Spectator
;;; ---------------------------------------------------------------------------

(defn handle-spectator-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (views/spectator-page)})

(defn handle-spectate [request]
  (sse/handle-spectate request))

(defn handle-map-swap [request]
  (let [body (:body request)
        map-id (get body "map" "arena")
        game-map (maps/get-map-by-id map-id)]
    (engine/swap-map! (sys) game-map)
    (sse/reload-browsers!)
    (json-response 200 {:status "map-swapped" :map map-id})))

(declare on-tick-all)

(defn handle-restart [request]
  (let [body (:body request)
        map-id (get body "map" "arena")
        game-map (maps/get-map-by-id map-id)]
    (require 'game.bots)
    ((resolve 'game.bots/stop-all-bots!))
    (engine/stop-game!)
    (reset! ws/avatar-store {})
    (engine/start-game! {:on-tick #'on-tick-all
                         :game-map game-map})
    (json-response 200 {:status "restarted" :map map-id :tick 0})))

(defn handle-add-bot [request]
  (let [body (:body request)
        bot-name (get body "name" (str "Bot-" (rand-int 9999)))]
    (require 'game.bots)
    (if-let [creds ((resolve 'game.bots/add-vs-bot!) bot-name)]
      (json-response 200 {:status "bot-added"
                          :name bot-name
                          :player-id (:id creds)})
      (json-response 400 {:error "Game is full"}))))

(defn handle-frame [request]
  (let [tick-str (get-in request [:query-params "tick"])
        tick (when tick-str (Integer/parseInt tick-str))]
    (if tick
      (if-let [frame (ws/get-frame tick)]
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Access-Control-Allow-Origin" "*"}
         :body frame}
        (json-response 404 {:error "Frame not found" :tick tick
                            :available (count @ws/frame-buffer)}))
      ;; No tick specified — return latest + frame count
      (let [buf @ws/frame-buffer
            latest-tick (when (seq buf) (apply max (keys buf)))]
        (json-response 200 {:latest-tick latest-tick
                            :frame-count (count buf)
                            :oldest-tick (when (seq buf) (apply min (keys buf)))})))))

(defn handle-lightning [_request]
  (let [result (engine/trigger-lightning! (sys))]
    (json-response 200 {:status "lightning"
                        :x (:x result)
                        :y (:y result)
                        :radius (:radius result)})))

(defn handle-seek [request]
  (let [body (:body request)
        tick (get body "tick" 0)]
    (let [result (engine/seek-to-tick! (sys) tick)]
      (json-response 200 {:status "seeked"
                          :tick (:tick result)
                          :max-tick (:max-tick result)}))))

(defn handle-gear-catalog [_request]
  (let [state (engine/get-state)]
    (json-response 200 {:gear (core/available-gear state)})))

(defn handle-gear-select [request]
  (let [body (:body request)
        ;; Accept auth token OR player-id in body
        player-id (or (authenticate request)
                      (get body "player-id"))
        item-key (keyword (get body "item"))]
    (if-not player-id
      (json-response 400 {:error "Pass 'player-id' or Authorization token"})
      (let [state (engine/get-state)
            [new-state success? msg] (core/select-gear state player-id item-key)]
        (if success?
          (let [player-name (get-in new-state [:players player-id :name] "Unknown")
                item-name (get-in core/armory-items [item-key :name] (name item-key))]
            (reset! (:game-state (sys)) new-state)
            ;; Announce gear acquisition
            (swap! (:game-state (sys))
                   update :bot-updates conj
                   {:name player-name
                    :description (str "equipped " item-name "!")
                    :tick (:tick new-state)
                    :timestamp (System/currentTimeMillis)})
            ;; Push to spectators
            (when-let [on-tick (:on-tick (sys))]
              (let [s @(:game-state (sys))]
                (if (var? on-tick) (@on-tick (sys) s) (on-tick (sys) s))))
            (json-response 200 {:status "equipped"
                                :item (name item-key)
                                :item-name item-name
                                :message msg
                                :gear-catalog (core/available-gear new-state)}))
          (json-response 400 {:error msg
                              :gear-catalog (core/available-gear state)}))))))

(defn handle-agent-context [request]
  (let [md-content (slurp (clojure.java.io/resource "public/guide/agent-context.md"))
        accept (or (get-in request [:headers "accept"]) "")]
    (if (str/includes? accept "text/html")
      ;; Browser request — serve rendered HTML page that loads marked.js
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (str "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                  "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                  "<title>Bot Battle Arena - Agent Context</title>"
                  "<script src='https://cdn.jsdelivr.net/npm/marked/marked.min.js'></script>"
                  "<link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/github-markdown-css@5/github-markdown-dark.min.css'>"
                  "<style>body{background:#0d1117;padding:40px 20px;}"
                  ".markdown-body{max-width:900px;margin:0 auto;}"
                  "</style></head><body>"
                  "<article class='markdown-body' id='c'></article>"
                  "<script>var t=" (json/write-str md-content) ";"  ; safe: json-escaped
                  "document.getElementById('c').innerHTML=marked.parse(t);</script>"  ; marked.js sanitizes by default
                  "</body></html>")}
      ;; Agent/curl — raw plain text
      {:status 200
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body md-content})))

(defn handle-bot-update [request]
  "Bot announces it has been updated (hot-reload). Shows on spectator.
   Pass player-id or name in body — no auth token needed."
  (let [body (:body request)
        state (engine/get-state)
        ;; Accept player-id or name
        player-id (get body "player-id")
        bot-name (or (get body "name")
                     (when player-id
                       (get-in state [:players player-id :name])))
        description (get body "description" "updated strategy")]
    (if bot-name
      (do
        (swap! (:game-state (sys))
               update :bot-updates conj
               {:name bot-name
                :description description
                :tick (:tick state)
                :timestamp (System/currentTimeMillis)})
        ;; Push state so spectator sees it
        (when-let [on-tick (:on-tick (sys))]
          (let [s @(:game-state (sys))]
            (if (var? on-tick) (@on-tick (sys) s) (on-tick (sys) s))))
        (json-response 200 {:status "update-announced"
                            :name bot-name
                            :description description}))
      (json-response 400 {:error "Pass 'name' or 'player-id' in body"}))))

(defn handle-next-round [_request]
  "Reset all players for the next round. Keep scores, advance round number."
  (let [state @(:game-state (sys))
        current-round (or (:round state) 1)]
    (if (>= current-round 3)
      (json-response 400 {:error "All 3 rounds complete!"
                          :final-scores (->> (:players state)
                                             (map (fn [[_ p]] {:name (:name p) :score (:score p)}))
                                             (sort-by :score >)
                                             vec)})
      (let [new-round (inc current-round)
            new-state (reduce-kv
                       (fn [s id player]
                         (-> s
                             (assoc-in [:players id :alive?] true)
                             (assoc-in [:players id :hp] 500)
                             (assoc-in [:players id :ammo] 5)))
                       (-> state
                           (assoc :round new-round)
                           (assoc :round-over false)
                           (assoc :round-winner nil)
                           (assoc :tick 0)
                           (assoc :enemies {})
                           (assoc :recent-shots [])
                           (assoc :bot-updates []))
                       (:players state))]
        (reset! (:game-state (sys)) new-state)
        (json-response 200 {:status "next-round" :round new-round})))))

;;; ---------------------------------------------------------------------------
;;; Custom Avatars — contestants upload their own sprite
;;; ---------------------------------------------------------------------------

(defn handle-avatar-upload [request]
  (if-let [player-id (authenticate request)]
    (let [content-type (get-in request [:headers "content-type"] "")
          allowed-types #{"image/png" "image/svg+xml" "image/jpeg" "image/gif"}
          max-size (* 512 1024)]
      (if-not (allowed-types content-type)
        (json-response 400 {:error "Unsupported image type"
                            :allowed (vec allowed-types)})
        (let [body (:body request)
              bytes (cond
                      (instance? java.io.InputStream body)
                      (.readAllBytes ^java.io.InputStream body)
                      (bytes? body) body
                      :else nil)]
          (if (or (nil? bytes) (> (alength bytes) max-size))
            (json-response 400 {:error "Image too large (max 512KB)"})
            (do
              (swap! ws/avatar-store assoc player-id
                     {:content-type content-type :bytes bytes})
              (log/info :avatar-uploaded :player player-id
                        :type content-type :size (alength bytes))
              (json-response 200 {:status "ok" :message "Avatar uploaded!"}))))))
    (json-response 401 {:error "Invalid token"})))

(defn handle-avatar-get [request]
  (let [player-id (get-in request [:path-params :player-id])]
    (if-let [{:keys [content-type bytes]} (get @ws/avatar-store player-id)]
      {:status 200
       :headers {"Content-Type" content-type
                 "Cache-Control" "public, max-age=3600"
                 "Access-Control-Allow-Origin" "*"}
       :body (java.io.ByteArrayInputStream. bytes)}
      (json-response 404 {:error "No avatar"}))))

;;; ---------------------------------------------------------------------------
;;; Secret Items — hidden endpoints for vibe coders who explore the API
;;; ---------------------------------------------------------------------------

(defn handle-secret-equip [item-key request]
  (if-let [player-id (authenticate request)]
    (let [state (engine/get-state)
          [new-state success? msg] (core/equip-secret state player-id item-key)]
      (if success?
        (do
          (reset! (:game-state (sys)) new-state)
          (json-response 200 {:status "unlocked" :message msg}))
        (json-response 400 {:error msg})))
    (json-response 401 {:error "Invalid token"})))

(defn handle-secret-hint [_request]
  (json-response 200 {:message "You found something... but what are you looking for?"
                       :hint "The arena hides power beyond the armory. Try the shadows, the void, the pulse, the mirror, the pull."
                       :whisper "POST with your auth token to claim what's hidden."}))

(defn handle-start [_request]
  (log/info :game-start-requested :phase (engine/get-phase)
            :players (count (:players (engine/get-state))))
  (if-let [result (engine/begin-game!)]
    (do (log/info :game-started-by-api :players (:players result))
        (json-response 200 {:status "started"
                            :players (:players result)}))
    (json-response 400 {:error "Game is not in lobby or armory phase"})))

(defn handle-armory-open [_request]
  (if-let [result (engine/begin-armory!)]
    (json-response 200 {:status "armory-opened"
                        :players (:players result)})
    (json-response 400 {:error "Game is not in lobby phase"})))

(defn handle-armory-get [request]
  (if-let [player-id (authenticate request)]
    (let [state (engine/get-state)
          player (get-in state [:players player-id])
          phase (name (engine/get-phase))]
      (json-response 200
                     {:phase phase
                      :items (into {}
                                   (map (fn [[k v]]
                                          [(name k) (-> v
                                                        (dissoc :effect)
                                                        (assoc :id (name k)))]))
                                   game.core/armory-items)
                      :your-points (:points player 0)
                      :your-items (vec (map name (or (:items player) [])))
                      :your-buffs (or (:buffs player) {})
                      :your-debuffs (or (:debuffs player) {})}))
    (json-response 401 {:error "Invalid token"})))

(defn handle-buy [request]
  (if-let [player-id (authenticate request)]
    (let [body (:body request)
          item-key (keyword (get body "item"))]
      (let [result (engine/buy-armory-item! player-id item-key)]
        (if (:success? result)
          (json-response 200 result)
          (json-response 400 result))))
    (json-response 401 {:error "Invalid token"})))

(defn handle-loadout [request]
  (if-let [player-id (authenticate request)]
    (let [state (engine/get-state)
          player (get-in state [:players player-id])]
      (json-response 200
                     {:points (:points player 0)
                      :items (vec (map name (or (:items player) [])))
                      :buffs (or (:buffs player) {})
                      :debuffs (or (:debuffs player) {})
                      :hp (:hp player)}))
    (json-response 401 {:error "Invalid token"})))

(defn handle-commentary-post [request]
  (let [body (:body request)
        text (get body "text" "")]
    (reset! commentary-text {:text text :timestamp (System/currentTimeMillis)})
    (json-response 200 {:status "ok"})))

(defn handle-commentary-get [_request]
  (json-response 200 @commentary-text))

(defn handle-resume [_request]
  (engine/resume-game!)
  (json-response 200 {:status "resumed"}))

(defn handle-sprite-viewer [request]
  (let [selected (get-in request [:query-params "sprite"])
        frame-str (get-in request [:query-params "frame"])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (sprite-viewer/sprite-viewer-page selected frame-str)}))

(defn handle-armory-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (clojure.java.io/resource "public/armory.html"))})

(defn handle-armory-state [_request]
  "Full armory state for the spectator/test view (no auth needed)."
  (let [state (engine/get-state)
        phase (name (engine/get-phase))
        players (->> (:players state)
                     (map (fn [[id p]]
                            {:id id
                             :name (:name p)
                             :points (:points p 0)
                             :items (vec (map name (or (:items p) [])))
                             :gear (vec (map name (or (:gear p) [])))
                             :buffs (or (:buffs p) {})
                             :debuffs (or (:debuffs p) {})
                             :hp (:hp p)
                             :alive (:alive? p)}))
                     vec)]
    (json-response 200
                   {:phase phase
                    :tick (:tick state)
                    :players players
                    :shop (into {}
                                (map (fn [[k v]]
                                       [(name k) (assoc v :id (name k))]))
                                game.core/armory-items)
                    :crates (vec (map (fn [[_ c]]
                                        {:id (:id c) :x (:x c) :y (:y c)
                                         :tier (name (:tier c)) :cost (:cost c)})
                                      (or (:crates state) {})))})))

(defn handle-server-stats [_request]
  (let [state (engine/get-state)
        rates (compute-rates)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (views/server-stats-page state rates
                                    (sse/subscriber-count)
                                    (ws/ws-client-count))}))

(defn handle-armory-demo
  "Pure armory demo — no server state, no bots, no mocks.
   Builds a synthetic game state from pure functions and returns it
   in the same shape as /game/armory/state.

   Modes:
     ?scenario=N  — static snapshots (0=fresh, 1=mid, 2=loaded)
     ?timeline&step=N — scripted purchase sequence, one buy per step"
  [request]
  (let [params (:query-params request)
        timeline? (contains? params "timeline")
        ;; Build base state with 4 players
        base-state (core/make-initial-state maps/arena-map)
        [base-state c1] (core/add-player base-state "Rick-Alpha")
        [base-state c2] (core/add-player base-state "Rick-Beta")
        [base-state c3] (core/add-player base-state "Rick-Gamma")
        [base-state c4] (core/add-player base-state "Rick-Delta")
        ids [(:id c1) (:id c2) (:id c3) (:id c4)]
        base-state (reduce (fn [s id] (assoc-in s [:players id :points] core/START-POINTS))
                           base-state ids)
        ;; Timeline: scripted purchase sequence
        timeline-steps
        [;; step 0: everyone fresh
         [nil nil "Everyone joins with 30 points"]
         ;; step 1-8: purchases
         [0 :plasma-rounds "Rick-Alpha buys Plasma Rounds!"]
         [1 :titan-shield "Rick-Beta buys Titan Shield!"]
         [2 :sprint-boots "Rick-Gamma buys Sprint Boots!"]
         [0 :ammo-belt "Rick-Alpha buys Ammo Belt!"]
         [1 :juggernaut "Rick-Beta buys Juggernaut!"]
         [3 :oracle-eye "Rick-Delta buys Oracle Eye!"]
         [2 :vampiric-rounds "Rick-Gamma buys Vampiric Rounds!"]
         [0 :titan-shield "Rick-Alpha buys Titan Shield!"]]
        ;; Resolve state + event
        [state phase event]
        (if timeline?
          (let [step (min (Integer/parseInt (or (get params "frame") "0"))
                          (count timeline-steps))
                applied (take (inc step) timeline-steps)
                state (reduce (fn [s [player-idx item _]]
                                (if item
                                  (let [[s' _] (core/buy-item s (nth ids player-idx) item)] s')
                                  s))
                              base-state applied)
                [_ _ evt] (nth timeline-steps (min step (dec (count timeline-steps))))]
            [state
             (if (>= step (count timeline-steps)) "reveal" "armory")
             evt])
          ;; Static scenario mode
          (let [scenario (Integer/parseInt (or (get params "scenario") "0"))
                state (case scenario
                        0 base-state
                        1 (let [[s _] (core/buy-item base-state (nth ids 0) :plasma-rounds)
                                [s _] (core/buy-item s (nth ids 0) :ammo-belt)
                                [s _] (core/buy-item s (nth ids 1) :titan-shield)
                                [s _] (core/buy-item s (nth ids 2) :sprint-boots)
                                [s _] (core/buy-item s (nth ids 2) :vampiric-rounds)]
                            s)
                        2 (let [[s _] (core/buy-item base-state (nth ids 0) :plasma-rounds)
                                [s _] (core/buy-item s (nth ids 0) :titan-shield)
                                [s _] (core/buy-item s (nth ids 0) :ammo-belt)
                                [s _] (core/buy-item s (nth ids 1) :juggernaut)
                                [s _] (core/buy-item s (nth ids 1) :oracle-eye)
                                [s _] (core/buy-item s (nth ids 2) :sprint-boots)
                                [s _] (core/buy-item s (nth ids 2) :cluster-shot)
                                s (assoc-in s [:players (nth ids 3) :debuffs :drunk-controls]
                                            {:expires-at 50 :name "Drunk Controls"})
                                s (core/spawn-crate s :gold)
                                s (core/spawn-crate s :silver)
                                s (core/spawn-crate s :purple)]
                            s)
                        base-state)
                _ nil]
            [state "armory" nil]))
        ;; Build response — SAME SHAPE as handle-armory-state (items as strings!)
        players (->> (:players state)
                     (map (fn [[id p]]
                            {:id id :name (:name p)
                             :points (:points p 0)
                             :items (vec (map name (or (:items p) [])))
                             :buffs (or (:buffs p) {})
                             :debuffs (or (:debuffs p) {})
                             :hp (:hp p) :score (:score p) :alive (:alive? p)}))
                     vec)
        shop (into {}
                   (map (fn [[k v]] [(name k) (assoc v :id (name k))]))
                   core/armory-items)
        crates (->> (or (:crates state) {})
                    (map (fn [[id c]] {:id id :x (:x c) :y (:y c)
                                       :tier (name (:tier c)) :cost (:cost c)}))
                    vec)]
    (json-response 200 (cond-> {:phase phase
                                :tick (:tick state)
                                :players players
                                :shop shop
                                :crates crates}
                         timeline? (assoc :step (Integer/parseInt (or (get params "frame") "0"))
                                          :total-steps (dec (count timeline-steps))
                                          :event event)))))

(defn handle-test [request]
  (let [params (:query-params request)
        scenario (Integer/parseInt (or (get params "scenario") "0"))
        frame (Integer/parseInt (or (get params "frame") "0"))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (test-views/test-page scenario frame)}))

;;; ---------------------------------------------------------------------------
;;; CORS + Router
;;; ---------------------------------------------------------------------------

(defn wrap-cors
  "Allow cross-origin requests from Phaser spectator client."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"}}
      (let [response (handler request)]
        (update response :headers merge
                {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"})))))

(def app
  (-> (reitit/ring-handler
       (reitit/router
        [;; Bot API
         ["/game/join" {:post {:handler #'handle-join}}]
         ["/game/state" {:get {:handler #'handle-state}}]
         ["/game/action" {:post {:handler #'handle-action}}]
         ["/game/scoreboard" {:get {:handler #'handle-scoreboard}}]
         ["/game/map" {:get {:handler #'handle-map}}]
         ["/game/status" {:get {:handler #'handle-status}}]
         ["/game/ascii" {:get {:handler #'handle-ascii}}]
         ;; Spectator
         ["/" {:get {:handler (fn [_] (resp/redirect "/spectator/index.html"))}}]
         ["/spectate" {:get {:handler #'handle-spectate}}]
         ["/spectate-ws" {:get {:handler #'ws/handle-ws-spectate}}]
         ["/game/restart" {:post {:handler #'handle-restart}}]
         ["/game/add-bot" {:post {:handler #'handle-add-bot}}]
         ["/game/frame" {:get {:handler #'handle-frame}}]
         ["/game/map-swap" {:post {:handler #'handle-map-swap}}]
         ["/game/lightning" {:post {:handler #'handle-lightning}}]
         ["/signup" {:post {:handler #'handle-signup}
                    :get {:handler #'handle-signup-list}}]
         ["/signup/clear" {:post {:handler #'handle-signup-clear}}]
         ["/agent-context" {:get {:handler #'handle-agent-context}}]
         ["/tips" {:get {:handler (fn [req]
                                    (let [md (slurp (clojure.java.io/resource "public/guide/TIPS.md"))
                                          accept (or (get-in req [:headers "accept"]) "")]
                                      (if (str/includes? accept "text/html")
                                        {:status 200
                                         :headers {"Content-Type" "text/html; charset=utf-8"}
                                         :body (str "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Vibe Battle Tips</title><script src='https://cdn.jsdelivr.net/npm/marked/marked.min.js'></script><link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/github-markdown-css@5/github-markdown-dark.min.css'><style>body{background:#0d1117;padding:40px 20px;}.markdown-body{max-width:900px;margin:0 auto;}</style></head><body><article class='markdown-body' id='c'></article><script>document.getElementById('c').innerHTML=marked.parse(" (json/write-str md) ");</script></body></html>")}
                                        {:status 200
                                         :headers {"Content-Type" "text/plain; charset=utf-8"}
                                         :body md})))}}]
         ["/download/bot" {:get {:handler (fn [_] {:status 200
                                                    :headers {"Content-Type" "text/plain; charset=utf-8"}
                                                    :body (slurp (clojure.java.io/resource "public/guide/bot.py"))})}}]
         ["/download/brain" {:get {:handler (fn [_] {:status 200
                                                      :headers {"Content-Type" "text/plain; charset=utf-8"}
                                                      :body (slurp (clojure.java.io/resource "public/guide/brain.py"))})}}]
         ["/download/loadout" {:get {:handler (fn [_] {:status 200
                                                        :headers {"Content-Type" "text/plain; charset=utf-8"}
                                                        :body (slurp (clojure.java.io/resource "public/guide/loadout.py"))})}}]
         ["/guide.html" {:get {:handler (fn [_]
                                           {:status 200
                                            :headers {"Content-Type" "text/html"}
                                            :body (slurp (clojure.java.io/resource "public/guide/index.html"))})}}]
         ["/game/brief" {:get {:handler #'handle-bot-brief}}]
         ["/game/bot-update" {:post {:handler #'handle-bot-update}}]
         ["/game/gear" {:get {:handler #'handle-gear-catalog}}]
         ["/game/gear/select" {:post {:handler #'handle-gear-select}}]
         ["/game/start" {:post {:handler #'handle-start}}]
         ["/game/armory-open" {:post {:handler #'handle-armory-open}}]
         ["/game/armory" {:get {:handler #'handle-armory-get}}]
         ["/game/armory/state" {:get {:handler #'handle-armory-state}}]
         ["/game/buy" {:post {:handler #'handle-buy}}]
         ["/game/loadout" {:get {:handler #'handle-loadout}}]
         ["/armory" {:get {:handler #'handle-armory-page}}]
         ["/game/armory/demo" {:get {:handler #'handle-armory-demo}}]
         ["/game/commentary" {:get {:handler #'handle-commentary-get}
                              :post {:handler #'handle-commentary-post}}]
         ["/game/seek" {:post {:handler #'handle-seek}}]
         ["/game/resume" {:post {:handler #'handle-resume}}]
         ;; Custom avatar upload/serve
         ["/game/avatar" {:post {:handler #'handle-avatar-upload}}]
         ["/game/avatar/:player-id" {:get {:handler #'handle-avatar-get}}]
         ;; Secret items — hidden endpoints, not in docs
         ["/game/secrets" {:get {:handler #'handle-secret-hint}}]
         ["/game/shadow"  {:post {:handler (fn [r] (handle-secret-equip :phase-cloak r))}}]
         ["/game/void"    {:post {:handler (fn [r] (handle-secret-equip :teleporter r))}}]
         ["/game/pulse"   {:post {:handler (fn [r] (handle-secret-equip :emp-blast r))}}]
         ["/game/mirror"  {:post {:handler (fn [r] (handle-secret-equip :shadow-clone r))}}]
         ["/game/pull"    {:post {:handler (fn [r] (handle-secret-equip :gravity-well r))}}]
         ;; Tools
         ["/server-stats" {:get {:handler #'handle-server-stats}}]
         ["/test" {:get {:handler #'handle-test}}]
         ["/sprite-viewer" {:get {:handler #'handle-sprite-viewer}}]
         ;; Dev reload endpoint (browser-reload polls this)
         ["/dev/reload-check" {:get {:handler (fn [_]
                                                (if-let [handler (resolve 'browser-reload.core/reload-check-handler)]
                                                  (handler _)
                                                  {:status 200 :body "0"}))}}]])
       (reitit/create-default-handler)
       {:middleware [wrap-params wrap-json]})
      (wrap-resource "public")
      wrap-content-type
      wrap-request-tracking
      wrap-cors))

;;; ---------------------------------------------------------------------------
;;; Main
;;; ---------------------------------------------------------------------------

(defn- make-dev-app
  "Wrap app with code-reload + browser-reload for dev mode."
  []
  (let [wrap-reload-script (requiring-resolve 'browser-reload.core/wrap-reload-script)
        wrap-reload (requiring-resolve 'ring.middleware.reload/wrap-reload)]
    (-> #'app
        wrap-reload-script
        (wrap-reload {:dirs ["src" "resources"]
                      :reload-compile-errors? true}))))

(defn on-tick-all
  "Composed on-tick: pushes to both SSE (HTML) and WebSocket (JSON) spectators."
  [sys game-state]
  (sse/on-tick sys game-state)
  (ws/on-tick sys game-state))

(defn -main [& _args]
  (engine/start-game! {:on-tick #'on-tick-all})
  (let [port (Integer/parseInt (or (System/getenv "PORT") "33333"))
        is-dev (= "dev" (System/getenv "ENV"))
        handler (if is-dev (make-dev-app) #'app)]
    (when is-dev
      (when-let [start-watcher (requiring-resolve 'browser-reload.core/start-file-watcher!)]
        (start-watcher ["src" "resources"] #{"clj" "css" "js" "html" "edn"}))
      (log/info :dev-mode :reload true :browser-reload true))
    (http/run-server handler {:port port})
    (log/info :server-started :port port
              :endpoints ["/game/join" "/game/state" "/game/action"
                          "/game/scoreboard" "/game/map" "/game/ascii"
                          "/" "/spectate" "/spectate-ws"])))
