(ns game.sse
  "Datastar SSE broadcaster. Pushes live HTML fragments to spectator browsers."
  (:require [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [hiccup2.core :as h]
            [game.views :as views]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Subscriber Management
;;; ---------------------------------------------------------------------------

(defonce subscribers (atom #{}))

(defn subscriber-count [] (count @subscribers))

(defn handle-spectate
  "SSE endpoint — browser connects once, stays open.
   Pushes initial state immediately so spectators don't see 'Waiting for game data'."
  [request]
  (hk/->sse-response request
                     {hk/on-open
                      (fn [sse-gen]
                        (swap! subscribers conj sse-gen)
                        (log/info :sse-connect :subscribers (count @subscribers))
       ;; Push current state immediately to this new subscriber
                        (try
                          (when-let [sys @game.engine/system]
                            (let [state @(:game-state sys)
                                  events @(:event-log sys)
                                  html-map (str (h/html (views/game-map-fragment state events)))
                                  html-sb (str (h/html (views/scoreboard-fragment state)))
                                  html-ef (str (h/html (views/event-feed-fragment events)))
                                  html-info (str (h/html (views/game-info-fragment state)))]
                              (d*/patch-elements! sse-gen html-map {d*/selector "#game-map" d*/patch-mode d*/pm-inner})
                              (d*/patch-elements! sse-gen html-sb {d*/selector "#scoreboard" d*/patch-mode d*/pm-inner})
                              (d*/patch-elements! sse-gen html-ef {d*/selector "#event-feed" d*/patch-mode d*/pm-inner})
                              (d*/patch-elements! sse-gen html-info {d*/selector "#game-info" d*/patch-mode d*/pm-inner})))
                          (catch Exception e
                            (log/warn :sse-initial-push-error :msg (.getMessage e)))))

                      hk/on-close
                      (fn [sse-gen _status]
                        (swap! subscribers disj sse-gen)
                        (log/info :sse-disconnect :subscribers (count @subscribers)))}))

;;; ---------------------------------------------------------------------------
;;; Push Helpers
;;; ---------------------------------------------------------------------------

(defn- push-to-all!
  "Push an SSE patch to all subscribers. Cleans up dead connections."
  [push-fn]
  (let [dead (atom #{})]
    (doseq [sub @subscribers]
      (try
        (push-fn sub)
        (catch Exception _
          (swap! dead conj sub))))
    (when (seq @dead)
      (swap! subscribers #(apply disj % @dead)))))

(defn push-fragment!
  "Push an HTML fragment to all spectators."
  [selector hiccup-tree & {:keys [mode] :or {mode :inner}}]
  (let [html-str (str (h/html hiccup-tree))]
    (push-to-all!
     (fn [sub]
       (d*/patch-elements! sub html-str
                           {d*/selector selector
                            d*/patch-mode (if (= mode :outer)
                                            d*/pm-outer
                                            d*/pm-inner)})))))

(defn push-signals!
  "Push signal values to all spectators."
  [signals-str]
  (push-to-all!
   (fn [sub]
     (d*/patch-signals! sub signals-str))))

;;; ---------------------------------------------------------------------------
;;; Game-Specific Pushes
;;; ---------------------------------------------------------------------------

(defn push-game-state!
  "Push full spectator view: map + scoreboard + event feed.
   tick-events: current tick only (for tracers/animations).
   all-events: full history (for event feed display)."
  [game-state tick-events all-events]
  (when (pos? (subscriber-count))
    (push-fragment! "#game-map"
                    (views/game-map-fragment game-state tick-events))
    (push-fragment! "#scoreboard"
                    (views/scoreboard-fragment game-state))
    (push-fragment! "#event-feed"
                    (views/event-feed-fragment all-events))
    (push-fragment! "#game-info"
                    (views/game-info-fragment game-state))))

(defn on-tick
  "Called by engine after each tick. Pushes state to all spectators.
   Current-tick events go to the map (tracers/animations).
   Full event history goes to the event feed (persistent kill feed)."
  [_sys game-state]
  (let [all-events @(:event-log _sys)
        current-tick (:tick game-state)
        ;; Events for the current tick only (for tracers/animations)
        tick-events (filter #(or (= (:tick %) current-tick)
                                 (= (:tick %) (dec current-tick))
                                 (nil? (:tick %)))
                            all-events)]
    (push-game-state! game-state tick-events all-events)))

(defn reload-browsers!
  "Push a browser reload to all connected spectators.
   Call from REPL after changing CSS/views: (sse/reload-browsers!)"
  []
  (push-to-all!
   (fn [sub]
     (d*/execute-script! sub "location.reload()")))
  (log/info :browser-reload :subscribers (subscriber-count)))
