(ns game.core
  "Pure game engine. All functions take state, return state. No side effects."
  (:require [clojure.set]
            [game.config :as config]))

;;; ---------------------------------------------------------------------------
;;; Map & Geometry
;;; ---------------------------------------------------------------------------

(def directions
  {:north [0 -1] :south [0 1] :east [1 0] :west [-1 0]})

(def START-HP "Starting and respawn hit points for players." 500)
(def START-POINTS "Starting currency for armory + in-game crates." 30)

;;; ---------------------------------------------------------------------------
;;; Items & Loot System
;;; ---------------------------------------------------------------------------

(def armory-items
  "Items available for purchase in the armory phase."
  {:plasma-rounds   {:name "Plasma Rounds"   :cost 15 :type :weapon  :effect :damage-2x     :duration nil
                     :desc "2x shot damage (permanent)"}
   :titan-shield    {:name "Titan Shield"    :cost 12 :type :defense :effect :damage-halved  :duration nil
                     :desc "50% damage reduction (permanent)"}
   :oracle-eye      {:name "Oracle Eye"      :cost 10 :type :utility :effect :vision-2x      :duration nil
                     :desc "Double vision radius (permanent)"}
   :sprint-boots    {:name "Sprint Boots"    :cost 8  :type :utility :effect :speed-2x       :duration 80
                     :desc "Move twice per tick (80 ticks)"}
   :vampiric-rounds {:name "Vampiric Rounds" :cost 8  :type :weapon  :effect :lifesteal      :duration nil
                     :desc "Heal 15 HP per hit (permanent)"}
   :juggernaut      {:name "Juggernaut"      :cost 6  :type :defense :effect :bonus-hp       :duration nil
                     :desc "+300 bonus HP (instant)"}
   :ammo-belt       {:name "Ammo Belt"       :cost 5  :type :utility :effect :ammo-regen-2x  :duration nil
                     :desc "Double ammo regen (permanent)"}
   :cluster-shot    {:name "Cluster Shot"    :cost 5  :type :weapon  :effect :cluster        :duration 60
                     :desc "Shots hit 3-wide (60 ticks)"}})

(def secret-items
  "Hidden items — not in the catalog. Only discoverable by exploring the API."
  {:phase-cloak    {:name "Phase Cloak"    :type :utility :effect :phase-cloak    :duration 60
                    :desc "Invisible to all players for 60 ticks. Shots still work."}
   :teleporter     {:name "Teleporter"     :type :utility :effect :teleport       :duration nil
                    :desc "Instantly warp to a random open cell. One-time use."}
   :emp-blast      {:name "EMP Blast"      :type :weapon  :effect :emp            :duration nil
                    :desc "Strip all buffs from every player in vision range. One-time use."}
   :shadow-clone   {:name "Shadow Clone"   :type :utility :effect :shadow-clone   :duration 40
                    :desc "Spawn a decoy that mimics your last movement. Enemies attack it."}
   :gravity-well   {:name "Gravity Well"   :type :weapon  :effect :gravity-well   :duration nil
                    :desc "Pull all nearby players/enemies 2 cells toward you. One-time use."}})

(def crate-loot-table
  "Possible contents of in-game loot crates by tier."
  {:gold   {:cost-range [15 20]
            :items [:plasma-rounds :titan-shield :oracle-eye]}
   :silver {:cost-range [5 10]
            :items [:sprint-boots :vampiric-rounds :juggernaut :ammo-belt :cluster-shot
                    :rapid-fire :regen-field]}
   :purple {:cost-range [0 3]
            :buffs  [:sprint-boots :vampiric-rounds :ammo-belt]
            :debuffs [:drunk-controls :loud-footsteps :butterfingers :pacifist
                      :magnet :shrink-ray :reverse-controls :glass-cannon]}})

(def debuff-definitions
  "Debuff effects applied from purple crates."
  {:drunk-controls    {:name "Drunk Controls"    :duration 50 :desc "30% chance moves go random"}
   :loud-footsteps    {:name "Loud Footsteps"    :duration 60 :desc "Visible to all players"}
   :butterfingers     {:name "Butterfingers"     :duration 40 :desc "Drop passenger every 10 ticks"}
   :pacifist          {:name "Pacifist"          :duration 30 :desc "Can't shoot"}
   :magnet            {:name "Magnet"            :duration 50 :desc "Enemies target you first"}
   :shrink-ray        {:name "Shrink Ray"        :duration 40 :desc "Vision radius = 2"}
   :reverse-controls  {:name "Reverse Controls"  :duration 30 :desc "Directions inverted"}
   :glass-cannon      {:name "Glass Cannon"      :duration 60 :desc "3x damage dealt AND taken"}})

(defn in-bounds? [{:keys [width height]} [x y]]
  (and (>= x 0) (< x width) (>= y 0) (< y height)))

(defn wall? [game-state [x y]]
  (contains? (get-in game-state [:map :walls]) [x y]))

(defn walkable? [game-state pos]
  (and (in-bounds? (:map game-state) pos)
       (not (wall? game-state pos))))

(defn occupied-cells
  "Set of all cells occupied by alive players and enemies."
  [state]
  (let [player-cells (set (for [[_ p] (:players state)
                                :when (:alive? p)]
                            [(:x p) (:y p)]))
        enemy-cells  (set (for [[_ e] (or (:enemies state) {})]
                            [(:x e) (:y e)]))]
    (into player-cells enemy-cells)))

(defn cell-free?
  "Is pos walkable AND not occupied by any player or enemy?"
  [state pos]
  (and (walkable? state pos)
       (not (contains? (occupied-cells state) pos))))

;;; ---------------------------------------------------------------------------
;;; Knockback — TMNT arcade-style pushback on hit
;;; ---------------------------------------------------------------------------

