(ns game.bots
  "Built-in bot strategies for testing and demos."
  (:require [game.engine :as engine]
            [game.core :as core]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(def directions [:north :south :east :west])
(def dir-deltas {:north [0 -1] :south [0 1] :east [1 0] :west [-1 0]})

(defn distance [[x1 y1] [x2 y2]]
  (+ (abs (- x1 x2)) (abs (- y1 y2))))

(defn toward
  "Pick direction that moves [x1 y1] closer to [x2 y2], avoiding walls.
   Falls back to perpendicular or random if direct path is blocked."
  [state [x1 y1] [x2 y2]]
  (let [dx (- x2 x1) dy (- y2 y1)
        preferred (cond
                    (and (zero? dx) (zero? dy)) (rand-nth directions)
                    (> (abs dx) (abs dy)) (if (pos? dx) :east :west)
                    :else (if (pos? dy) :south :north))
        try-dir (fn [dir]
                  (let [[ddx ddy] (dir-deltas dir)
                        nx (+ x1 ddx) ny (+ y1 ddy)]
                    (when (core/walkable? state [nx ny]) dir)))
        ;; Try preferred, then other axis, then perpendiculars, then random
        alt (if (> (abs dx) (abs dy))
              (if (pos? dy) :south :north)
              (if (pos? dx) :east :west))]
    (or (try-dir preferred)
        (try-dir alt)
        (try-dir (rand-nth directions))
        (try-dir (rand-nth directions))
        preferred)))

(defn away-from
  "Pick direction that moves away from [x2 y2]."
  [[x1 y1] [x2 y2]]
  (let [dx (- x1 x2) dy (- y1 y2)]
    (cond
      (and (zero? dx) (zero? dy)) (rand-nth directions)
      (> (abs dx) (abs dy)) (if (pos? dx) :east :west)
      :else (if (pos? dy) :south :north))))

(defn line-of-sight-dir
  "If target is on same row/col, return the shooting direction. Else nil."
  [[x1 y1] [x2 y2]]
  (cond
    (and (= y1 y2) (< x2 x1)) :west
    (and (= y1 y2) (> x2 x1)) :east
    (and (= x1 x2) (< y2 y1)) :north
    (and (= x1 x2) (> y2 y1)) :south
    :else nil))

(defn in-danger?
  "Is pos [x y] in the path of any visible shot?"
  [shots x y]
  (some (fn [shot]
          (some (fn [[sx sy]] (and (= sx x) (= sy y)))
                (:path shot)))
        shots))

(defn dodge-dir
  "Pick a direction perpendicular to incoming fire."
  [shot-direction]
  (case shot-direction
    (:north :south) (rand-nth [:east :west])
    (:east :west) (rand-nth [:north :south])
    (rand-nth directions)))

;;; ---------------------------------------------------------------------------
;;; Bot: Hunter — aggressive, seeks enemies, dodges incoming fire
;;; ---------------------------------------------------------------------------

(defn hunter-think
  "Hunter strategy: dodge bullets > shoot aligned enemies > chase nearest enemy > pickup passengers."
  [state me-id]
  (let [me (get-in state [:players me-id])
        my-pos [(:x me) (:y me)]
        shots (:recent-shots state)
        enemies (->> (:players state)
                     (remove (fn [[id _]] (= id me-id)))
                     (filter (fn [[_ p]] (:alive? p)))
                     (map (fn [[id p]] {:id id :pos [(:x p) (:y p)]})))
        passengers (->> (:passengers state)
                        (filter #(nil? (:picked-up-by %)))
                        (map (fn [p] {:pos [(:x p) (:y p)] :dest (:dest p)})))]
    (cond
      ;; 1. DODGE — if we're in a bullet path, move perpendicular
      (and (seq shots) (in-danger? shots (:x me) (:y me)))
      (let [threat (first (filter #(some (fn [[sx sy]] (and (= sx (:x me)) (= sy (:y me)))) (:path %)) shots))]
        [{:type :move :direction (dodge-dir (:direction threat))}])

      ;; 2. SHOOT — if enemy is aligned and we have ammo
      (pos? (:ammo me))
      (if-let [shot-dir (some #(line-of-sight-dir my-pos (:pos %)) enemies)]
        [{:type :shoot :direction shot-dir}]
        ;; 3. CHASE — move toward nearest enemy
        (if (seq enemies)
          (let [nearest (apply min-key #(distance my-pos (:pos %)) enemies)]
            [{:type :move :direction (toward state my-pos (:pos nearest))}
             {:type :pickup}])
          ;; 4. DELIVER — go for passengers
          (if (:passenger me)
            [{:type :move :direction (toward state my-pos [(get-in me [:passenger :dest :x])
                                                           (get-in me [:passenger :dest :y])])}
             {:type :dropoff}]
            (if (seq passengers)
              (let [nearest-pax (apply min-key #(distance my-pos (:pos %)) passengers)]
                [{:type :move :direction (toward state my-pos (:pos nearest-pax))}
                 {:type :pickup}])
              [{:type :move :direction (rand-nth directions)}]))))

      ;; Out of ammo — deliver passengers or roam
      :else
      (if (:passenger me)
        [{:type :move :direction (toward state my-pos [(get-in me [:passenger :dest :x])
                                                       (get-in me [:passenger :dest :y])])}
         {:type :dropoff}]
        (if (seq passengers)
          (let [nearest-pax (apply min-key #(distance my-pos (:pos %)) passengers)]
            [{:type :move :direction (toward state my-pos (:pos nearest-pax))}
             {:type :pickup}])
          [{:type :move :direction (rand-nth directions)}])))))

;;; ---------------------------------------------------------------------------
;;; Bot: Courier — prioritizes deliveries, fights only in self-defense
;;; ---------------------------------------------------------------------------

(defn courier-think
  "Courier strategy: dodge > deliver passenger > pickup nearest > shoot only if attacked."
  [state me-id]
  (let [me (get-in state [:players me-id])
        my-pos [(:x me) (:y me)]
        shots (:recent-shots state)
        enemies (->> (:players state)
                     (remove (fn [[id _]] (= id me-id)))
                     (filter (fn [[_ p]] (:alive? p)))
                     (map (fn [[id p]] {:id id :pos [(:x p) (:y p)]})))
        passengers (->> (:passengers state)
                        (filter #(nil? (:picked-up-by %)))
                        (map (fn [p] {:pos [(:x p) (:y p)] :dest (:dest p)})))]
    (cond
      ;; 1. DODGE
      (and (seq shots) (in-danger? shots (:x me) (:y me)))
      (let [threat (first (filter #(some (fn [[sx sy]] (and (= sx (:x me)) (= sy (:y me)))) (:path %)) shots))]
        [{:type :move :direction (dodge-dir (:direction threat))}])

      ;; 2. DELIVER — if carrying, go to destination
      (:passenger me)
      [{:type :move :direction (toward state my-pos [(get-in me [:passenger :dest :x])
                                                     (get-in me [:passenger :dest :y])])}
       {:type :dropoff}]

      ;; 3. PICKUP — go to nearest passenger
      (seq passengers)
      (let [nearest-pax (apply min-key #(distance my-pos (:pos %)) passengers)]
        [{:type :move :direction (toward state my-pos (:pos nearest-pax))}
         {:type :pickup}])

      ;; 4. SELF-DEFENSE — shoot if enemy aligned
      (and (pos? (:ammo me))
           (some #(line-of-sight-dir my-pos (:pos %)) enemies))
      [{:type :shoot :direction (some #(line-of-sight-dir my-pos (:pos %)) enemies)}]

      ;; 5. ROAM toward center (more passengers spawn there)
      :else
      [{:type :move :direction (toward state my-pos [10 9])}])))

;;; ---------------------------------------------------------------------------
;;; Bot: Random Walker (baseline)
;;; ---------------------------------------------------------------------------

(defn random-think
  "Random walk — baseline. Moves randomly, sometimes picks up/shoots."
  [_state _me-id]
  (let [actions [{:type :move :direction (rand-nth directions)}]]
    (cond-> actions
      (< (rand) 0.3) (conj {:type :pickup})
      (< (rand) 0.3) (conj {:type :dropoff})
      (< (rand) 0.15) (conj {:type :shoot :direction (rand-nth directions)}))))

;;; ---------------------------------------------------------------------------
;;; Bot Runner
;;; ---------------------------------------------------------------------------

(defn start-bot!
  "Start a bot with the given think function. Returns the future.
   Survives pause/resume — sleeps when paused instead of exiting."
  [bot-creds think-fn]
  (future
    (let [sys @engine/system
          id (:id bot-creds)
          tick-ms (get-in @(:game-state sys) [:config :tick-ms] 250)]
      (loop []
        (when @(:game-state sys) ;; only exit if system is gone
          (if (some? @(:game-timer sys))
            (let [state @(:game-state sys)
                  me (get-in state [:players id])]
              (when (:alive? me)
                (doseq [action (think-fn state id)]
                  (engine/enqueue-command! sys id action))))
            nil) ;; paused — just sleep
          (Thread/sleep tick-ms)
          (recur))))))

(defn start-team!
  "Start a team of bots. Returns map of name → {:creds ... :future ...}."
  [bot-specs]
  (into {}
        (map (fn [{:keys [name think-fn]}]
               (let [creds (engine/add-player! @engine/system name)]
                 [name {:creds creds
                        :future (start-bot! creds think-fn)}]))
             bot-specs)))
