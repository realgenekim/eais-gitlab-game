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

;;; ---------------------------------------------------------------------------
;;; Gear Catalog & Loadout System
;;; ---------------------------------------------------------------------------

(def LOADOUT-BUDGET 100)

(def gear-catalog
  "All equippable gear items. Each has a slot, cost, and stat effects."
  {;; Weapons (slot: :weapon, pick one)
   :standard-blaster {:slot :weapon :cost 0
                      :effects {:shoot-damage 30 :shoot-range 5}}
   :shotgun          {:slot :weapon :cost 15
                      :effects {:shoot-damage 50 :shoot-range 3}}
   :sniper-rifle     {:slot :weapon :cost 20
                      :effects {:shoot-damage 40 :shoot-range 12}}
   :plasma-cannon    {:slot :weapon :cost 25
                      :effects {:shoot-damage 60 :shoot-range 5 :shoot-cooldown 2}}

   ;; Armor (slot: :armor, pick one)
   :light-vest       {:slot :armor :cost 10
                      :effects {:max-hp 600}}
   :heavy-armor      {:slot :armor :cost 25
                      :effects {:max-hp 750 :speed 0}} ;; speed 0 = cannot use speed boost
   :energy-shield    {:slot :armor :cost 20
                      :effects {:shield-hp 50}} ;; absorbs first 50 dmg per life

   ;; Movement (slot: :movement, pick one)
   :speed-boost      {:slot :movement :cost 15
                      :effects {:speed 2}} ;; move 2 tiles per tick
   :teleporter       {:slot :movement :cost 30
                      :effects {:can-teleport true :teleport-cooldown 20}}

   ;; Utility (slot: :utility, pick multiple)
   :radar            {:slot :utility :cost 15
                      :effects {:visibility-radius 8}}
   :extra-ammo       {:slot :utility :cost 10
                      :effects {:start-ammo 10 :max-ammo 15}}
   :grenades-plus    {:slot :utility :cost 10
                      :effects {:start-grenades 5}}
   :trap-mine        {:slot :utility :cost 15
                      :effects {:can-trap true :max-traps 3}}
   :decoy            {:slot :utility :cost 20
                      :effects {:can-decoy true :decoy-cooldown 15}}})

