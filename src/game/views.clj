(ns game.views
  "Hiccup templates for spectator page and live fragments."
  (:require [hiccup2.core :as h]
            [hiccup.page :as page]
            [game.maps :as maps]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Player Colors & Sprites — Mario Kart whimsy
;;; ---------------------------------------------------------------------------

(def player-colors
  ["#ff6b6b" "#4ecdc4" "#ffe66d" "#a29bfe"
   "#fd79a8" "#00b894" "#fdcb6e" "#6c5ce7"])

(defn player-color [idx]
  (nth player-colors (mod idx (count player-colors))))

;; Each player gets a unique animated sprite — 4 frames that cycle
;; :frames = normal driving, :pax = carrying passenger, :dead = eliminated
(def player-sprites
  [{:name "Rocket"   :frames ["\uD83D\uDE97" "\uD83D\uDE99" "\uD83C\uDFCE\uFE0F" "\uD83D\uDE97"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Ghost"    :frames ["\uD83D\uDC7E" "\uD83D\uDC7B" "\uD83D\uDC7E" "\uD83D\uDEF8"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Fox"      :frames ["\uD83E\uDD8A" "\uD83D\uDC3A" "\uD83E\uDD8A" "\uD83D\uDC3E"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Robot"    :frames ["\uD83E\uDD16" "\uD83D\uDD27" "\uD83E\uDD16" "\u26A1"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Octopus"  :frames ["\uD83D\uDC19" "\uD83E\uDD91" "\uD83D\uDC19" "\uD83C\uDF0A"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Unicorn"  :frames ["\uD83E\uDD84" "\uD83C\uDF08" "\uD83E\uDD84" "\u2728"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Dragon"   :frames ["\uD83D\uDC32" "\uD83D\uDD25" "\uD83D\uDC32" "\uD83D\uDCA8"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Alien"    :frames ["\uD83D\uDC7D" "\uD83D\uDEF8" "\uD83D\uDC7D" "\uD83D\uDCAB"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}])

(defn player-sprite [idx]
  (nth player-sprites (mod idx (count player-sprites))))

(defn- compute-ranks
  "Returns {player-id rank} where rank is 1-based, sorted by score desc."
  [players]
  (->> players
       (map (fn [[id p]] {:id id :score (:score p)}))
       (sort-by :score >)
       (map-indexed (fn [i {:keys [id]}] [id (inc i)]))
       (into {})))

(defn- ordinal [n]
  (str n (case (int n) 1 "st" 2 "nd" 3 "rd" "th")))

(defn- medal-class [rank]
  (case (int rank) 1 "gold" 2 "silver" 3 "bronze" nil))

(defn- sprite-style
  "CSS custom properties for sprite frame animation."
  [sprite has-passenger alive]
  (let [frames (cond
                 (not alive) [(:dead sprite) (:dead sprite) (:dead sprite) (:dead sprite)]
                 has-passenger (:pax sprite)
                 :else (:frames sprite))]
    (str "--f0:'" (nth frames 0) "';"
         "--f1:'" (nth frames 1) "';"
         "--f2:'" (nth frames 2) "';"
         "--f3:'" (nth frames 3) "';")))

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
       [:div.header-controls
        [:select#map-select.map-select
         (for [{:keys [id name]} maps/map-registry]
           [:option {:value id} name])]
        [:button.restart-btn
         {:data-star-on:click
          (str "fetch('/game/restart',{method:'POST',"
               "headers:{'Content-Type':'application/json'},"
               "body:JSON.stringify({map:document.getElementById('map-select').value})})"
               ".catch(e=>console.error(e))")}
         "NEW GAME"]
        [:button.lightning-btn
         {:data-star-on:click
          (str "fetch('/game/lightning',{method:'POST'})"
               ".catch(e=>console.error(e))")}
         "\u26A1 LIGHTNING"]]

       ;; Tick scrubber controls
       [:div.scrubber
        [:button#scrub-prev.scrub-btn {:title "Previous tick (Left arrow)"}
         "\u25C0"]
        [:input#tick-input.tick-input
         {:type "number" :min "0" :placeholder "tick"
          :title "Enter tick number and press Enter"}]
        [:button#scrub-next.scrub-btn {:title "Next tick (Right arrow)"}
         "\u25B6"]
        [:button#scrub-resume.scrub-btn.resume-btn {:title "Resume live (Esc)"}
         "\u25B6\u25B6"]]

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
          [:div.empty "Waiting for action..."]]]]]

      ;; Scrubber JavaScript
      [:script
       (h/raw
        "
(function() {
  var input = document.getElementById('tick-input');
  var prevBtn = document.getElementById('scrub-prev');
  var nextBtn = document.getElementById('scrub-next');
  var resumeBtn = document.getElementById('scrub-resume');

  function seekTo(tick) {
    fetch('/game/seek', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({tick: tick})
    })
    .then(function(r) { return r.json(); })
    .then(function(d) { input.value = d.tick; input.max = d['max-tick']; })
    .catch(function(e) { console.error(e); });
  }

  function step(delta) {
    var current = parseInt(input.value) || 0;
    seekTo(current + delta);
  }

  // Input: enter to seek
  input.addEventListener('keydown', function(e) {
    if (e.key === 'Enter') {
      seekTo(parseInt(input.value) || 0);
    }
  });

  // Buttons
  prevBtn.addEventListener('click', function() { step(-1); });
  nextBtn.addEventListener('click', function() { step(1); });
  resumeBtn.addEventListener('click', function() {
    input.value = '';
    fetch('/game/resume', {method: 'POST'}).catch(function(e) { console.error(e); });
  });

  // Keyboard: left/right arrows + Esc to resume
  document.addEventListener('keydown', function(e) {
    // Don't hijack if user is typing in a different input
    if (e.target.tagName === 'SELECT') return;
    if (e.target === input && e.key !== 'ArrowLeft' && e.key !== 'ArrowRight' && e.key !== 'Escape') return;

    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      step(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      step(1);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      input.value = '';
      fetch('/game/resume', {method: 'POST'}).catch(function(e2) { console.error(e2); });
    }
  });
})();
")]]])))

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
        rank-map (compute-ranks players)
        player-list (->> players
                         (map-indexed (fn [idx [id p]]
                                        {:id id :x (:x p) :y (:y p)
                                         :name (:name p) :score (:score p)
                                         :hp (:hp p) :alive (:alive? p)
                                         :color (player-color idx)
                                         :sprite (player-sprite idx)
                                         :rank (get rank-map id 0)
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
        ;; Tracer config — adjust independently
        tracer-length 5 ;; visual beam length (cells lit at once)
        tracer-travel 10 ;; cells the beam sweeps through per tick
        ;; Compute bullet tracers: all cells along path up to tracer-travel
        tracer-map (atom {}) ;; [x y] -> {:dir :east :d N :tt N}
        _ (doseq [evt events]
            (when (and (= :command (:type evt))
                       (= :shoot (get-in evt [:action :type])))
              (let [pid (:player-id evt)
                    player (get-in game-state [:players pid])
                    dir (keyword (get-in evt [:action :direction]))
                    [dx dy] (get directions dir [0 0])
                    range- 20]
                (when (and player (not= [dx dy] [0 0]))
                  ;; Find how far bullet actually travels (hit or wall)
                  (let [max-dist (loop [x (+ (:x player) dx)
                                        y (+ (:y player) dy)
                                        d 1]
                                   (if (or (> d range-)
                                           (not (and (>= x 0) (< x width) (>= y 0) (< y height)))
                                           (contains? walls [x y]))
                                     (dec d)
                                     (if (get player-lookup [x y])
                                       d
                                       (recur (+ x dx) (+ y dy) (inc d)))))
                        ;; Render up to tracer-travel cells (or max-dist, whichever is less)
                        render-dist (min max-dist tracer-travel)]
                    (loop [x (+ (:x player) dx)
                           y (+ (:y player) dy)
                           d 1]
                      (when (and (<= d render-dist)
                                 (>= x 0) (< x width)
                                 (>= y 0) (< y height)
                                 (not (contains? walls [x y])))
                        (swap! tracer-map assoc [x y]
                               {:dir dir :d d :tt render-dist :tl tracer-length})
                        (when-not (get player-lookup [x y])
                          (recur (+ x dx) (+ y dy) (inc d))))))))))
        tracers @tracer-map
        ;; Lightning strike cells from recent-effects
        lightning-cells (->> (:recent-effects game-state)
                             (filter #(= :lightning (:type %)))
                             (mapcat :cells)
                             set)
        lightning-centers (->> (:recent-effects game-state)
                               (filter #(= :lightning (:type %)))
                               (map (fn [e] [(:x e) (:y e)]))
                               set)
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
                             set)
        ;; Battle royale shrink warning — ring of fire
        shrink-warning (or (:shrink-warning game-state) #{})
        ;; Active crater fires
        crater-fires (or (:crater-fires game-state) {})]
    [:div.grid {:style (str "grid-template-columns: repeat(" width ", 1fr);"
                            "grid-template-rows: repeat(" height ", 1fr);")}
     (for [y (range height)
           x (range width)]
       (let [cls (cell-class game-state x y player-lookup pax-lookup dest-lookup)
             player (get player-lookup [x y])
             pax (get pax-lookup [x y])
             is-kill (contains? recent-kills [x y])
             is-hit (and player (contains? damaged-players [x y]))
             is-lightning-center (contains? lightning-centers [x y])
             is-lightning (and (not is-lightning-center)
                               (contains? lightning-cells [x y]))
             is-shrink-warning (contains? shrink-warning [x y])
             is-crater-fire (contains? crater-fires [x y])
             tracer (when (and (not player) (not is-kill) (not is-lightning) (not is-lightning-center))
                      (get tracers [x y]))]
         [:div.cell
          {:class (str cls
                       (when is-kill " nuke")
                       (when is-hit " hit")
                       (when is-shrink-warning " shrink-warning")
                       (when is-crater-fire " crater-fire")
                       (when is-lightning-center " lightning-center")
                       (when is-lightning " lightning-blast")
                       (when tracer (str " tracer tracer-" (name (:dir tracer)))))
           :style (str (when (and player (not is-kill))
                         (sprite-style (:sprite player) (:has-passenger player) true))
                       (when tracer
                         (str "--d:" (:d tracer)
                              ";--tt:" (:tt tracer)
                              ";--tl:" (:tl tracer) ";")))}
          (cond
            is-lightning-center
            [:div.lightning-fx
             [:div.lightning-bolt]
             [:div.lightning-flash]
             [:div.sparks
              [:div.spark] [:div.spark] [:div.spark]
              [:div.spark] [:div.spark] [:div.spark]
              [:div.spark] [:div.spark] [:div.spark]
              [:div.spark] [:div.spark] [:div.spark]]]

            is-lightning
            [:div.crater-fx]

            is-kill [:div.nuke-fx
                     [:div.smoke]
                     [:div.sparks
                      [:div.spark] [:div.spark] [:div.spark]
                      [:div.spark] [:div.spark] [:div.spark]
                      [:div.spark] [:div.spark] [:div.spark]
                      [:div.spark] [:div.spark] [:div.spark]]]
            is-hit [:div.hit-fx
                    [:div.sprite-cell
                     [:span.sprite {:title (str (:name player) " " (:hp player) "hp")}]
                     [:div.rank-badge
                      [:span.color-dot {:style (str "background:" (:color player))}]
                      [:span.rank-num (str (:rank player))]]]
                    [:div.sparks.small
                     [:div.spark] [:div.spark] [:div.spark] [:div.spark]]]
            player [:div.sprite-cell
                    {:title (str (:name player) " (" (:score player) "pts)"
                                 " " (:hp player) "hp"
                                 (when (:has-passenger player) " [PAX]"))}
                    [:span.sprite]
                    [:div.rank-badge
                     [:span.color-dot {:style (str "background:" (:color player))}]
                     [:span.rank-num (str (:rank player))]]]
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
                                     :sprite (player-sprite idx)
                                     :has-passenger (some? (:passenger p))}))
                     (sort-by :score >)
                     (map-indexed (fn [rank-idx p]
                                    (assoc p :rank (inc rank-idx)))))]
    (if (empty? players)
      [:div.empty "No players yet"]
      [:div.scores
       (for [{:keys [name score alive hp color sprite has-passenger rank]} players]
         [:div.score-row {:class (str (when-not alive "dead")
                                      (when-let [m (medal-class rank)]
                                        (str " " m)))
                          :style (str "border-left: 4px solid " color ";"
                                      (sprite-style sprite has-passenger alive))}
          [:span.rank-num (ordinal rank)]
          [:span.sprite.sb-sprite]
          [:span.name name]
          (when has-passenger [:span.pax-badge "PAX"])
          [:div.hp-bar
           [:div.hp-fill {:style (str "width:" (if alive hp 0) "%")}]]
          [:span.score (str score "pts")]])])))

;;; ---------------------------------------------------------------------------
;;; Event Feed Fragment
;;; ---------------------------------------------------------------------------

(defn event-feed-fragment [events]
  (let [display-events (->> events
                            (filter #(#{:kill :delivery :player-joined :game-over :respawn :lightning}
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
            :lightning (str "\u26A1 LIGHTNING STRIKE at ("
                            (:x evt) "," (:y evt)
                            ") — " (:cells-destroyed evt) " cells destroyed!")
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
