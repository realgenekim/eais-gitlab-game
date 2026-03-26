(ns game.core
  "Pure game engine. All functions take state, return state. No side effects."
  (:require [clojure.set]
            [game.config :as config]))

;;; ---------------------------------------------------------------------------
;;; Map & Geometry
;;; ---------------------------------------------------------------------------

(def directions
  {:north [0 -1] :south [0 1] :east [1 0] :west [-1 0]})

(defn in-bounds? [{:keys [width height]} [x y]]
  (and (>= x 0) (< x width) (>= y 0) (< y height)))

(defn wall? [game-state [x y]]
  (contains? (get-in game-state [:map :walls]) [x y]))

(defn walkable? [game-state pos]
  (and (in-bounds? (:map game-state) pos)
       (not (wall? game-state pos))))

(defn manhattan-distance [[x1 y1] [x2 y2]]
  (+ (abs (- x1 x2)) (abs (- y1 y2))))

(defn positions-in-radius
  "All grid positions within manhattan distance r of [cx cy]."
  [[cx cy] r {:keys [width height]}]
  (for [x (range (max 0 (- cx r)) (min width (inc (+ cx r))))
        y (range (max 0 (- cy r)) (min height (inc (+ cy r))))
        :when (<= (manhattan-distance [cx cy] [x y]) r)]
    [x y]))

;;; ---------------------------------------------------------------------------
;;; Visibility / Fog of War
;;; ---------------------------------------------------------------------------

(defn visible-positions
  "Returns set of positions visible to a player (manhattan radius)."
  [game-state player-id]
  (let [player (get-in game-state [:players player-id])
        radius (get-in game-state [:config :visibility-radius] 5)]
    (set (positions-in-radius [(:x player) (:y player)] radius (:map game-state)))))