(defn validate-loadout
  "Validate a loadout map. Returns {:valid? bool :errors [...] :cost int :effects {...}}.
   Loadout format: {:weapon :sniper-rifle :armor :light-vest :utility [:radar :extra-ammo]}"
  [loadout]
  (if (nil? loadout)
    {:valid? true :errors [] :cost 0 :effects {} :items []}
    (let [weapon (or (:weapon loadout) :standard-blaster)
          armor (:armor loadout)
          movement (:movement loadout)
          utilities (vec (or (:utility loadout) []))
          all-items (cond-> [weapon]
                      armor (conj armor)
                      movement (conj movement)
                      true (into utilities))
          ;; Check all items exist
          unknown (remove #(contains? gear-catalog %) all-items)
          ;; Check slot constraints (only 1 weapon, 1 armor, 1 movement)
          weapon-items (filter #(= :weapon (:slot (gear-catalog %))) all-items)
          armor-items (filter #(= :armor (:slot (gear-catalog %))) all-items)
          movement-items (filter #(= :movement (:slot (gear-catalog %))) all-items)
          errors (cond-> []
                   (seq unknown)
                   (conj (str "Unknown gear: " (pr-str (vec unknown))))
                   (> (count weapon-items) 1)
                   (conj "Can only equip one weapon")
                   (> (count armor-items) 1)
                   (conj "Can only equip one armor")
                   (> (count movement-items) 1)
                   (conj "Can only equip one movement item"))
          total-cost (reduce + 0 (map #(:cost (gear-catalog %) 0) all-items))
          errors (cond-> errors
                   (> total-cost LOADOUT-BUDGET)
                   (conj (str "Over budget: " total-cost "/" LOADOUT-BUDGET)))
          ;; Merge all effects (later items override earlier for same key)
          effects (reduce (fn [acc item]
                            (merge acc (:effects (gear-catalog item) {})))
                          {} all-items)
          ;; Heavy armor blocks speed boost
          effects (if (and (= 0 (:speed effects 1)) (= :heavy-armor armor))
                    (assoc effects :speed 1) ;; heavy armor = normal speed, no boost allowed
                    effects)]
      {:valid? (empty? errors)
       :errors errors
       :cost total-cost
       :effects effects
       :items all-items})))

(defn loadout->player-stats
  "Convert a validated loadout's effects into player stat overrides."
  [effects]
  {:max-hp           (get effects :max-hp START-HP)
   :shoot-damage     (get effects :shoot-damage 30)
   :shoot-range      (get effects :shoot-range 5)
   :shoot-cooldown   (get effects :shoot-cooldown 0)
   :speed            (get effects :speed 1)
   :visibility-radius (get effects :visibility-radius 5)
   :start-ammo       (get effects :start-ammo 5)
   :max-ammo         (get effects :max-ammo 10)
   :start-grenades   (get effects :start-grenades 2)
   :shield-hp        (get effects :shield-hp 0)
   :can-teleport     (get effects :can-teleport false)
   :teleport-cooldown (get effects :teleport-cooldown 0)
   :can-trap         (get effects :can-trap false)
   :max-traps        (get effects :max-traps 0)
   :can-decoy        (get effects :can-decoy false)
   :decoy-cooldown   (get effects :decoy-cooldown 0)})


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
  "Returns set of positions visible to a player (manhattan radius).
   Uses per-player visibility-radius from loadout if present, else config default."
  [game-state player-id]
  (let [player (get-in game-state [:players player-id])
        radius (or (get-in player [:stats :visibility-radius])
                   (get-in game-state [:config :visibility-radius] 5))]
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
                                             (visible [(:x p) (:y p)]))))
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
                           :path (vec (filter visible (:path shot)))})))]
    {:tick (:tick game-state)
     :you (cond-> (-> me
                      (select-keys [:x :y :hp :score :passenger :ammo :grenades :alive? :shield-hp])
                      (assoc :id player-id))
             (:loadout me) (assoc :loadout (:loadout me))
             (:stats me) (assoc :stats (select-keys (:stats me)
                                                     [:max-hp :shoot-damage :shoot-range :speed
                                                      :visibility-radius :can-teleport :can-trap :can-decoy])))
     :visible {:players (vec others)
               :enemies (vec enemies)
               :passengers (vec passengers)
               :shots (vec shots)}
     ;; Show player's own traps
     :traps (vec (filter #(= (:owner-id %) player-id) (or (:traps game-state) [])))
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
        speed (get-in player [:stats :speed] 1)]
    (if (not (:alive? player))
      state
      ;; Move up to `speed` tiles in the given direction, stopping at walls
      (loop [s state steps 0]
        (if (>= steps speed)
          s
          (let [p (get-in s [:players player-id])
                new-pos [(+ (:x p) dx) (+ (:y p) dy)]]
            (if (walkable? s new-pos)
              (recur (-> s
                         (assoc-in [:players player-id :x] (first new-pos))
                         (assoc-in [:players player-id :y] (second new-pos)))
                     (inc steps))
              s)))))))

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
        ;; Per-player stats from loadout, falling back to config defaults
        range- (or (get-in player [:stats :shoot-range])
                   (get-in state [:config :shoot-range] 5))
        damage (or (get-in player [:stats :shoot-damage])
                   (get-in state [:config :shoot-damage] 30))
        kill-bonus (get-in state [:config :kill-bonus] 150)
        ;; Shoot cooldown — plasma cannon has 2-tick cooldown
        cooldown (get-in player [:stats :shoot-cooldown] 0)
        last-shot-tick (get player :last-shot-tick -999)
        enemies (or (:enemies state) {})]
    (if (or (not (:alive? player))
            (< (:ammo player) 1)
            (= [dx dy] [0 0])
            ;; Enforce shoot cooldown (e.g. plasma cannon)
            (and (pos? cooldown)
                 (< (- (:tick state) last-shot-tick) cooldown)))
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
                      (assoc-in [:players player-id :last-shot-tick] (:tick state))
                      (update :recent-shots conj shot))]
        (cond
          ;; Hit a player
          hit-id
          (let [;; Shield absorbs damage first
                shield (get-in state [:players hit-id :shield-hp] 0)
                shield-absorb (min shield damage)
                actual-damage (- damage shield-absorb)
                state (if (pos? shield-absorb)
                        (update-in state [:players hit-id :shield-hp] - shield-absorb)
                        state)
                new-hp (- (get-in state [:players hit-id :hp]) actual-damage)]
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
              (assoc-in state [:players hit-id :hp] new-hp)))

          ;; Hit an enemy
          hit-enemy-id
          (let [enemy (get-in state [:enemies hit-enemy-id])
                new-hp (- (:hp enemy) damage)]
            (if (<= new-hp 0)
              ;; Kill enemy — remove it, award score
              (-> state
                  (update :enemies dissoc hit-enemy-id)
                  (update-in [:players player-id :score] + (:score enemy 10)))
              ;; Damage enemy
              (assoc-in state [:enemies hit-enemy-id :hp] new-hp)))

          :else state)))))