(defn knockback-dest
  "Calculate knockback destination: push [x y] away from [sx sy] by up to `dist` cells.
   Stops at walls and occupied cells. Returns [final-x final-y cells-moved]."
  [state x y sx sy dist exclude-pos]
  (let [;; Push direction: away from source
        dx (compare x sx)  ;; -1, 0, or 1
        dy (compare y sy)
        ;; If same position, pick a random push direction
        [dx dy] (if (and (zero? dx) (zero? dy))
                  (rand-nth [[1 0] [-1 0] [0 1] [0 -1]])
                  [dx dy])
        occupied (disj (occupied-cells state) exclude-pos)]
    (loop [cx x cy y moved 0]
      (if (>= moved dist)
        [cx cy moved]
        (let [nx (+ cx dx) ny (+ cy dy)]
          (if (and (walkable? state [nx ny])
                   (not (contains? occupied [nx ny])))
            (recur nx ny (inc moved))
            ;; Blocked — try perpendicular slide
            (let [perps (if (zero? dx) [[1 0] [-1 0]] [[0 1] [0 -1]])
                  open? (fn [[pdx pdy]]
                          (let [tx2 (+ cx pdx) ty2 (+ cy pdy)]
                            (and (walkable? state [tx2 ty2])
                                 (not (contains? occupied [tx2 ty2])))))
                  slide (first (filter open? perps))]
              (if (and slide (< moved 1))
                [(+ cx (first slide)) (+ cy (second slide)) (inc moved)]
                [cx cy moved]))))))))

(defn apply-knockback-player
  "Push a player away from [sx sy]. Clears their last-direction to prevent oscillation lock."
  [state player-id sx sy dist]
  (let [p (get-in state [:players player-id])
        [nx ny moved] (knockback-dest state (:x p) (:y p) sx sy dist [(:x p) (:y p)])]
    (if (pos? moved)
      (-> state
          (assoc-in [:players player-id :x] nx)
          (assoc-in [:players player-id :y] ny)
          (assoc-in [:players player-id :last-direction] nil)
          (assoc-in [:players player-id :prev-direction] nil))
      state)))

(defn apply-knockback-enemy
  "Push an enemy away from [sx sy]."
  [state enemy-id sx sy dist]
  (let [e (get-in state [:enemies enemy-id])]
    (if e
      (let [[nx ny _] (knockback-dest state (:x e) (:y e) sx sy dist [(:x e) (:y e)])]
        (-> state
            (assoc-in [:enemies enemy-id :x] nx)
            (assoc-in [:enemies enemy-id :y] ny)))
      state)))

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
;;; Buff / Debuff helpers
;;; ---------------------------------------------------------------------------

(defn has-buff? [player buff-key]
  (contains? (or (:buffs player) {}) buff-key))

(defn has-debuff? [player debuff-key]
  (contains? (or (:debuffs player) {}) debuff-key))

;; Forward declarations for armory/crate functions defined later in file
(declare expire-buffs-debuffs apply-buff-effects buy-item
         pickup-crate maybe-spawn-crates find-nearest-open)

;;; ---------------------------------------------------------------------------
;;; Visibility / Fog of War
;;; ---------------------------------------------------------------------------

(defn visible-positions
  "Returns set of positions visible to a player (manhattan radius)."
  [game-state player-id]
  (let [player (get-in game-state [:players player-id])
        base-radius (get-in game-state [:config :visibility-radius] 5)
        radius (cond
                 (has-debuff? player :shrink-ray) 2
                 (has-buff? player :vision-2x) (* 2 base-radius)
                 :else base-radius)]
    (set (positions-in-radius [(:x player) (:y player)] radius (:map game-state)))))