(defn player-view
  "Build the fog-of-war view for a specific player.
   Includes visible shots (bullet tracers) so bots can dodge."
  [game-state player-id]
  (let [visible (visible-positions game-state player-id)
        me (get-in game-state [:players player-id])
        others (->> (:players game-state)
                    (remove (fn [[id _]] (= id player-id)))
                    (filter (fn [[_ p]] (and (:alive? p)
                                             (visible [(:x p) (:y p)]))))
                    (map (fn [[id p]]
                           {:id id :x (:x p) :y (:y p)
                            :has-passenger (some? (:passenger p))})))
        passengers (->> (:passengers game-state)
                        (filter (fn [p] (and (nil? (:picked-up-by p))
                                             (visible [(:x p) (:y p)]))))
                        (map #(select-keys % [:id :x :y :dest])))
        ;; Shots with any tracer cell in visibility range
        shots (->> (:recent-shots game-state)
                   (filter (fn [shot]
                             (some visible (:path shot))))
                   (map (fn [shot]
                          {:shooter-id (:shooter-id shot)
                           :origin (:origin shot)
                           :direction (:direction shot)
                           :path (vec (filter visible (:path shot)))})))]
    {:tick (:tick game-state)
     :you (-> me
              (select-keys [:x :y :hp :score :passenger :ammo :grenades :alive?])
              (assoc :id player-id))
     :visible {:players (vec others)
               :passengers (vec passengers)
               :shots (vec shots)}
     :map {:width (get-in game-state [:map :width])
           :height (get-in game-state [:map :height])}}))

;;; ---------------------------------------------------------------------------
;;; Map Swap — change map mid-game, bump entities out of walls
;;; ---------------------------------------------------------------------------

(defn find-nearest-open
  "Find nearest open cell to [x y] in the given state via BFS."
  [state x y]
  (let [{:keys [width height walls]} (:map state)]
    (if (and (in-bounds? (:map state) [x y])
             (not (contains? walls [x y])))
      [x y]
      (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [x y])
             visited #{[x y]}]
        (when-let [pos (peek queue)]
          (let [[px py] pos
                neighbors (for [[dx dy] [[0 -1] [0 1] [1 0] [-1 0]]
                                :let [nx (+ px dx) ny (+ py dy)]
                                :when (and (>= nx 0) (< nx width)
                                           (>= ny 0) (< ny height)
                                           (not (visited [nx ny])))]
                            [nx ny])
                open (first (filter #(not (contains? walls %)) neighbors))]
            (if open
              open
              (recur (into (pop queue) (remove visited neighbors))
                     (into visited neighbors)))))))))

(defn swap-map
  "Replace the current map mid-game. Bumps players and passengers out of walls."
  [state new-game-map]
  (let [state (assoc state :map new-game-map)
        ;; Bump alive players out of walls
        state (reduce-kv
               (fn [s id player]
                 (if (and (:alive? player)
                          (contains? (:walls new-game-map) [(:x player) (:y player)]))
                   (let [[nx ny] (find-nearest-open s (:x player) (:y player))]
                     (-> s
                         (assoc-in [:players id :x] nx)
                         (assoc-in [:players id :y] ny)))
                   s))
               state (:players state))
        ;; Remove passengers stuck in walls
        state (update state :passengers
                      (fn [ps] (vec (remove #(contains? (:walls new-game-map)
                                                        [(:x %) (:y %)])
                                            ps))))]
    state))

;;; ---------------------------------------------------------------------------
;;; Actions
;;; ---------------------------------------------------------------------------

(defmulti apply-action
  "Apply a single player action. Returns updated game-state."
  (fn [_state _player-id action] (:type action)))

(defmethod apply-action :move
  [state player-id {:keys [direction]}]
  (let [player (get-in state [:players player-id])
        [dx dy] (get directions (keyword direction) [0 0])
        new-pos [(+ (:x player) dx) (+ (:y player) dy)]]
    (if (and (:alive? player) (walkable? state new-pos))
      (-> state
          (assoc-in [:players player-id :x] (first new-pos))
          (assoc-in [:players player-id :y] (second new-pos)))
      state)))

(defmethod apply-action :pickup
  [state player-id _action]
  (let [player (get-in state [:players player-id])
        px (:x player) py (:y player)]
    (if (or (not (:alive? player)) (:passenger player))
      state
      ;; Find first passenger at player's location
      (let [idx (first (keep-indexed
                        (fn [i p]
                          (when (and (nil? (:picked-up-by p))
                                     (= (:x p) px) (= (:y p) py))
                            i))
                        (:passengers state)))]
        (if idx
          (let [pax (get-in state [:passengers idx])]
            (-> state
                (assoc-in [:passengers idx :picked-up-by] player-id)
                (assoc-in [:players player-id :passenger]
                          {:id (:id pax) :dest (:dest pax)})))
          state)))))

(defmethod apply-action :dropoff
  [state player-id _action]
  (let [player (get-in state [:players player-id])
        pax (:passenger player)]
    (if (and (:alive? player) pax
             (= (:x player) (get-in pax [:dest :x]))
             (= (:y player) (get-in pax [:dest :y])))
      (-> state
          (assoc-in [:players player-id :passenger] nil)
          (update-in [:players player-id :score] + 100)
          ;; Remove delivered passenger
          (update :passengers
                  (fn [ps] (vec (remove #(= (:id %) (:id pax)) ps)))))
      state)))

(defmethod apply-action :shoot
  [state player-id {:keys [direction]}]
  (let [player (get-in state [:players player-id])
        [dx dy] (get directions (keyword direction) [0 0])
        range- (get-in state [:config :shoot-range] 5)
        damage (get-in state [:config :shoot-damage] 30)]
    (if (or (not (:alive? player))
            (< (:ammo player) 1)
            (= [dx dy] [0 0]))
      state
      (let [;; Trace the shot — collect path cells AND find hit target
            {:keys [path hit-id]}
            (loop [x (+ (:x player) dx)
                   y (+ (:y player) dy)
                   dist 1
                   cells []]
              (cond
                (> dist range-)
                {:path cells :hit-id nil}

                (not (in-bounds? (:map state) [x y]))
                {:path cells :hit-id nil}

                (wall? state [x y])
                {:path cells :hit-id nil}

                :else
                (let [target (first (keep (fn [[id p]]
                                            (when (and (not= id player-id)
                                                       (:alive? p)
                                                       (= (:x p) x)
                                                       (= (:y p) y))
                                              id))
                                          (:players state)))]
                  (if target
                    {:path (conj cells [x y]) :hit-id target}
                    (recur (+ x dx) (+ y dy) (inc dist)
                           (conj cells [x y]))))))
            ;; Record shot for API visibility
            shot {:shooter-id player-id
                  :origin [(:x player) (:y player)]
                  :direction (keyword direction)
                  :path path
                  :hit-id hit-id}
            state (-> state
                      (update-in [:players player-id :ammo] dec)
                      (update :recent-shots conj shot))]
        (if hit-id
          (let [new-hp (- (get-in state [:players hit-id :hp]) damage)]
            (if (<= new-hp 0)
              ;; Kill!
              (-> state
                  (assoc-in [:players hit-id :hp] 0)
                  (assoc-in [:players hit-id :alive?] false)
                  (assoc-in [:players hit-id :respawn-at]
                            (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))
                  ;; Drop passenger if carrying
                  (cond-> (get-in state [:players hit-id :passenger])
                    (update :passengers
                            (fn [ps]
                              (mapv #(if (= (:id %) (get-in state [:players hit-id :passenger :id]))
                                       (assoc % :picked-up-by nil
                                              :x (get-in state [:players hit-id :x])
                                              :y (get-in state [:players hit-id :y]))
                                       %)
                                    ps))))
                  (assoc-in [:players hit-id :passenger] nil)
                  (update-in [:players player-id :score] + 50))
              ;; Damage only
              (assoc-in state [:players hit-id :hp] new-hp)))
          state)))))

(defmethod apply-action :default
  [state _player-id _action]
  state)

;;; ---------------------------------------------------------------------------
;;; Tick Advance (the core loop — pure function)
;;; ---------------------------------------------------------------------------

(defn apply-commands
  "Apply all player commands for this tick."
  [state commands]
  (reduce (fn [s {:keys [player-id action]}]
            (apply-action s player-id action))
          state commands))

(defn respawn-dead-players
  "Respawn players whose respawn timer has elapsed.
   If spawn point is now a wall (shrink/lightning), bump to nearest open cell."
  [state]
  (let [spawn-points (get-in state [:map :spawn-points] [[1 1] [18 1] [1 18] [18 18]])]
    (reduce-kv
     (fn [s id player]
       (if (and (not (:alive? player))
                (:respawn-at player)
                (>= (:tick state) (:respawn-at player)))
         (let [spawn (nth spawn-points (mod (hash id) (count spawn-points)))
               ;; If spawn is now a wall, find nearest open cell
               [sx sy] (if (contains? (get-in s [:map :walls]) spawn)
                         (find-nearest-open s (first spawn) (second spawn))
                         spawn)]
           (-> s
               (assoc-in [:players id :alive?] true)
               (assoc-in [:players id :hp] 100)
               (assoc-in [:players id :x] sx)
               (assoc-in [:players id :y] sy)
               (assoc-in [:players id :ammo] 5)
               (assoc-in [:players id :respawn-at] nil)))
         s))
     state (:players state))))

(defn maybe-spawn-passengers
  "Spawn new passengers if below the max count."
  [state]
  (let [max-pax (get-in state [:config :max-passengers] 6)
        alive (count (filter #(nil? (:picked-up-by %)) (:passengers state)))
        needed (- max-pax alive)]
    (if (pos? needed)
      (let [{:keys [width height walls]} (:map state)
            open-cells (for [x (range width) y (range height)
                             :when (not (contains? walls [x y]))]
                         [x y])
            open-vec (vec open-cells)]
        (reduce (fn [s i]
                  (let [pos (nth open-vec (rand-int (count open-vec)))
                        dest (nth open-vec (rand-int (count open-vec)))
                        id (str "pax-" (:tick state) "-" i)]
                    (update s :passengers conj
                            {:id id :x (first pos) :y (second pos)
                             :dest {:x (first dest) :y (second dest)}
                             :picked-up-by nil})))
                state (range needed)))
      state)))

(defn regen-ammo
  "Regenerate ammo for alive players every N ticks."
  [state]
  (let [regen-every (get-in state [:config :ammo-regen-ticks] 5)
        max-ammo (get-in state [:config :max-ammo] 10)]
    (if (zero? (mod (:tick state) regen-every))
      (reduce-kv
       (fn [s id player]
         (if (and (:alive? player) (< (:ammo player) max-ammo))
           (update-in s [:players id :ammo] inc)
           s))
       state (:players state))
      state)))

;;; ---------------------------------------------------------------------------
;;; Battle Royale Shrink — walls close in over time
;;; ---------------------------------------------------------------------------

(defn shrink-ring
  "Return the set of wall cells for a ring at distance `d` from the border.
   d=0 is the outermost ring (already walls), d=1 is one step in, etc."
  [width height d]
  (set
   (concat
    ;; Top and bottom rows at depth d
    (for [x (range d (- width d))] [x d])
    (for [x (range d (- width d))] [x (- height 1 d)])
    ;; Left and right columns at depth d
    (for [y (range d (- height d))] [d y])
    (for [y (range d (- height d))] [(- width 1 d) y]))))

(defn battle-royale-shrink
  "Shrink the arena by adding wall rings. Shrinks every `shrink-interval` ticks
   starting at `shrink-start`. Kills players caught in new walls.
   Shows a fire warning ring for `shrink-warn-ticks` before each shrink."
  [state]
  (let [tick (:tick state)
        shrink-start (get-in state [:config :shrink-start] 200)
        shrink-interval (get-in state [:config :shrink-interval] 50)
        warn-ticks (get-in state [:config :shrink-warn-ticks] 10)
        {:keys [width height]} (:map state)
        current-walls (get-in state [:map :walls])
        ;; Calculate ticks until next shrink
        next-shrink (if (< tick shrink-start)
                      shrink-start
                      (let [elapsed (- tick shrink-start)
                            next-mult (* (inc (quot elapsed shrink-interval))
                                         shrink-interval)]
                        (+ shrink-start next-mult)))
        ticks-until (- next-shrink tick)
        ;; Which ring will be added at next shrink?
        next-ring-num (if (< tick shrink-start)
                        1
                        (+ 2 (quot (- tick shrink-start) shrink-interval)))
        ;; Show fire warning if within warn window
        warning-cells (when (and (<= 1 ticks-until warn-ticks)
                                 (> next-ring-num 0))
                        (let [upcoming (shrink-ring width height next-ring-num)]
                          (clojure.set/difference upcoming current-walls)))
        state (assoc state :shrink-warning (or warning-cells #{}))]
    (if (and (>= tick shrink-start)
             (zero? (mod tick shrink-interval)))
      ;; Time to shrink — add the wall ring
      (let [rings-added (inc (quot (- tick shrink-start) shrink-interval))
            new-walls (shrink-ring width height rings-added)
            added-walls (clojure.set/difference new-walls current-walls)]
        (if (empty? added-walls)
          state
          (let [state (update-in state [:map :walls] into added-walls)
                ;; Clear warning now that walls are placed
                state (assoc state :shrink-warning #{})
                ;; Kill any player standing on a new wall
                state (reduce-kv
                       (fn [s id player]
                         (if (and (:alive? player)
                                  (contains? added-walls [(:x player) (:y player)]))
                           (-> s
                               (assoc-in [:players id :hp] 0)
                               (assoc-in [:players id :alive?] false)
                               (assoc-in [:players id :respawn-at]
                                         (+ tick (get-in state [:config :respawn-ticks] 10)))
                               (assoc-in [:players id :passenger] nil))
                           s))
                       state (:players state))
                ;; Remove passengers on new walls
                state (update state :passengers
                              (fn [ps] (vec (remove #(contains? added-walls [(:x %) (:y %)])
                                                    ps))))]
            state)))
      state)))

;;; ---------------------------------------------------------------------------
;;; Environmental Effects — Random catastrophes
;;; ---------------------------------------------------------------------------

(defn lightning-strike
  "Execute a lightning strike at [cx cy] with given radius.
   Destroys open cells (turns to walls), kills/damages players, removes passengers.
   Returns updated state with :recent-effects populated."
  [state cx cy radius]
  (let [{:keys [width height walls]} (:map state)
        ;; All cells in blast radius (manhattan distance)
        blast-cells (set (positions-in-radius [cx cy] radius (:map state)))
        ;; Only convert non-wall cells to crater walls
        new-crater (clojure.set/difference blast-cells walls)
        ;; Add crater walls
        state (update-in state [:map :walls] into new-crater)
        ;; Kill or damage players in blast zone
        state (reduce-kv
               (fn [s id player]
                 (if (and (:alive? player)
                          (contains? blast-cells [(:x player) (:y player)]))
                   (-> s
                       (assoc-in [:players id :hp] 0)
                       (assoc-in [:players id :alive?] false)
                       (assoc-in [:players id :respawn-at]
                                 (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))
                       ;; Drop passenger if carrying
                       (cond-> (get-in s [:players id :passenger])
                         (update :passengers
                                 (fn [ps]
                                   (mapv #(if (= (:id %) (get-in s [:players id :passenger :id]))
                                            (assoc % :picked-up-by nil
                                                   :x (get-in s [:players id :x])
                                                   :y (get-in s [:players id :y]))
                                            %)
                                         ps))))
                       (assoc-in [:players id :passenger] nil))
                   s))
               state (:players state))
        ;; Remove passengers in blast zone
        state (update state :passengers
                      (fn [ps] (vec (remove #(contains? new-crater [(:x %) (:y %)]) ps))))
        ;; Record crater fires — burn for N ticks
        fire-duration (get-in state [:config :crater-fire-ticks] 10)
        fire-expires (+ (:tick state) fire-duration)
        state (update state :crater-fires
                      (fn [fires]
                        (reduce #(assoc %1 %2 fire-expires)
                                (or fires {})
                                blast-cells)))
        ;; Record the effect for rendering
        effect {:type :lightning
                :x cx :y cy
                :radius radius
                :cells blast-cells}]
    (update state :recent-effects (fnil conj []) effect)))

(defn maybe-lightning-strike
  "Random chance of lightning strike each tick. Configurable frequency and radius."
  [state]
  (let [chance (get-in state [:config :lightning-chance] 0.008) ;; ~0.8% per tick
        min-radius (get-in state [:config :lightning-min-radius] 2)
        max-radius (get-in state [:config :lightning-max-radius] 4)
        ;; Don't strike too early
        grace (get-in state [:config :lightning-grace-ticks] 20)
        too-early? (< (:tick state) grace)]
    (if (or too-early? (> (rand) chance))
      state
      ;; Pick a random open cell as strike center
      (let [{:keys [width height walls]} (:map state)
            ;; Bias toward interior cells (not right on border)
            margin 3
            cx (+ margin (rand-int (max 1 (- width (* 2 margin)))))
            cy (+ margin (rand-int (max 1 (- height (* 2 margin)))))
            radius (+ min-radius (rand-int (inc (- max-radius min-radius))))]
        (lightning-strike state cx cy radius)))))

(defn expire-crater-fires
  "Remove crater fires whose timer has elapsed."
  [state]
  (let [tick (:tick state)]
    (update state :crater-fires
            (fn [fires]
              (persistent!
               (reduce-kv (fn [m pos expires]
                            (if (>= tick expires)
                              (dissoc! m pos)
                              m))
                          (transient (or fires {}))
                          (or fires {})))))))

(defn advance-tick
  "Pure function: old state + commands → new state."
  [state commands]
  (-> state
      (assoc :recent-shots []) ;; clear previous tick's shots
      (assoc :recent-effects []) ;; clear previous tick's effects
      (apply-commands commands)
      (respawn-dead-players)
      (maybe-spawn-passengers)
      (regen-ammo)
      (battle-royale-shrink)
      (maybe-lightning-strike)
      (expire-crater-fires)
      (update :tick inc)))

;;; ---------------------------------------------------------------------------
;;; Initial State
;;; ---------------------------------------------------------------------------

(defn make-initial-state
  "Create a fresh game state with the given map."
  [game-map]
  {:tick 0
   :map game-map
   :players {}
   :passengers []
   :recent-shots []
   :recent-effects []
   :crater-fires {}  ;; {[x y] expires-at-tick} — burning crater cells
   :config (config/load-config)})

(defn add-player
  "Add a player to the game. Returns [updated-state token]."
  [state player-name]
  (let [id (str "player-" (subs (str (random-uuid)) 0 8))
        token (str (random-uuid))
        spawns (get-in state [:map :spawn-points] [[1 1] [18 1] [1 18] [18 18]])
        spawn (nth spawns (mod (count (:players state)) (count spawns)))]
    [(-> state
         (assoc-in [:players id]
                   {:name player-name
                    :x (first spawn)
                    :y (second spawn)
                    :hp 100
                    :score 0
                    :passenger nil
                    :ammo 5
                    :grenades 2
                    :alive? true
                    :token token}))
     {:id id :token token}]))