(defmethod apply-action :teleport
  [state player-id _action]
  (let [player (get-in state [:players player-id])
        can-tp (get-in player [:stats :can-teleport] false)
        cooldown (get-in player [:stats :teleport-cooldown] 20)
        last-tp (get player :last-teleport-tick -999)]
    (if (or (not (:alive? player))
            (not can-tp)
            (< (- (:tick state) last-tp) cooldown))
      state
      ;; Teleport to a random open cell
      (let [{:keys [width height walls]} (:map state)
            open-cells (for [x (range width) y (range height)
                             :when (not (contains? walls [x y]))]
                         [x y])
            open-vec (vec open-cells)
            [nx ny] (nth open-vec (rand-int (count open-vec)))]
        (-> state
            (assoc-in [:players player-id :x] nx)
            (assoc-in [:players player-id :y] ny)
            (assoc-in [:players player-id :last-teleport-tick] (:tick state)))))))

(defmethod apply-action :grenade
  [state player-id {:keys [direction]}]
  (let [player (get-in state [:players player-id])
        [dx dy] (get directions (keyword direction) [0 0])
        grenade-range 4
        grenade-radius 2
        grenade-damage 40]
    (if (or (not (:alive? player))
            (< (:grenades player 0) 1)
            (= [dx dy] [0 0]))
      state
      ;; Grenade lands `grenade-range` tiles in the given direction (stops at walls)
      (let [;; Trace to find landing spot
            [lx ly] (loop [x (:x player) y (:y player) dist 0]
                      (let [nx (+ x dx) ny (+ y dy)]
                        (if (or (>= dist grenade-range)
                                (not (in-bounds? (:map state) [nx ny]))
                                (wall? state [nx ny]))
                          [x y]
                          (recur nx ny (inc dist)))))
            ;; All cells in blast radius
            blast-cells (set (positions-in-radius [lx ly] grenade-radius (:map state)))
            enemies (or (:enemies state) {})
            ;; Damage/kill players in blast
            state (reduce-kv
                   (fn [s id p]
                     (if (and (:alive? p)
                              (not= id player-id)
                              (contains? blast-cells [(:x p) (:y p)]))
                       (let [new-hp (- (:hp p) grenade-damage)]
                         (if (<= new-hp 0)
                           (-> s
                               (assoc-in [:players id :hp] 0)
                               (assoc-in [:players id :alive?] false)
                               (assoc-in [:players id :respawn-at]
                                         (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))
                               (assoc-in [:players id :passenger] nil)
                               (update-in [:players player-id :score] + (get-in state [:config :kill-bonus] 150)))
                           (assoc-in s [:players id :hp] new-hp)))
                       s))
                   state (:players state))
            ;; Damage/kill enemies in blast
            state (reduce-kv
                   (fn [s eid e]
                     (if (contains? blast-cells [(:x e) (:y e)])
                       (let [new-hp (- (:hp e) grenade-damage)]
                         (if (<= new-hp 0)
                           (-> s
                               (update :enemies dissoc eid)
                               (update-in [:players player-id :score] + (:score e 10)))
                           (assoc-in s [:enemies eid :hp] new-hp)))
                       s))
                   state enemies)
            ;; Record effect for spectator rendering
            effect {:type :grenade
                    :x lx :y ly
                    :radius grenade-radius
                    :cells blast-cells
                    :shooter-id player-id}]
        (-> state
            (update-in [:players player-id :grenades] dec)
            (update :recent-effects (fnil conj []) effect))))))