(defn player-view
  "Build the fog-of-war view for a specific player.
   Includes visible enemies, players, shots so bots can decide what to shoot."
  [game-state player-id]
  (let [visible (visible-positions game-state player-id)
        me (get-in game-state [:players player-id])
        others (->> (:players game-state)
                    (remove (fn [[id _]] (= id player-id)))
                    (filter (fn [[_ p]] (and (:alive? p)
                                             (visible [(:x p) (:y p)])
                                             (not (has-buff? p :phase-cloak)))))
                    (map (fn [[id p]]
                           {:id id :x (:x p) :y (:y p)
                            :has-passenger (some? (:passenger p))})))
        enemies (->> (or (:enemies game-state) {})
                     (filter (fn [[_ e]] (visible [(:x e) (:y e)])))
                     (map (fn [[id e]]
                            {:id id :x (:x e) :y (:y e)
                             :hp (:hp e) :type (name (:type e))})))
        passengers (->> (:passengers game-state)
                        (filter (fn [p] (and (nil? (:picked-up-by p))
                                             (visible [(:x p) (:y p)]))))
                        (map #(select-keys % [:id :x :y :dest])))
        shots (->> (:recent-shots game-state)
                   (filter (fn [shot] (some visible (:path shot))))
                   (map (fn [shot]
                          {:shooter-id (:shooter-id shot)
                           :origin (:origin shot)
                           :direction (:direction shot)
                           :path (vec (filter visible (:path shot)))})))
        crates (->> (or (:crates game-state) {})
                    (filter (fn [[_ c]] (visible [(:x c) (:y c)])))
                    (map (fn [[_ c]]
                           {:id (:id c) :x (:x c) :y (:y c)
                            :tier (name (:tier c)) :cost (:cost c)})))]
    {:tick (:tick game-state)
     :you (-> me
              (select-keys [:x :y :hp :score :passenger :ammo :grenades :alive?
                            :points :items :buffs :debuffs])
              (assoc :id player-id))
     :visible {:players (vec others)
               :enemies (vec enemies)
               :passengers (vec passengers)
               :shots (vec shots)
               :crates (vec crates)}
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

(declare pickup-crate maybe-spawn-crates expire-buffs-debuffs)

(defmulti apply-action
  "Apply a single player action. Returns updated game-state."
  (fn [_state _player-id action] (:type action)))

(def opposite-dir
  {:north :south :south :north :east :west :west :east})

(defmethod apply-action :move
  [state player-id {:keys [direction]}]
  (let [player (get-in state [:players player-id])
        dir-kw (keyword direction)
        ;; Apply debuff modifications to direction
        dir-kw (cond
                 ;; Drunk controls: 30% chance of random direction
                 (and (has-debuff? player :drunk-controls) (< (rand) 0.3))
                 (rand-nth [:north :south :east :west])
                 ;; Reverse controls: invert direction
                 (has-debuff? player :reverse-controls)
                 (get opposite-dir dir-kw dir-kw)
                 :else dir-kw)
        [dx dy] (get directions dir-kw [0 0])
        new-pos [(+ (:x player) dx) (+ (:y player) dy)]
        ;; Block rapid oscillation: only reject reversal if last TWO moves
        ;; were opposite (A→B→A pattern), not just one reversal
        last-dir (:last-direction player)
        prev-dir (:prev-direction player)
        oscillating? (and last-dir prev-dir
                         (= dir-kw (opposite-dir last-dir))
                         (= dir-kw prev-dir))  ;; A→B→A pattern
        ;; Occupied by anyone OTHER than this player
        others (disj (occupied-cells state) [(:x player) (:y player)])]
    (if (and (:alive? player)
             (not oscillating?)
             (walkable? state new-pos)
             (not (contains? others new-pos)))
      (-> state
          (assoc-in [:players player-id :x] (first new-pos))
          (assoc-in [:players player-id :y] (second new-pos))
          (assoc-in [:players player-id :prev-direction] last-dir)
          (assoc-in [:players player-id :last-direction] dir-kw))
      state)))

(defmethod apply-action :pickup
  [state player-id _action]
  (let [player (get-in state [:players player-id])
        px (:x player) py (:y player)]
    (if (not (:alive? player))
      state
      ;; Try picking up a crate first
      (let [crate-entry (first (filter (fn [[_ c]] (and (= (:x c) px) (= (:y c) py)))
                                       (or (:crates state) {})))]
        (if crate-entry
          ;; Crate found — attempt pickup (checks cost)
          (let [[new-state _result] (pickup-crate state player-id)]
            new-state)
          ;; No crate — try passenger pickup
          (if (:passenger player)
            state
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
                state))))))))

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
        damage (get-in state [:config :shoot-damage] 30)
        kill-bonus (get-in state [:config :kill-bonus] 150)
        enemies (or (:enemies state) {})]
    (if (or (not (:alive? player))
            (< (:ammo player) 1)
            (= [dx dy] [0 0])
            (has-debuff? player :pacifist))
      state
      (let [;; Build lookup of enemy positions
            enemy-at (into {} (for [[eid e] enemies] [[(:x e) (:y e)] eid]))
            ;; Trace the shot — check players AND enemies
            {:keys [path hit-id hit-enemy-id]}
            (loop [x (+ (:x player) dx)
                   y (+ (:y player) dy)
                   dist 1
                   cells []]
              (cond
                (> dist range-)
                {:path cells :hit-id nil :hit-enemy-id nil}

                (not (in-bounds? (:map state) [x y]))
                {:path cells :hit-id nil :hit-enemy-id nil}

                (wall? state [x y])
                {:path cells :hit-id nil :hit-enemy-id nil}

                :else
                (let [;; Check for player hit
                      target-player (first (keep (fn [[id p]]
                                                   (when (and (not= id player-id)
                                                              (:alive? p)
                                                              (= (:x p) x)
                                                              (= (:y p) y))
                                                     id))
                                                 (:players state)))
                      ;; Check for enemy hit
                      target-enemy (get enemy-at [x y])]
                  (cond
                    target-player
                    {:path (conj cells [x y]) :hit-id target-player :hit-enemy-id nil}

                    target-enemy
                    {:path (conj cells [x y]) :hit-id nil :hit-enemy-id target-enemy}

                    :else
                    (recur (+ x dx) (+ y dy) (inc dist)
                           (conj cells [x y]))))))
            ;; Record shot
            shot {:shooter-id player-id
                  :origin [(:x player) (:y player)]
                  :direction (keyword direction)
                  :path path
                  :hit-id (or hit-id hit-enemy-id)
                  :fired-tick (:tick state)}
            state (-> state
                      (update-in [:players player-id :ammo] dec)
                      (update :recent-shots conj shot))]
        (let [shooter-x (:x player) shooter-y (:y player)]
          (cond
            ;; Hit a player — damage + KNOCKBACK
            hit-id
            (let [;; Apply damage modifiers from buffs/debuffs
                  effective-damage (cond-> damage
                                    (has-buff? player :damage-2x) (* 2)
                                    (has-debuff? player :glass-cannon) (* 3))
                  ;; Target damage reduction
                  target (get-in state [:players hit-id])
                  effective-damage (cond-> effective-damage
                                    (has-buff? target :damage-halved) (quot 2)
                                    (has-debuff? target :glass-cannon) (* 3))
                  new-hp (- (get-in state [:players hit-id :hp]) effective-damage)]
              (if (<= new-hp 0)
                (-> state
                    (assoc-in [:players hit-id :hp] 0)
                    (assoc-in [:players hit-id :alive?] false)
                    (assoc-in [:players hit-id :respawn-at]
                              (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))
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
                    (update-in [:players player-id :score] + kill-bonus))
                ;; Alive hit — damage + knockback 2 cells away from shooter
                (-> state
                    (assoc-in [:players hit-id :hp] new-hp)
                    (apply-knockback-player hit-id shooter-x shooter-y 2)
                    ;; Lifesteal: heal shooter 15 HP per hit
                    (cond-> (has-buff? player :lifesteal)
                      (update-in [:players player-id :hp]
                                 #(min (+ % 15) START-HP))))))

            ;; Hit an enemy — damage + KNOCKBACK
            hit-enemy-id
            (let [enemy (get-in state [:enemies hit-enemy-id])
                  new-hp (- (:hp enemy) damage)]
              (if (<= new-hp 0)
                ;; Kill enemy — remove it, award score
                (-> state
                    (update :enemies dissoc hit-enemy-id)
                    (update-in [:players player-id :score] + (:score enemy 10)))
                ;; Alive hit — damage + knockback 3 cells
                (-> state
                    (assoc-in [:enemies hit-enemy-id :hp] new-hp)
                    (apply-knockback-enemy hit-enemy-id shooter-x shooter-y 3))))

            :else state))))))

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

(defn find-nearest-open
  "Find the nearest open (non-wall) cell to [x y] using BFS."
  [state x y]
  (let [walls (get-in state [:map :walls])
        {:keys [width height]} (:map state)]
    (if (not (contains? walls [x y]))
      [x y]
      (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [x y])
             visited #{[x y]}]
        (if (empty? queue)
          ;; Fallback: center of map
          [(quot width 2) (quot height 2)]
          (let [[cx cy] (peek queue)
                neighbors (for [[dx dy] [[0 1] [0 -1] [1 0] [-1 0]]
                                :let [nx (+ cx dx) ny (+ cy dy)]
                                :when (and (>= nx 0) (< nx width)
                                           (>= ny 0) (< ny height)
                                           (not (visited [nx ny])))]
                            [nx ny])
                open (first (filter #(not (contains? walls %)) neighbors))]
            (if open
              open
              (recur (into (pop queue) (remove visited neighbors))
                     (into visited neighbors)))))))))

(defn respawn-dead-players
  "Respawn players whose respawn timer has elapsed.
   If spawn point is a wall or occupied, bump to nearest free cell."
  [state]
  (let [{:keys [width height walls]} (:map state)
        cx (quot width 2) cy (quot height 2)]
    (reduce-kv
     (fn [s id player]
       (if (and (not (:alive? player))
                (:respawn-at player)
                (>= (:tick state) (:respawn-at player)))
         (let [occupied (occupied-cells s)
               ;; Respawn near CENTER — never in corners
               center-cells (vec (for [x (range (max 1 (- cx 3)) (min (dec width) (+ cx 4)))
                                       y (range (max 1 (- cy 3)) (min (dec height) (+ cy 4)))
                                       :when (and (not (contains? walls [x y]))
                                                  (not (contains? occupied [x y])))]
                                   [x y]))
               [sx sy] (if (seq center-cells)
                         (rand-nth center-cells)
                         (find-nearest-open s cx cy))]
           (-> s
               (assoc-in [:players id :alive?] true)
               (assoc-in [:players id :hp] 500)
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
  "Return the set of wall cells for ALL cells at depth < d from the border.
   d=1 fills the outermost ring, d=2 fills the two outermost rings, etc.
   This ensures the area outside the playable zone is fully walled."
  [width height d]
  (set
   (for [x (range width)
         y (range height)
         :when (or (< x d) (>= x (- width d))
                   (< y d) (>= y (- height d)))]
     [x y])))

(defn battle-royale-shrink
  "Shrink the arena by adding wall rings. Shrinks every `shrink-interval` ticks
   starting at `shrink-start`. Kills players/enemies caught in new walls.
   Shows a fire warning ring for `shrink-warn-ticks` before each shrink.
   Stops shrinking when the playable area would be smaller than 3x3."
  [state]
  (let [tick (:tick state)
        shrink-start (get-in state [:config :shrink-start] 200)
        shrink-interval (get-in state [:config :shrink-interval] 50)
        warn-ticks (get-in state [:config :shrink-warn-ticks] 10)
        {:keys [width height]} (:map state)
        current-walls (get-in state [:map :walls])
        ;; Max ring depth — leave at least 3x3 playable area
        max-ring (min (quot (dec width) 2) (quot (dec height) 2))
        max-ring (max 1 (- max-ring 1))
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
                        (min (+ 2 (quot (- tick shrink-start) shrink-interval))
                             max-ring))
        ;; Show fire warning if within warn window
        warning-cells (when (and (<= 1 ticks-until warn-ticks)
                                 (> next-ring-num 0))
                        (let [upcoming (shrink-ring width height next-ring-num)]
                          (clojure.set/difference upcoming current-walls)))
        state (assoc state :shrink-warning (or warning-cells #{}))]
    (if (and (>= tick shrink-start)
             (zero? (mod tick shrink-interval)))
      ;; Time to shrink — add the wall ring
      (let [rings-added (min (inc (quot (- tick shrink-start) shrink-interval))
                             max-ring)
            new-walls (shrink-ring width height rings-added)
            added-walls (clojure.set/difference new-walls current-walls)]
        (if (empty? added-walls)
          state
          (let [state (update-in state [:map :walls] into added-walls)
                state (assoc state :shrink-warning #{})
                ;; Kill players standing on new walls
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
                ;; Remove enemies standing on new walls
                state (update state :enemies
                              (fn [enemies]
                                (into {} (remove (fn [[_ e]]
                                                   (contains? added-walls [(:x e) (:y e)]))
                                                 enemies))))
                ;; Remove passengers on new walls
                state (update state :passengers
                              (fn [ps] (vec (remove #(contains? added-walls [(:x %) (:y %)])
                                                    ps))))
                ;; End game when final ring is placed
                state (if (>= rings-added max-ring)
                        (assoc-in state [:config :game-duration-ticks] (+ tick 1))
                        state)]
            state)))
      state)))

;;; ---------------------------------------------------------------------------
;;; Environmental Effects — Random catastrophes
;;; ---------------------------------------------------------------------------

(defn lightning-strike
  "Execute a lightning strike at [cx cy] with given radius.
   Destroys walls in the blast zone (opens up terrain), kills/damages players,
   removes passengers. Returns updated state with :recent-effects populated."
  [state cx cy radius]
  (let [{:keys [width height walls]} (:map state)
        ;; All cells in blast radius (manhattan distance)
        blast-cells (set (positions-in-radius [cx cy] radius (:map state)))
        ;; Walls that get destroyed (opened up)
        destroyed-walls (clojure.set/intersection blast-cells walls)
        ;; Remove destroyed walls from map
        state (update-in state [:map :walls] #(clojure.set/difference % destroyed-walls))
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
                      (fn [ps] (vec (remove #(contains? blast-cells [(:x %) (:y %)]) ps))))
        ;; Record the effect for rendering
        effect {:type :lightning
                :x cx :y cy
                :radius radius
                :cells blast-cells
                :destroyed-walls (count destroyed-walls)}]
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

;;; ---------------------------------------------------------------------------
;;; Enemies — server-side mobs for VS mode
;;; ---------------------------------------------------------------------------

(def enemy-types
  "Enemy type definitions — weak enough to be fun, not frustrating."
  {:floopy {:hp 10 :speed 1 :damage 5 :score 10}
   :squanchy {:hp 25 :speed 1 :damage 8 :score 25}
   :scary {:hp 40 :speed 1 :damage 12 :score 50}})

(defn spawn-enemies
  "Spawn a wave of enemies at random edge cells. No cell sharing."
  [state enemy-type count-to-spawn]
  (let [{:keys [width height walls]} (:map state)
        edge-cells (concat
                    (for [x (range 1 (dec width))] [x 1])
                    (for [x (range 1 (dec width))] [x (- height 2)])
                    (for [y (range 1 (dec height))] [1 y])
                    (for [y (range 1 (dec height))] [(- width 2) y]))
        type-info (get enemy-types enemy-type {:hp 20 :speed 1 :damage 10 :score 10})]
    (reduce (fn [s i]
              (let [occupied (occupied-cells s)
                    open-edges (vec (remove #(or (contains? walls %)
                                                 (contains? occupied %))
                                           edge-cells))]
                (if (empty? open-edges)
                  s
                  (let [pos (nth open-edges (rand-int (count open-edges)))
                        id (str "enemy-" (:tick state) "-" i)]
                    (assoc-in s [:enemies id]
                              {:x (first pos)
                               :y (second pos)
                               :hp (:hp type-info)
                               :max-hp (:hp type-info)
                               :speed (:speed type-info)
                               :damage (:damage type-info)
                               :score (:score type-info)
                               :type enemy-type})))))
            state (range count-to-spawn))))

(defn move-enemies
  "Move each enemy one cell toward the nearest alive player.
   Enemies cannot share cells with players or other enemies."
  [state]
  (let [alive-players (->> (:players state)
                           (filter (fn [[_ p]] (:alive? p)))
                           (map (fn [[_ p]] [(:x p) (:y p)])))
        walls (get-in state [:map :walls])]
    (if (empty? alive-players)
      state
      ;; Process enemies sequentially so each one sees updated positions
      (reduce-kv
       (fn [s id enemy]
         (let [ex (:x enemy) ey (:y enemy)
               ;; Build occupied set from current state (excluding this enemy)
               occupied (disj (occupied-cells s) [ex ey])
               blocked? (fn [[x y]] (or (contains? walls [x y])
                                        (contains? occupied [x y])
                                        (not (in-bounds? (:map s) [x y]))))
               ;; Find nearest player
               nearest (apply min-key
                              (fn [[px py]] (manhattan-distance [ex ey] [px py]))
                              alive-players)
               [px py] nearest
               dx (compare px ex)
               dy (compare py ey)
               mx (Math/abs (- px ex))
               my (Math/abs (- py ey))
               ;; Try primary direction, then alternate, then stay
               [nx ny] (let [primary (if (>= mx my) [(+ ex dx) ey] [ex (+ ey dy)])
                             alt     (if (>= mx my) [ex (+ ey dy)] [(+ ex dx) ey])]
                          (cond
                            (not (blocked? primary)) primary
                            (not (blocked? alt))     alt
                            :else                    [ex ey]))]
           (-> s
               (assoc-in [:enemies id :x] nx)
               (assoc-in [:enemies id :y] ny))))
       state (or (:enemies state) {})))))

(defn enemy-player-collisions
  "Enemies adjacent to a player (manhattan distance 1) deal damage + knockback.
   TMNT arcade style — getting hit flings you away."
  [state]
  (let [alive-players (vec (for [[id p] (:players state) :when (:alive? p)]
                             [id (:x p) (:y p)]))]
    (reduce-kv
     (fn [s eid enemy]
       (let [ex (:x enemy) ey (:y enemy)
             ;; Find any adjacent player (use current positions from state s)
             adjacent (first (filter (fn [[pid _ _]]
                                       (let [p (get-in s [:players pid])]
                                         (and (:alive? p)
                                              (= 1 (manhattan-distance
                                                     [ex ey]
                                                     [(:x p) (:y p)])))))
                                     alive-players))]
         (if adjacent
           (let [[pid _ _] adjacent
                 damage (:damage enemy 10)
                 new-hp (max 0 (- (get-in s [:players pid :hp]) damage))]
             (-> s
                 (assoc-in [:players pid :hp] new-hp)
                 (cond-> (zero? new-hp)
                   (-> (assoc-in [:players pid :alive?] false)
                       (assoc-in [:players pid :respawn-at]
                                 (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))))
                 ;; KNOCKBACK — push player 1 cell away from the enemy
                 (cond-> (pos? new-hp)
                   (apply-knockback-player pid ex ey 1))))
           s)))
     state (or (:enemies state) {}))))

(defn maybe-spawn-wave
  "Spawn enemy waves on a schedule. Escalating difficulty."
  [state]
  (let [tick (:tick state)
        wave-interval (get-in state [:config :wave-interval] 40) ;; every 10 seconds at 250ms/tick
        grace (get-in state [:config :enemy-grace-ticks] 20)]
    (if (or (< tick grace)
            (not (zero? (mod tick wave-interval))))
      state
      ;; Determine wave composition — cap total enemies at 20
      (let [wave-num (inc (or (:wave-number state) 0))
            current-enemies (count (or (:enemies state) {}))
            max-enemies 15]
        (if (>= current-enemies max-enemies)
          ;; Already at cap — just bump wave number, don't spawn
          (assoc state :wave-number wave-num)
          ;; Spawn up to the cap
          (let [room (- max-enemies current-enemies)
                floopy-count (min room (max 2 (min 5 (+ 2 (quot wave-num 2)))))
                room2 (- room floopy-count)
                squanchy-count (if (>= wave-num 3) (min room2 (min 2 (quot wave-num 3))) 0)
                room3 (- room2 squanchy-count)
                scary-count (if (>= wave-num 6) (min room3 1) 0)]
            (-> state
                (assoc :wave-number wave-num)
                (spawn-enemies :floopy floopy-count)
                (cond-> (pos? squanchy-count) (spawn-enemies :squanchy squanchy-count))
                (cond-> (pos? scary-count) (spawn-enemies :scary scary-count)))))))))

(defn nudge-stalled-players
  "If a player hasn't moved in 5+ ticks, force them to move.
   If completely trapped (no adjacent open cell), teleport to center area."
  [state]
  (reduce-kv
   (fn [s id player]
     (if (and (:alive? player)
              (:last-move-tick player)
              (> (- (:tick state) (:last-move-tick player)) 5))
       ;; Stuck! Try adjacent first
       (let [px (:x player) py (:y player)
             occupied (disj (occupied-cells s) [px py])
             candidates (for [[dx dy] [[0 -1] [0 1] [1 0] [-1 0]]
                              :let [nx (+ px dx) ny (+ py dy)]
                              :when (and (walkable? s [nx ny])
                                         (not (contains? occupied [nx ny])))]
                          [nx ny])]
         (if (seq candidates)
           (let [[nx ny] (rand-nth (vec candidates))]
             (-> s
                 (assoc-in [:players id :x] nx)
                 (assoc-in [:players id :y] ny)
                 (assoc-in [:players id :last-move-tick] (:tick state))
                 (assoc-in [:players id :last-direction] nil)
                 (assoc-in [:players id :prev-direction] nil)))
           ;; Completely trapped — teleport to center area
           (let [{:keys [width height walls]} (:map s)
                 cx (quot width 2) cy (quot height 2)
                 ;; Find open cells near center
                 center-cells (for [x (range (max 1 (- cx 4)) (min (dec width) (+ cx 5)))
                                    y (range (max 1 (- cy 4)) (min (dec height) (+ cy 5)))
                                    :when (and (not (contains? walls [x y]))
                                               (not (contains? occupied [x y])))]
                                [x y])]
             (if (seq center-cells)
               (let [[nx ny] (rand-nth (vec center-cells))]
                 (-> s
                     (assoc-in [:players id :x] nx)
                     (assoc-in [:players id :y] ny)
                     (assoc-in [:players id :last-move-tick] (:tick state))
                     (assoc-in [:players id :last-direction] nil)
                     (assoc-in [:players id :prev-direction] nil)))
               s))))
       s))
   state (:players state)))

(defn track-movement
  "Track when players last moved (for stall detection)."
  [old-state new-state]
  (reduce-kv
   (fn [s id player]
     (let [old-p (get-in old-state [:players id])]
       (if (and old-p
                (or (not= (:x player) (:x old-p))
                    (not= (:y player) (:y old-p))))
         (assoc-in s [:players id :last-move-tick] (:tick new-state))
         s)))
   new-state (:players new-state)))

(defn advance-tick
  "Pure function: old state + commands → new state."
  [state commands]
  (let [old-state state
        new-state (-> state
                      ;; Keep shots for 3 ticks so spectator client always sees them
                      (update :recent-shots (fn [shots]
                                              (vec (filter #(>= (+ (or (:fired-tick %) 0) 3)
                                                                (:tick state))
                                                           shots))))
                      (assoc :recent-effects [])
                      (apply-commands commands)
                      (respawn-dead-players)
                      (maybe-spawn-passengers)
                      (regen-ammo)
                      ;; Enemy system
                      (maybe-spawn-wave)
                      (move-enemies)
                      (enemy-player-collisions)
                      ;; Anti-stall: nudge frozen players
                      (nudge-stalled-players)
                      ;; Environment
                      (battle-royale-shrink)
                      (maybe-lightning-strike)
                      (expire-crater-fires)
                      ;; Loot crate system
                      (maybe-spawn-crates)
                      (expire-buffs-debuffs)
                      (update :tick inc))]
    (track-movement old-state new-state)))

;;; ---------------------------------------------------------------------------
;;; Initial State
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; Armory & Loot Crate Logic
;;; ---------------------------------------------------------------------------

(defn buy-item
  "Attempt to buy an armory item. Returns [updated-state result-map].
   Result has :success? and :reason."
  [state player-id item-key]
  (let [player (get-in state [:players player-id])
        item (get armory-items item-key)]
    (cond
      (nil? player)
      [state {:success? false :reason "Player not found"}]

      (nil? item)
      [state {:success? false :reason "Item not found"}]

      (< (:points player 0) (:cost item))
      [state {:success? false :reason "Not enough points"
              :have (:points player 0) :need (:cost item)}]

      (>= (count (:items player [])) 3)
      [state {:success? false :reason "Inventory full (max 3 items)"}]

      (some #{item-key} (:items player []))
      [state {:success? false :reason "Already own this item"}]

      :else
      (let [new-state (-> state
                          (update-in [:players player-id :points] - (:cost item))
                          (update-in [:players player-id :items] (fnil conj []) item-key)
                          ;; Apply instant effects
                          (cond->
                            (= (:effect item) :bonus-hp)
                            (update-in [:players player-id :hp] + 300)

                            (= (:effect item) :damage-2x)
                            (assoc-in [:players player-id :buffs :damage-2x] {:permanent true})

                            (= (:effect item) :damage-halved)
                            (assoc-in [:players player-id :buffs :damage-halved] {:permanent true})

                            (= (:effect item) :vision-2x)
                            (assoc-in [:players player-id :buffs :vision-2x] {:permanent true})

                            (= (:effect item) :lifesteal)
                            (assoc-in [:players player-id :buffs :lifesteal] {:permanent true})

                            (= (:effect item) :ammo-regen-2x)
                            (assoc-in [:players player-id :buffs :ammo-regen-2x] {:permanent true})

                            (and (:duration item) (:effect item))
                            (assoc-in [:players player-id :buffs (:effect item)]
                                      {:expires-at (+ (:tick state) (:duration item))})))]
        [new-state {:success? true :item item-key :cost (:cost item)
                    :remaining (:points (get-in new-state [:players player-id]))}]))))

(defn spawn-crate
  "Spawn a loot crate at a random open cell."
  [state tier]
  (let [{:keys [width height walls]} (:map state)
        occupied (occupied-cells state)
        crate-cells (set (map (fn [c] [(:x c) (:y c)]) (vals (or (:crates state) {}))))
        open-cells (vec (for [x (range 1 (dec width))
                              y (range 1 (dec height))
                              :when (and (not (contains? walls [x y]))
                                         (not (contains? occupied [x y]))
                                         (not (contains? crate-cells [x y])))]
                          [x y]))]
    (if (empty? open-cells)
      state
      (let [pos (nth open-cells (rand-int (count open-cells)))
            tier-info (get crate-loot-table tier)
            [min-cost max-cost] (:cost-range tier-info)
            cost (+ min-cost (rand-int (max 1 (inc (- max-cost min-cost)))))
            id (str "crate-" (:tick state) "-" (rand-int 9999))
            ;; Determine contents (hidden until pickup)
            contents (case tier
                       :gold   (rand-nth (:items tier-info))
                       :silver (rand-nth (:items tier-info))
                       :purple (if (< (rand) 0.6)
                                 {:type :buff  :item (rand-nth (:buffs tier-info))}
                                 {:type :debuff :item (rand-nth (:debuffs tier-info))}))]
        (assoc-in state [:crates id]
                  {:id id :x (first pos) :y (second pos)
                   :tier tier :cost cost :contents contents
                   :spawned-at (:tick state)})))))

(defn maybe-spawn-crates
  "Spawn crates during gameplay to maintain desired counts."
  [state]
  (let [crates (vals (or (:crates state) {}))
        gold-count (count (filter #(= :gold (:tier %)) crates))
        silver-count (count (filter #(= :silver (:tier %)) crates))
        purple-count (count (filter #(= :purple (:tier %)) crates))
        tick (:tick state)
        ;; Only spawn every 30 ticks to avoid flooding
        spawn-interval 30]
    (if (not (zero? (mod tick spawn-interval)))
      state
      (-> state
          (cond->
            (< gold-count 1)   (spawn-crate :gold)
            (< silver-count 2) (spawn-crate :silver)
            (< purple-count 2) (spawn-crate :purple))))))

(defn pickup-crate
  "Player picks up a crate at their position. Returns [state result]."
  [state player-id]
  (let [player (get-in state [:players player-id])
        px (:x player) py (:y player)
        crate-entry (first (filter (fn [[_ c]] (and (= (:x c) px) (= (:y c) py)))
                                   (or (:crates state) {})))]
    (if-not crate-entry
      [state {:success? false :reason "No crate here"}]
      (let [[crate-id crate] crate-entry
            cost (:cost crate)]
        (if (< (:points player 0) cost)
          [state {:success? false :reason "Not enough points"
                  :have (:points player 0) :need cost}]
          (let [contents (:contents crate)
                ;; Resolve what the player gets
                [buff-key is-debuff?]
                (cond
                  ;; Gold/silver: contents is just an item keyword
                  (keyword? contents)
                  [contents false]
                  ;; Purple: contents is {:type :buff/:debuff :item keyword}
                  (= :buff (:type contents))
                  [(:item contents) false]
                  :else
                  [(:item contents) true])

                ;; Apply effect
                state (-> state
                          (update-in [:players player-id :points] - cost)
                          (update :crates dissoc crate-id))

                state (if is-debuff?
                        ;; Apply debuff
                        (let [debuff (get debuff-definitions buff-key)
                              expires (+ (:tick state) (:duration debuff 40))]
                          (assoc-in state [:players player-id :debuffs buff-key]
                                    {:expires-at expires}))
                        ;; Apply buff (same as armory item or from silver extras)
                        (let [item-def (get armory-items buff-key)
                              duration (or (:duration item-def) nil)]
                          (if duration
                            (assoc-in state [:players player-id :buffs (or (:effect item-def) buff-key)]
                                      {:expires-at (+ (:tick state) duration)})
                            (assoc-in state [:players player-id :buffs (or (:effect item-def) buff-key)]
                                      {:permanent true}))))]
            [state {:success? true
                    :crate-id crate-id
                    :tier (:tier crate)
                    :cost cost
                    :item buff-key
                    :is-debuff is-debuff?
                    :item-name (if is-debuff?
                                 (:name (get debuff-definitions buff-key))
                                 (:name (get armory-items buff-key) (name buff-key)))}]))))))

(defn expire-buffs-debuffs
  "Remove timed buffs and debuffs that have expired."
  [state]
  (let [tick (:tick state)]
    (reduce-kv
     (fn [s id player]
       (let [buffs (reduce-kv
                    (fn [m k v]
                      (if (and (:expires-at v) (>= tick (:expires-at v)))
                        (dissoc m k)
                        m))
                    (or (:buffs player) {})
                    (or (:buffs player) {}))
             debuffs (reduce-kv
                      (fn [m k v]
                        (if (and (:expires-at v) (>= tick (:expires-at v)))
                          (dissoc m k)
                          m))
                      (or (:debuffs player) {})
                      (or (:debuffs player) {}))]
         (-> s
             (assoc-in [:players id :buffs] buffs)
             (assoc-in [:players id :debuffs] debuffs))))
     state (:players state))))

(defn has-buff? [player buff-key]
  (contains? (or (:buffs player) {}) buff-key))

(defn has-debuff? [player debuff-key]
  (contains? (or (:debuffs player) {}) debuff-key))

(defn make-initial-state
  "Create a fresh game state with the given map."
  [game-map]
  {:tick 0
   :map game-map
   :players {}
   :enemies {}
   :passengers []
   :crates {}
   :recent-shots []
   :recent-effects []
   :crater-fires {}
   :config (config/load-config)})

(defn name-taken?
  "Check if a player name is already in use."
  [state player-name]
  (some (fn [[_ p]] (= (:name p) player-name)) (:players state)))

(defn taken-gear
  "Set of gear item keys already claimed by any player."
  [state]
  (set (mapcat (fn [[_ p]] (or (:gear p) [])) (:players state))))

(defn available-gear
  "Return the armory catalog with availability. Taken items marked :available false."
  [state]
  (let [taken (taken-gear state)]
    (into {}
          (map (fn [[k v]]
                 [k (assoc v :available (not (contains? taken k)))])
               armory-items))))

(defn select-gear
  "Player selects a gear item. Returns [updated-state success?].
   Fails if item doesn't exist, is already taken, or player already has it."
  [state player-id item-key]
  (let [taken (taken-gear state)
        player (get-in state [:players player-id])
        player-gear (set (or (:gear player) []))]
    (cond
      (not (contains? armory-items item-key))
      [state false "Item does not exist"]

      (contains? taken item-key)
      [state false "Item already taken by another player"]

      (contains? player-gear item-key)
      [state false "You already have this item"]

      :else
      [(update-in state [:players player-id :gear] (fnil conj []) item-key)
       true "Item equipped"])))

(defn equip-secret
  "Equip a hidden item. These bypass the normal catalog — no taken-gear check.
   Returns [updated-state success? msg]."
  [state player-id item-key]
  (let [player (get-in state [:players player-id])
        item (get secret-items item-key)]
    (cond
      (nil? player)
      [state false "Player not found"]

      (nil? item)
      [state false "???"]

      (some #{item-key} (or (:gear player) []))
      [state false "You already have this"]

      :else
      (let [new-state (-> state
                          (update-in [:players player-id :gear] (fnil conj []) item-key)
                          ;; Apply effect immediately
                          (cond->
                            (= (:effect item) :phase-cloak)
                            (assoc-in [:players player-id :buffs :phase-cloak]
                                      {:expires-at (+ (:tick state) (:duration item))})

                            (= (:effect item) :teleport)
                            (as-> s
                              (let [open (filter #(cell-free? s %)
                                                 (for [x (range (get-in s [:map :width]))
                                                       y (range (get-in s [:map :height]))]
                                                   [x y]))
                                    [tx ty] (rand-nth (vec open))]
                                (-> s
                                    (assoc-in [:players player-id :x] tx)
                                    (assoc-in [:players player-id :y] ty))))

                            (= (:effect item) :emp)
                            (as-> s
                              (let [px (:x player) py (:y player)
                                    radius (if (has-buff? player :vision-2x) 10 5)]
                                (reduce (fn [st [pid p]]
                                          (if (and (not= pid player-id)
                                                   (:alive? p)
                                                   (<= (manhattan-distance [px py] [(:x p) (:y p)]) radius))
                                            (assoc-in st [:players pid :buffs] {})
                                            st))
                                        s (:players s))))

                            (= (:effect item) :shadow-clone)
                            (as-> s
                              (let [clone-id (str "enemy-clone-" (subs (str (random-uuid)) 0 6))]
                                (assoc-in s [:enemies clone-id]
                                          {:x (:x player) :y (:y player) :hp 1
                                           :type :decoy :score 0
                                           :direction (or (:last-direction player) :south)})))

                            (= (:effect item) :gravity-well)
                            (as-> s
                              (let [px (:x player) py (:y player)]
                                (reduce (fn [st [pid p]]
                                          (if (and (not= pid player-id) (:alive? p)
                                                   (<= (manhattan-distance [px py] [(:x p) (:y p)]) 5))
                                            (let [[kx ky _] (knockback-dest st (:x p) (:y p)
                                                                            ;; Invert: pull TOWARD player
                                                                            (- (* 2 (:x p)) px)
                                                                            (- (* 2 (:y p)) py)
                                                                            2 [px py])]
                                              (-> st
                                                  (assoc-in [:players pid :x] kx)
                                                  (assoc-in [:players pid :y] ky)))
                                            st))
                                        s (:players s))))))]
        [new-state true (str "SECRET UNLOCKED: " (:name item) " — " (:desc item))]))))

(defn add-player
  "Add a player to the game. Returns [updated-state {:id :token}] or [state {:error msg}] if name taken."
  [state player-name]
  (if (name-taken? state player-name)
    [state {:error (str "Name '" player-name "' is already taken. Pick a different name.")}]
    (let [id (str "player-" (subs (str (random-uuid)) 0 8))
          token (str (random-uuid))
          spawns (get-in state [:map :spawn-points] [[1 1] [18 1] [1 18] [18 18]])
          spawn (nth spawns (mod (count (:players state)) (count spawns)))]
      [(-> state
           (assoc-in [:players id]
                     {:name player-name
                      :x (first spawn)
                      :y (second spawn)
                      :hp START-HP
                      :score 0
                      :passenger nil
                      :ammo 5
                      :grenades 2
                      :alive? true
                      :points START-POINTS
                      :items []
                      :gear []
                      :buffs {}
                      :debuffs {}
                      :token token}))
       {:id id :token token}])))
