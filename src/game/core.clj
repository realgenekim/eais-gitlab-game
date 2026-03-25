(ns game.core
  "Pure game engine. All functions take state, return state. No side effects.")

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
                            (+ (:tick state) 10))
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
  "Respawn players whose respawn timer has elapsed."
  [state]
  (let [spawn-points (get-in state [:map :spawn-points] [[1 1] [18 1] [1 18] [18 18]])]
    (reduce-kv
     (fn [s id player]
       (if (and (not (:alive? player))
                (:respawn-at player)
                (>= (:tick state) (:respawn-at player)))
         (let [spawn (nth spawn-points (mod (hash id) (count spawn-points)))]
           (-> s
               (assoc-in [:players id :alive?] true)
               (assoc-in [:players id :hp] 100)
               (assoc-in [:players id :x] (first spawn))
               (assoc-in [:players id :y] (second spawn))
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

(defn advance-tick
  "Pure function: old state + commands → new state."
  [state commands]
  (-> state
      (assoc :recent-shots []) ;; clear previous tick's shots
      (apply-commands commands)
      (respawn-dead-players)
      (maybe-spawn-passengers)
      (regen-ammo)
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
   :config {:tick-ms 250
            :visibility-radius 5
            :shoot-range 20
            :shoot-damage 30
            :max-passengers 6
            :max-players 8
            :ammo-regen-ticks 5
            :max-ammo 10
            :game-duration-ticks 1000}}) ;; 1000 ticks × 500ms = ~8 min per round

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