(defmethod apply-action :trap
  [state player-id _action]
  (let [player (get-in state [:players player-id])
        can-trap (get-in player [:stats :can-trap] false)
        max-traps (get-in player [:stats :max-traps] 3)
        current-traps (count (filter #(= (:owner-id %) player-id)
                                     (or (:traps state) [])))]
    (if (or (not (:alive? player))
            (not can-trap)
            (>= current-traps max-traps))
      state
      ;; Place invisible trap at player's current position
      (update state :traps (fnil conj [])
              {:id (str "trap-" (:tick state) "-" player-id)
               :owner-id player-id
               :x (:x player)
               :y (:y player)
               :damage 60
               :placed-tick (:tick state)}))))

(defmethod apply-action :decoy
  [state player-id _action]
  (let [player (get-in state [:players player-id])
        can-decoy (get-in player [:stats :can-decoy] false)
        cooldown (get-in player [:stats :decoy-cooldown] 15)
        last-decoy (get player :last-decoy-tick -999)]
    (if (or (not (:alive? player))
            (not can-decoy)
            (< (- (:tick state) last-decoy) cooldown))
      state
      ;; Place a decoy that looks like a player to enemies for 10 ticks
      (-> state
          (update :decoys (fnil conj [])
                  {:id (str "decoy-" (:tick state) "-" player-id)
                   :owner-id player-id
                   :x (:x player) :y (:y player)
                   :expires-tick (+ (:tick state) 10)
                   :name (get-in state [:players player-id :name])})
          (assoc-in [:players player-id :last-decoy-tick] (:tick state))))))

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
           (let [max-hp (get-in player [:stats :max-hp] START-HP)
                 start-ammo (get-in player [:stats :start-ammo] 5)
                 shield (get-in player [:stats :shield-hp] 0)]
             (-> s
                 (assoc-in [:players id :alive?] true)
                 (assoc-in [:players id :hp] max-hp)
                 (assoc-in [:players id :x] sx)
                 (assoc-in [:players id :y] sy)
                 (assoc-in [:players id :ammo] start-ammo)
                 (assoc-in [:players id :respawn-at] nil)
                 ;; Restore shield on respawn
                 (cond-> (pos? shield)
                   (assoc-in [:players id :shield-hp] shield)))))
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
        default-max (get-in state [:config :max-ammo] 10)]
    (if (zero? (mod (:tick state) regen-every))
      (reduce-kv
       (fn [s id player]
         (let [max-ammo (get-in player [:stats :max-ammo] default-max)]
           (if (and (:alive? player) (< (:ammo player) max-ammo))
             (update-in s [:players id :ammo] inc)
             s)))
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
  "Enemy type definitions: {type {:hp :speed :damage :score}}"
  {:floopy {:hp 20 :speed 1 :damage 10 :score 10}
   :squanchy {:hp 50 :speed 1 :damage 20 :score 25}
   :scary {:hp 80 :speed 2 :damage 30 :score 50}})

(defn spawn-enemies
  "Spawn a wave of enemies at random edge cells."
  [state enemy-type count-to-spawn]
  (let [{:keys [width height walls]} (:map state)
        edge-cells (concat
                    (for [x (range 1 (dec width))] [x 1])
                    (for [x (range 1 (dec width))] [x (- height 2)])
                    (for [y (range 1 (dec height))] [1 y])
                    (for [y (range 1 (dec height))] [(- width 2) y]))
        open-edges (vec (remove #(contains? walls %) edge-cells))
        type-info (get enemy-types enemy-type {:hp 20 :speed 1 :damage 10 :score 10})]
    (if (empty? open-edges)
      state
      (reduce (fn [s i]
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
                             :type enemy-type})))
              state (range count-to-spawn)))))

