(ns game.views
  "Hiccup templates for spectator page and live fragments."
  (:require [hiccup2.core :as h]
            [hiccup.page :as page]
            [game.maps :as maps]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Player Colors — Vampire Survivors palette
;;; ---------------------------------------------------------------------------

(def player-colors
  ["#ff6b6b" "#4ecdc4" "#ffe66d" "#a29bfe"
   "#fd79a8" "#00b894" "#fdcb6e" "#6c5ce7"])

(defn player-color [idx]
  (nth player-colors (mod idx (count player-colors))))

;;; ---------------------------------------------------------------------------
;;; Spectator Page (full HTML)
;;; ---------------------------------------------------------------------------

(defn spectator-page []
  (str
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "Cab Battle — Spectator"]
      [:link {:rel "stylesheet" :href "/css/spectator.css"}]
      [:script {:type "module" :src "/vendor/datastar-aliased.js"}]]
     [:body {:data-star-init "@get('/spectate')"}

      [:div.header
       [:h1 "CAB BATTLE"]
       [:div#game-info
        [:span.tick "Tick: 0"]
        [:span.players "Players: 0"]
        [:span.spectators "Spectators: 0"]]]

      [:div.arena
       ;; Main game map — god mode, no fog
       [:div#game-map.map-container
        [:div.loading "Waiting for game data..."]]

       ;; Sidebar
       [:div.sidebar
        ;; Scoreboard
        [:div.panel
         [:h2 "SCOREBOARD"]
         [:div#scoreboard
          [:div.empty "No players yet"]]]

        ;; Event feed (kill feed)
        [:div.panel
         [:h2 "EVENTS"]
         [:div#event-feed
          [:div.empty "Waiting for action..."]]]]]]])))

;;; ---------------------------------------------------------------------------
;;; Map Fragment — the grid (god mode view)
;;; ---------------------------------------------------------------------------

(defn- cell-class [game-state x y player-lookup pax-lookup dest-lookup]
  (cond
    (contains? (get-in game-state [:map :walls]) [x y]) "wall"
    (get player-lookup [x y]) "player"
    (get pax-lookup [x y]) "passenger"
    (get dest-lookup [x y]) "destination"
    :else "open"))

