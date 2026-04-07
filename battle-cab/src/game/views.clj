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
  [{:name "Rocket" :image "/sprites/rocket.png" :frame-count 4 :aspect 1.333
    :frames ["\uD83D\uDE97" "\uD83D\uDE99" "\uD83C\uDFCE\uFE0F" "\uD83D\uDE97"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Ghost" :frames ["\uD83D\uDC7E" "\uD83D\uDC7B" "\uD83D\uDC7E" "\uD83D\uDEF8"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Fox" :frames ["\uD83E\uDD8A" "\uD83D\uDC3A" "\uD83E\uDD8A" "\uD83D\uDC3E"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Robot" :frames ["\uD83E\uDD16" "\uD83D\uDD27" "\uD83E\uDD16" "\u26A1"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Octopus" :frames ["\uD83D\uDC19" "\uD83E\uDD91" "\uD83D\uDC19" "\uD83C\uDF0A"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Unicorn" :frames ["\uD83E\uDD84" "\uD83C\uDF08" "\uD83E\uDD84" "\u2728"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Dragon" :frames ["\uD83D\uDC32" "\uD83D\uDD25" "\uD83D\uDC32" "\uD83D\uDCA8"]
    :pax ["\uD83D\uDE95" "\uD83E\uDD11" "\uD83D\uDE95" "\uD83D\uDCB0"] :dead "\uD83D\uDC80"}
   {:name "Alien" :frames ["\uD83D\uDC7D" "\uD83D\uDEF8" "\uD83D\uDC7D" "\uD83D\uDCAB"]
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

;;; ---------------------------------------------------------------------------
;;; Sprite Rendering Functions
;;; ---------------------------------------------------------------------------
;; Each function owns its layout + style. No context-dependent CSS overrides.

(defn- sprite-frames
  "Core: render animated PNG frames or emoji fallback. Returns hiccup.
   `css-class` distinguishes grid vs scoreboard sizing."
  [sprite has-passenger alive css-class]
  (if-let [image (and alive (:image sprite))]
    (let [frame-count (get sprite :frame-count 4)
          aspect (get sprite :aspect 1.333)
          img-url (if has-passenger
                    (get sprite :pax-image image)
                    image)]
      [:span {:class (str "sprite sprite-composed " css-class)
              :style (str "--sprite-aspect:" aspect ";"
                          "--sprite-bg-size:" (* 100 frame-count) "% 100%;")}
       (for [i (range frame-count)]
         [:span.sprite-frame
          {:class (str "pf" i)
           :style (str "background-image:url(" img-url ");"
                       "background-position:" (if (= frame-count 1) "0"
                                                  (* i (/ 100.0 (dec frame-count)))) "% 0%;")}])])
    ;; Emoji fallback
    (let [frames (cond
                   (not alive) [(:dead sprite) (:dead sprite) (:dead sprite) (:dead sprite)]
                   has-passenger (:pax sprite)
                   :else (:frames sprite))]
      [:span {:class (str "sprite " css-class)}
       [:span.sf.sf0 (nth frames 0)]
       [:span.sf.sf1 (nth frames 1)]
       [:span.sf.sf2 (nth frames 2)]
       [:span.sf.sf3 (nth frames 3)]])))

(defn- rank-badge
  "Rank badge — PNG image if available, text number fallback.
   `css-class` distinguishes grid vs scoreboard sizing."
  [rank css-class]
  (let [suffix (ordinal rank)
        png (str "/sprites/rank-" suffix ".png")
        exists? (.exists (clojure.java.io/file (str "resources/public" png)))]
    (if exists?
      [:div {:class (str "rank-badge " css-class)}
       [:img.rank-img {:src png}]]
      [:div {:class (str "rank-badge " css-class)}
       [:span.rank-num (str rank)]])))

(defn grid-sprite
  "Sprite for a game grid cell — fills cell width, rank badge above."
  [sprite player]
  [:div.sprite-cell
   {:title (str (:name player) " (" (:score player) "pts)"
                " " (:hp player) "hp"
                (when (:has-passenger player) " [PAX]"))}
   (rank-badge (:rank player) "rank-grid")
   (sprite-frames (:sprite player) (:has-passenger player) true "gs")])

(defn grid-sprite-hit
  "Sprite for a hit player in a grid cell — with spark effects."
  [sprite player]
  [:div.hit-fx
   [:div.sprite-cell
    (rank-badge (:rank player) "rank-grid")
    (sprite-frames (:sprite player) (:has-passenger player) true "gs")]
   [:div.sparks.small
    [:div.spark] [:div.spark] [:div.spark] [:div.spark]]])

(defn scoreboard-sprite
  "Compact inline sprite for the scoreboard sidebar."
  [sprite has-passenger alive]
  (sprite-frames sprite has-passenger alive "sbs"))

;;; ---------------------------------------------------------------------------
;;; Shared Nav Bar
;;; ---------------------------------------------------------------------------

(defn nav-bar
  "Top navigation bar linking all pages. `active` is :spectator, :test, :sprites, or :stats."
  [active]
  [:nav.nav-bar
   [:a.nav-link {:href "/" :class (when (= active :spectator) "active")} "Spectator"]
   [:a.nav-link {:href "/server-stats" :class (when (= active :stats) "active")} "Stats"]
   [:a.nav-link {:href "/test" :class (when (= active :test) "active")} "Visual Test"]
   [:a.nav-link {:href "/sprite-viewer" :class (when (= active :sprites) "active")} "Sprites"]])

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
      (nav-bar :spectator)

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

(defn- build-player-list [players rank-map]
  (->> players
       (map-indexed (fn [idx [id p]]
                      {:id id :x (:x p) :y (:y p)
                       :name (:name p) :score (:score p)
                       :hp (:hp p) :alive (:alive? p)
                       :color (player-color idx)
                       :sprite (player-sprite idx)
                       :rank (get rank-map id 0)
                       :has-passenger (some? (:passenger p))}))))

(defn game-map-fragment
  "Render the full game map grid as a spectator god-mode view.
   Events from the current tick drive hit/kill/tracer animations.
   Optional `opts` map: {:sprite-height px} for zoomed views."
  ([game-state events] (game-map-fragment game-state events {}))
  ([game-state events opts]
   (let [{:keys [width height walls]} (:map game-state)
         players (:players game-state)
         passengers (filter #(nil? (:picked-up-by %)) (:passengers game-state))
         directions {:north [0 -1] :south [0 1] :east [1 0] :west [-1 0]}
         sprite-h (get opts :sprite-height 32)
         rank-map (compute-ranks players)
         player-list (build-player-list players rank-map)
         alive-list (filter :alive player-list)
         player-lookup (into {} (map (fn [p] [[(:x p) (:y p)] p]) alive-list))
         pax-lookup (into {} (map (fn [p] [[(:x p) (:y p)] p]) passengers))
         dest-lookup (into {} (keep (fn [p] (when-let [pax (:passenger p)] [[(get-in pax [:dest :x]) (get-in pax [:dest :y])] {:player-name (:name p)}])) (map (fn [[_ p]] p) players)))
         tracer-length 5
         tracer-travel 10
         tracer-map (atom {})
         _ (doseq [evt events] (when (and (= :command (:type evt)) (= :shoot (get-in evt [:action :type]))) (let [pid (:player-id evt) player (get-in game-state [:players pid]) dir (keyword (get-in evt [:action :direction])) [dx dy] (get directions dir [0 0]) range- 20] (when (and player (not= [dx dy] [0 0])) (let [max-dist (loop [x (+ (:x player) dx) y (+ (:y player) dy) d 1] (if (or (> d range-) (not (and (>= x 0) (< x width) (>= y 0) (< y height))) (contains? walls [x y])) (dec d) (if (get player-lookup [x y]) d (recur (+ x dx) (+ y dy) (inc d))))) render-dist (min max-dist tracer-travel)] (loop [x (+ (:x player) dx) y (+ (:y player) dy) d 1] (when (and (<= d render-dist) (>= x 0) (< x width) (>= y 0) (< y height) (not (contains? walls [x y]))) (swap! tracer-map assoc [x y] {:dir dir :d d :tt render-dist :tl tracer-length}) (when-not (get player-lookup [x y]) (recur (+ x dx) (+ y dy) (inc d))))))))))
         tracers @tracer-map
         lightning-cells (->> (:recent-effects game-state) (filter #(= :lightning (:type %))) (mapcat :cells) set)
         lightning-centers (->> (:recent-effects game-state) (filter #(= :lightning (:type %))) (map (fn [e] [(:x e) (:y e)])) set)
         recent-kills (->> events (filter #(= :kill (:type %))) (keep (fn [evt] (when-let [victim (get-in game-state [:players (:victim-id evt)])] [(:x victim) (:y victim)]))) set)
         damaged-players (->> players (filter (fn [[_ p]] (and (:alive? p) (< (:hp p) 100)))) (map (fn [[_ p]] [(:x p) (:y p)])) set)
         shrink-warning (or (:shrink-warning game-state) #{})
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
            :style (str (when tracer
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
             is-hit (grid-sprite-hit (:sprite player) player)
             player (grid-sprite (:sprite player) player)
             pax [:span.pax-icon {:title (str "Passenger \u2192 ("
                                              (get-in pax [:dest :x]) ","
                                              (get-in pax [:dest :y]) ")")}
                  "$"]
             :else nil)]))])))

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
                          :style (str "border-left: 4px solid " color ";")}
          [:span.rank-num (ordinal rank)]
          (scoreboard-sprite sprite has-passenger alive)
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

;;; ---------------------------------------------------------------------------
;;; Server Stats Page
;;; ---------------------------------------------------------------------------

(defn- format-rps [n]
  (if (zero? n) "0" (format "%.1f" (double n))))

(defn- stat-card [label value & {:keys [sub class] :or {class ""}}]
  [:div.stat-card {:class class}
   [:div.stat-value value]
   [:div.stat-label label]
   (when sub [:div.stat-sub sub])])

(defn server-stats-page
  "Full HTML page showing server statistics, request rates, player info."
  [game-state rates sse-count ws-count]
  (let [players (:players game-state)
        alive-count (count (filter (fn [[_ p]] (:alive? p)) players))
        total-players (count players)
        passengers (:passengers game-state)
        free-pax (count (filter #(nil? (:picked-up-by %)) passengers))
        carried-pax (count (filter #(some? (:picked-up-by %)) passengers))
        tick (:tick game-state)
        running? (some? (some-> @game.engine/system :game-timer deref))
        tick-ms (get-in game-state [:config :tick-ms] 500)
        by-endpoint (:by-endpoint rates)]
    (str
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title "Cab Battle — Server Stats"]
        [:link {:rel "stylesheet" :href "/css/spectator.css"}]
        [:style
         (h/raw "
.stats-container {
  padding: 1.5rem;
  max-width: 1200px;
  margin: 0 auto;
  overflow-y: auto;
  height: calc(100vh - 2rem);
}
.stats-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}
.stats-header h1 {
  font-size: 1.6rem;
  color: var(--neon-yellow);
  text-shadow: 0 0 20px rgba(255,230,109,0.5);
  letter-spacing: 0.2em;
}
.status-badge {
  padding: 0.3rem 0.8rem;
  border-radius: 4px;
  font-size: 0.8rem;
  font-weight: bold;
  letter-spacing: 0.1em;
}
.status-running { background: #00b89433; color: #00b894; border: 1px solid #00b894; }
.status-stopped { background: #ff6b6b33; color: #ff6b6b; border: 1px solid #ff6b6b; }
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1rem;
  margin-bottom: 2rem;
}
.stat-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  text-align: center;
}
.stat-value {
  font-size: 2rem;
  font-weight: bold;
  color: var(--neon-cyan);
  line-height: 1.2;
}
.stat-label {
  font-size: 0.7rem;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.15em;
  margin-top: 0.3rem;
}
.stat-sub {
  font-size: 0.65rem;
  color: var(--text-dim);
  margin-top: 0.2rem;
}
.stat-card.highlight .stat-value { color: var(--neon-yellow); }
.stat-card.alert .stat-value { color: var(--neon-red); }
.section-title {
  font-size: 0.9rem;
  color: var(--neon-yellow);
  letter-spacing: 0.15em;
  text-transform: uppercase;
  margin-bottom: 0.8rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.4rem;
}
.rate-table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 2rem;
  font-size: 0.85rem;
}
.rate-table th {
  text-align: left;
  color: var(--text-dim);
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  padding: 0.4rem 0.8rem;
  border-bottom: 1px solid var(--border);
}
.rate-table td {
  padding: 0.4rem 0.8rem;
  border-bottom: 1px solid #1a1a2a;
}
.rate-table .ep { color: var(--neon-cyan); }
.rate-table .rps { color: var(--neon-yellow); font-weight: bold; text-align: right; }
.rate-bar {
  display: inline-block;
  height: 8px;
  background: var(--neon-cyan);
  border-radius: 4px;
  opacity: 0.6;
  margin-left: 0.5rem;
  vertical-align: middle;
}
.player-table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 2rem;
  font-size: 0.85rem;
}
.player-table th {
  text-align: left;
  color: var(--text-dim);
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  padding: 0.4rem 0.8rem;
  border-bottom: 1px solid var(--border);
}
.player-table td {
  padding: 0.4rem 0.8rem;
  border-bottom: 1px solid #1a1a2a;
}
.player-table .alive { color: #00b894; }
.player-table .dead { color: #ff6b6b; }
.auto-refresh {
  font-size: 0.7rem;
  color: var(--text-dim);
  text-align: right;
}
")]]
       [:body
        (nav-bar :stats)
        [:div.stats-container
         ;; Header
         [:div.stats-header
          [:h1 "SERVER STATS"]
          [:div
           [:span.status-badge
            {:class (if running? "status-running" "status-stopped")}
            (if running? "RUNNING" "STOPPED")]]]

         ;; Top-line metrics
         [:div.stats-grid
          (stat-card "Bots" (str total-players)
                     :sub (str alive-count " alive / " (- total-players alive-count) " dead")
                     :class "highlight")
          (stat-card "Requests/sec" (format-rps (:total-rps rates))
                     :sub (str (:sample-count rates) " in last "
                               (int (:window-secs rates)) "s")
                     :class "highlight")
          (stat-card "SSE Spectators" (str sse-count))
          (stat-card "WS Spectators" (str ws-count))
          (stat-card "Current Tick" (str tick)
                     :sub (str (format "%.0f" (double (/ tick (/ 1000.0 tick-ms)))) "s elapsed"))
          (stat-card "Tick Rate" (str tick-ms "ms")
                     :sub (str (format "%.0f" (/ 1000.0 tick-ms)) " ticks/sec"))
          (stat-card "Passengers" (str free-pax " free")
                     :sub (str carried-pax " carried"))
          (stat-card "Map" (get-in game-state [:map :name] "unknown")
                     :sub (str (get-in game-state [:map :width]) "x"
                               (get-in game-state [:map :height])))]

         ;; Endpoint rates
         [:div.section-title "Request Rates by Endpoint"]
         (if (seq by-endpoint)
           (let [max-rps (apply max (vals by-endpoint))]
             [:table.rate-table
              [:thead [:tr [:th "Endpoint"] [:th {:style "text-align:right"} "Req/s"] [:th ""]]]
              [:tbody
               (for [[ep rps] (sort-by val > by-endpoint)]
                 [:tr
                  [:td.ep ep]
                  [:td.rps (format-rps rps)]
                  [:td [:span.rate-bar
                        {:style (str "width:" (if (pos? max-rps)
                                                (int (* 120 (/ rps max-rps)))
                                                0) "px")}]]])]])
           [:div {:style "color:var(--text-dim);margin-bottom:2rem"} "No requests recorded yet."])

         ;; Player details
         (when (seq players)
           [:div
            [:div.section-title "Player Details"]
            [:table.player-table
             [:thead [:tr [:th "Name"] [:th "ID"] [:th "Score"] [:th "HP"]
                      [:th "Pos"] [:th "Ammo"] [:th "Passenger"] [:th "Status"]]]
             [:tbody
              (for [[id p] (sort-by (fn [[_ p]] (:score p)) > players)]
                [:tr
                 [:td {:style (str "color:" (player-color (.indexOf (vec (keys players)) id)))}
                  (:name p)]
                 [:td {:style "color:var(--text-dim);font-size:0.7rem"} (subs (str id) 0 (min 12 (count (str id))))]
                 [:td {:style "font-weight:bold"} (:score p)]
                 [:td {:class (if (:alive? p) "alive" "dead")} (:hp p)]
                 [:td (str "(" (:x p) "," (:y p) ")")]
                 [:td (:ammo p)]
                 [:td (if (:passenger p) "Yes" "-")]
                 [:td {:class (if (:alive? p) "alive" "dead")}
                  (if (:alive? p) "ALIVE" "DEAD")]])]]])

         ;; Auto-refresh script
         [:div.auto-refresh "Auto-refreshes every 2s"]]]

       [:script (h/raw "setTimeout(function refresh(){ location.reload(); }, 2000);")]]))))