(defn move-enemies
  "Move each enemy one cell toward the nearest alive player (or decoy)."
  [state]
  (let [alive-players (->> (:players state)
                           (filter (fn [[_ p]] (:alive? p)))
                           (map (fn [[_ p]] [(:x p) (:y p)])))
        ;; Decoys look like players to enemies
        decoy-positions (map (fn [d] [(:x d) (:y d)]) (or (:decoys state) []))
        alive-players (concat alive-players decoy-positions)
        walls (get-in state [:map :walls])]
    (if (empty? alive-players)
      state
      (reduce-kv
       (fn [s id enemy]
         (let [ex (:x enemy) ey (:y enemy)
               ;; Find nearest player
               nearest (apply min-key
                              (fn [[px py]] (manhattan-distance [ex ey] [px py]))
                              alive-players)
               [px py] nearest
               dx (compare px ex) ;; -1, 0, or 1
               dy (compare py ey)
               ;; Try to move toward player (prefer axis with larger distance)
               mx (Math/abs (- px ex))
               my (Math/abs (- py ey))
               [nx ny] (if (>= mx my)
                         [(+ ex dx) ey]
                         [ex (+ ey dy)])
               ;; Fall back if blocked by wall
               [nx ny] (if (contains? walls [nx ny])
                         (if (>= mx my)
                           [ex (+ ey dy)]
                           [(+ ex dx) ey])
                         [nx ny])
               ;; Final check — don't walk into walls
               [nx ny] (if (contains? walls [nx ny])
                         [ex ey]
                         [nx ny])]
           (-> s
               (assoc-in [:enemies id :x] nx)
               (assoc-in [:enemies id :y] ny))))
       state (or (:enemies state) {})))))

(defn enemy-player-collisions
  "Enemies on the same cell as a player deal damage."
  [state]
  (let [player-positions (into {}
                               (for [[id p] (:players state)
                                     :when (:alive? p)]
                                 [[(:x p) (:y p)] id]))]
    (reduce-kv
     (fn [s eid enemy]
       (if-let [pid (get player-positions [(:x enemy) (:y enemy)])]
         (let [damage (:damage enemy 10)
               new-hp (max 0 (- (get-in s [:players pid :hp]) damage))]
           (-> s
               (assoc-in [:players pid :hp] new-hp)
               (cond-> (zero? new-hp)
                 (-> (assoc-in [:players pid :alive?] false)
                     (assoc-in [:players pid :respawn-at]
                               (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))))))
         s))
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
      ;; Determine wave composition based on tick
      (let [wave-num (quot tick wave-interval)
            floopy-count (min 8 (+ 3 wave-num))
            squanchy-count (if (>= wave-num 3) (min 4 (- wave-num 1)) 0)
            scary-count (if (>= wave-num 6) (min 3 (- wave-num 4)) 0)]
        (-> state
            (spawn-enemies :floopy floopy-count)
            (cond-> (pos? squanchy-count) (spawn-enemies :squanchy squanchy-count))
            (cond-> (pos? scary-count) (spawn-enemies :scary scary-count)))))))