(defn game-map-fragment
  "Render the full game map grid as a spectator god-mode view.
   Events from the current tick drive hit/kill/tracer animations."
  [game-state events]
  (let [{:keys [width height walls]} (:map game-state)
        players (:players game-state)
        passengers (filter #(nil? (:picked-up-by %)) (:passengers game-state))
        directions {:north [0 -1] :south [0 1] :east [1 0] :west [-1 0]}
        ;; Build lookups
        player-list (->> players
                         (map-indexed (fn [idx [id p]]
                                        {:id id :x (:x p) :y (:y p)
                                         :name (:name p) :score (:score p)
                                         :hp (:hp p) :alive (:alive? p)
                                         :color (player-color idx)
                                         :has-passenger (some? (:passenger p))})))
        alive-list (filter :alive player-list)
        player-lookup (into {} (map (fn [p] [[(:x p) (:y p)] p]) alive-list))
        pax-lookup (into {} (map (fn [p] [[(:x p) (:y p)] p]) passengers))
        dest-lookup (into {} (keep (fn [p]
                                     (when-let [pax (:passenger p)]
                                       [[(get-in pax [:dest :x])
                                         (get-in pax [:dest :y])]
                                        {:player-name (:name p)}]))
                                   (map (fn [[_ p]] p) players)))
        ;; Compute bullet tracers with direction + distance for staggered animation
        tracer-map (atom {}) ;; [x y] -> {:dir :east :dist 1 :max-dist 3}
        _ (doseq [evt events]
            (when (and (= :command (:type evt))
                       (= :shoot (get-in evt [:action :type])))
              (let [pid    (:player-id evt)
                    player (get-in game-state [:players pid])
                    dir    (keyword (get-in evt [:action :direction]))
                    [dx dy] (get directions dir [0 0])
                    range- 20]
                (when (and player (not= [dx dy] [0 0]))
                  ;; First pass: find how far the bullet travels
                  (let [max-dist (loop [x (+ (:x player) dx)
                                        y (+ (:y player) dy)
                                        d 1]
                                   (if (or (> d range-)
                                           (not (and (>= x 0) (< x width) (>= y 0) (< y height)))
                                           (contains? walls [x y]))
                                     (dec d)
                                     (if (get player-lookup [x y])
                                       d
                                       (recur (+ x dx) (+ y dy) (inc d)))))]
                    ;; Only render last 3 cells of the tracer (bullet head)
                    (loop [x (+ (:x player) dx)
                           y (+ (:y player) dy)
                           d 1]
                      (when (and (<= d range-)
                                 (>= x 0) (< x width)
                                 (>= y 0) (< y height)
                                 (not (contains? walls [x y])))
                        (when (> d (- max-dist 3))
                          (swap! tracer-map assoc [x y]
                                 {:dir dir :dist d :max-dist max-dist}))
                        (when-not (get player-lookup [x y])
                          (recur (+ x dx) (+ y dy) (inc d))))))))))
        tracers @tracer-map
        ;; Kill positions
        recent-kills (->> events
                          (filter #(= :kill (:type %)))
                          (keep (fn [evt]
                                  (when-let [victim (get-in game-state [:players (:victim-id evt)])]
                                    [(:x victim) (:y victim)])))
                          set)
        ;; Players with reduced HP = recently hit
        damaged-players (->> players
                             (filter (fn [[_ p]] (and (:alive? p) (< (:hp p) 100))))
                             (map (fn [[_ p]] [(:x p) (:y p)]))
                             set)]
    [:div.grid {:style (str "grid-template-columns: repeat(" width ", 1fr);"
                            "grid-template-rows: repeat(" height ", 1fr);")}
     (for [y (range height)
           x (range width)]
       (let [cls (cell-class game-state x y player-lookup pax-lookup dest-lookup)
             player (get player-lookup [x y])
             pax (get pax-lookup [x y])
             is-kill (contains? recent-kills [x y])
             is-hit (and player (contains? damaged-players [x y]))
             tracer (when (and (not player) (not is-kill)) (get tracers [x y]))]
         [:div.cell
          {:class (str cls
                       (when is-kill " nuke")
                       (when is-hit " hit")
                       (when tracer (str " tracer tracer-" (name (:dir tracer)))))
           :style (str (when (and player (not is-kill))
                         (str "background-color:" (:color player) ";"))
                       (when tracer
                         (str "--d:" (:dist tracer)
                              ";--md:" (:max-dist tracer) ";")))}
          (cond
            is-kill [:div.nuke-fx
                     [:div.smoke]
                     [:div.sparks
                      [:div.spark] [:div.spark] [:div.spark]
                      [:div.spark] [:div.spark] [:div.spark]
                      [:div.spark] [:div.spark] [:div.spark]
                      [:div.spark] [:div.spark] [:div.spark]]]
            is-hit [:div.hit-fx
                    [:span.player-icon
                     {:title (str (:name player) " " (:hp player) "hp")}
                     (if (:has-passenger player) "\uD83D\uDE95" "\uD83D\uDE96")]
                    [:div.sparks.small
                     [:div.spark] [:div.spark] [:div.spark] [:div.spark]]]
            player [:span.player-icon
                    {:title (str (:name player) " (" (:score player) "pts)"
                                 " " (:hp player) "hp"
                                 (when (:has-passenger player) " [PAX]"))}
                    (if (:has-passenger player) "\uD83D\uDE95" "\uD83D\uDE96")]
            pax [:span.pax-icon {:title (str "Passenger \u2192 ("
                                             (get-in pax [:dest :x]) ","
                                             (get-in pax [:dest :y]) ")")}
                 "$"]
            :else nil)]))]))

;;; ---------------------------------------------------------------------------
;;; Scoreboard Fragment
;;; ---------------------------------------------------------------------------

(defn scoreboard-fragment [game-state]
  (let [players (->> (:players game-state)
                     (map-indexed (fn [idx [id p]]
                                    {:id id :name (:name p) :score (:score p)
                                     :alive (:alive? p) :hp (:hp p)
                                     :color (player-color idx)
                                     :has-passenger (some? (:passenger p))}))
                     (sort-by :score >))]
    (if (empty? players)
      [:div.empty "No players yet"]
      [:div.scores
       (for [{:keys [name score alive hp color has-passenger]} players]
         [:div.score-row {:class (when-not alive "dead")}
          [:span.color-dot {:style (str "background:" color)}]
          [:span.name name]
          (when has-passenger [:span.pax-badge "PAX"])
          [:span.hp (when alive (str hp "hp"))]
          [:span.score (str score "pts")]])])))

;;; ---------------------------------------------------------------------------
;;; Event Feed Fragment
;;; ---------------------------------------------------------------------------

(defn event-feed-fragment [events]
  (let [display-events (->> events
                            (filter #(#{:kill :delivery :player-joined :game-over :respawn}
                                      (:type %)))
                            (take-last 10)
                            reverse)]
    (if (empty? display-events)
      [:div.empty "Waiting for action..."]
      [:div.events
       (for [evt display-events]
         [:div.event {:class (name (:type evt))}
          (case (:type evt)
            :kill (str "\u2620\uFE0F ELIMINATED — " (:victim-id evt))
            :delivery (str "\uD83D\uDCE6 DELIVERY +" (:points evt)
                           " — " (:player-id evt))
            :player-joined (str "\uD83D\uDE95 JOINED — " (:name evt))
            :respawn (str "\u2728 RESPAWN — " (:player-id evt))
            :game-over "\uD83C\uDFC1 GAME OVER!"
            (str (:type evt)))])])))

;;; ---------------------------------------------------------------------------
;;; Game Info Fragment
;;; ---------------------------------------------------------------------------

(defn game-info-fragment [game-state]
  [:span
   [:span.tick (str "Tick: " (:tick game-state)
                    "/" (get-in game-state [:config :game-duration-ticks]))]
   [:span.players (str "Players: " (count (:players game-state)))]
   [:span.passengers (str "Passengers: "
                          (count (filter #(nil? (:picked-up-by %))
                                         (:passengers game-state))))]])