(defn check-traps
  "Check if any player or enemy stepped on a trap. Traps deal damage and are consumed."
  [state]
  (let [traps (or (:traps state) [])]
    (if (empty? traps)
      state
      (reduce
       (fn [s trap]
         (let [tx (:x trap) ty (:y trap)
               ;; Check players (not the owner)
               hit-player (first (keep (fn [[id p]]
                                         (when (and (:alive? p)
                                                    (not= id (:owner-id trap))
                                                    (= (:x p) tx) (= (:y p) ty))
                                           id))
                                       (:players s)))
               ;; Check enemies
               hit-enemy (first (keep (fn [[eid e]]
                                        (when (and (= (:x e) tx) (= (:y e) ty))
                                          eid))
                                      (or (:enemies s) {})))]
           (cond
             hit-player
             (let [damage (:damage trap 60)
                   new-hp (- (get-in s [:players hit-player :hp]) damage)
                   s (update s :traps (fn [ts] (vec (remove #(= (:id %) (:id trap)) ts))))
                   s (update s :recent-effects (fnil conj [])
                             {:type :trap-triggered :x tx :y ty :hit-id hit-player})]
               (if (<= new-hp 0)
                 (-> s
                     (assoc-in [:players hit-player :hp] 0)
                     (assoc-in [:players hit-player :alive?] false)
                     (assoc-in [:players hit-player :respawn-at]
                               (+ (:tick state) (get-in state [:config :respawn-ticks] 10)))
                     (update-in [:players (:owner-id trap) :score] + (get-in state [:config :kill-bonus] 150)))
                 (assoc-in s [:players hit-player :hp] new-hp)))

             hit-enemy
             (let [damage (:damage trap 60)
                   enemy (get-in s [:enemies hit-enemy])
                   new-hp (- (:hp enemy) damage)
                   s (update s :traps (fn [ts] (vec (remove #(= (:id %) (:id trap)) ts))))]
               (if (<= new-hp 0)
                 (-> s
                     (update :enemies dissoc hit-enemy)
                     (update-in [:players (:owner-id trap) :score] + (:score enemy 10)))
                 (assoc-in s [:enemies hit-enemy :hp] new-hp)))

             :else s)))
       state traps))))

(defn expire-decoys
  "Remove decoys that have expired."
  [state]
  (let [tick (:tick state)]
    (update state :decoys
            (fn [decoys]
              (vec (remove #(>= tick (:expires-tick %)) (or decoys [])))))))

(defn advance-tick
  "Pure function: old state + commands → new state."
  [state commands]
  (-> state
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
      ;; Gear mechanics
      (check-traps)
      (expire-decoys)
      ;; Environment
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
   :enemies {}
   :passengers []
   :recent-shots []
   :recent-effects []
   :crater-fires {}
   :traps []
   :decoys []
   :config (config/load-config)})

(defn add-player
  "Add a player to the game. Accepts optional loadout map.
   Returns [updated-state {:id token}] or [state {:error msg}] if invalid."
  ([state player-name] (add-player state player-name nil))
  ([state player-name loadout]
   (let [{:keys [valid? errors effects items cost]} (validate-loadout loadout)
         _ (when-not valid?
             (throw (ex-info "Invalid loadout" {:errors errors})))
         stats (loadout->player-stats effects)
         id (str "player-" (subs (str (random-uuid)) 0 8))
         token (str (random-uuid))
         spawns (get-in state [:map :spawn-points] [[1 1] [18 1] [1 18] [18 18]])
         spawn (nth spawns (mod (count (:players state)) (count spawns)))]
     [(-> state
          (assoc-in [:players id]
                    (cond->
                      {:name player-name
                       :x (first spawn)
                       :y (second spawn)
                       :hp (:max-hp stats)
                       :score 0
                       :passenger nil
                       :ammo (:start-ammo stats)
                       :grenades (:start-grenades stats)
                       :alive? true
                       :token token
                       ;; Per-player gear stats
                       :loadout {:items (vec (or items []))
                                 :cost (or cost 0)}
                       :stats stats}
                      ;; Set shield-hp if energy shield equipped
                      (pos? (:shield-hp stats))
                      (assoc :shield-hp (:shield-hp stats)))))
      {:id id :token token}])))
