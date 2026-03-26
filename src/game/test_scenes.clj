(ns game.test-scenes
  "Visual test scenarios — pure functions that generate game states.
   Each scenario is a sequence of frames. f(scenario, frame) → game-state + events."
  (:require [game.core :as core]
            [game.maps :as maps]))

;;; ---------------------------------------------------------------------------
;;; Helpers — build small test states
;;; ---------------------------------------------------------------------------

(def test-map-5x5
  "Tiny 5x5 map for visual debugging. Open interior, walls on border."
  {:width 5 :height 5
   :walls (set (concat
                ;; Top and bottom rows
                (for [x (range 5)] [x 0])
                (for [x (range 5)] [x 4])
                ;; Left and right columns
                (for [y (range 5)] [0 y])
                (for [y (range 5)] [4 y])))
   :spawn-points [[1 1] [3 3]]
   :passenger-spawns [[2 1] [2 3]]})

(defn- make-player [name x y & {:keys [hp score passenger alive? ammo]
                                :or {hp 100 score 0 passenger nil
                                     alive? true ammo 5}}]
  {:name name :x x :y y :hp hp :score score
   :passenger passenger :alive? alive? :ammo ammo
   :grenades 2 :token "test"})

(defn- make-state
  "Build a minimal game state for test rendering."
  [& {:keys [tick players passengers map-data recent-shots
             recent-effects shrink-warning crater-fires config]
      :or {tick 0 players {} passengers [] map-data test-map-5x5
           recent-shots [] recent-effects [] shrink-warning #{}
           crater-fires {} config {}}}]
  {:tick tick
   :map map-data
   :players players
   :passengers passengers
   :recent-shots recent-shots
   :recent-effects recent-effects
   :shrink-warning shrink-warning
   :crater-fires crater-fires
   :config (merge {:tick-ms 250 :visibility-radius 5 :shoot-range 20
                   :shoot-damage 30 :max-passengers 6 :max-players 8
                   :ammo-regen-ticks 5 :max-ammo 10 :respawn-ticks 10
                   :game-duration-ticks 1000 :shrink-start 200
                   :shrink-interval 50 :shrink-warn-ticks 10
                   :crater-fire-ticks 10}
                  config)})

;;; ---------------------------------------------------------------------------
;;; Scenario 1: Sprite Cycle — shows all sprite frames, then passenger mode
;;; ---------------------------------------------------------------------------

(defn sprite-cycle-frame [frame]
  (let [has-pax (>= frame 4)]
    {:state (make-state
             :tick frame
             :players {"player-test"
                       (make-player "Rocket" 2 2
                                    :score (* frame 25)
                                    :passenger (when has-pax
                                                 {:id "pax-1"
                                                  :dest {:x 3 :y 1}}))})
     :events [{:type :player-joined :name "Rocket" :tick 0}]
     :description (if has-pax
                    (str "Passenger mode — frame " (- frame 3) "/4")
                    (str "Normal driving — frame " (inc frame) "/4"))}))

(def sprite-cycle
  {:name "Sprite Cycle"
   :total-frames 8
   :frame-fn sprite-cycle-frame})

;;; ---------------------------------------------------------------------------
;;; Scenario 2: Bullet Tracer — bot shoots east across the grid
;;; ---------------------------------------------------------------------------

(defn bullet-tracer-frame [frame]
  (let [shooting (> frame 0)
        shot-path (when shooting
                    (vec (for [d (range 1 4)] [(+ 1 d) 2])))]
    {:state (make-state
             :tick frame
             :players {"player-hunter"
                       (make-player "Hunter-1" 1 2 :ammo (if shooting 4 5))
                       "player-ghost"
                       (make-player "Ghost-2" 3 3)}
             :recent-shots (if shooting
                             [{:shooter-id "player-hunter"
                               :origin [1 2]
                               :direction :east
                               :path shot-path
                               :hit-id nil}]
                             []))
     :events (cond-> [{:type :player-joined :name "Hunter-1" :tick 0}
                      {:type :player-joined :name "Ghost-2" :tick 0}]
               shooting (conj {:type :command :tick frame
                               :player-id "player-hunter"
                               :action {:type :shoot :direction "east"}}))
     :description (if shooting
                    "Bullet tracer heading east from Hunter-1"
                    "Before the shot — both players idle")}))

(def bullet-tracer
  {:name "Bullet Tracer"
   :total-frames 4
   :frame-fn bullet-tracer-frame})

;;; ---------------------------------------------------------------------------
;;; Scenario 3: Shoot + Kill — full combat sequence
;;; ---------------------------------------------------------------------------

(defn shoot-kill-frame [frame]
  (case frame
    0 {:state (make-state
               :tick 0
               :players {"player-a" (make-player "Hunter-1" 1 2 :score 0)
                         "player-b" (make-player "Ghost-2" 3 2 :score 0)})
       :events [{:type :player-joined :name "Hunter-1" :tick 0}
                {:type :player-joined :name "Ghost-2" :tick 0}]
       :description "Two bots face off — Hunter-1 on left, Ghost-2 on right"}

    1 {:state (make-state
               :tick 1
               :players {"player-a" (make-player "Hunter-1" 1 2 :ammo 4)
                         "player-b" (make-player "Ghost-2" 3 2 :hp 70)}
               :recent-shots [{:shooter-id "player-a"
                               :origin [1 2] :direction :east
                               :path [[2 2] [3 2]] :hit-id "player-b"}])
       :events [{:type :command :tick 1 :player-id "player-a"
                 :action {:type :shoot :direction "east"}}]
       :description "Hunter-1 shoots east — tracer + hit on Ghost-2 (70hp)"}

    2 {:state (make-state
               :tick 2
               :players {"player-a" (make-player "Hunter-1" 1 2 :ammo 3)
                         "player-b" (make-player "Ghost-2" 3 2 :hp 40)}
               :recent-shots [{:shooter-id "player-a"
                               :origin [1 2] :direction :east
                               :path [[2 2] [3 2]] :hit-id "player-b"}])
       :events [{:type :command :tick 2 :player-id "player-a"
                 :action {:type :shoot :direction "east"}}]
       :description "Second shot — Ghost-2 at 40hp, sparks fly"}

    3 {:state (make-state
               :tick 3
               :players {"player-a" (make-player "Hunter-1" 1 2 :ammo 2 :score 50)
                         "player-b" (make-player "Ghost-2" 3 2 :hp 0 :alive? false)}
               :recent-shots [{:shooter-id "player-a"
                               :origin [1 2] :direction :east
                               :path [[2 2] [3 2]] :hit-id "player-b"}])
       :events [{:type :command :tick 3 :player-id "player-a"
                 :action {:type :shoot :direction "east"}}
                {:type :kill :tick 3 :victim-id "player-b"}]
       :description "KILL! Ghost-2 eliminated — nuke explosion + 50pts"}

    4 {:state (make-state
               :tick 4
               :players {"player-a" (make-player "Hunter-1" 1 2 :ammo 2 :score 50)
                         "player-b" (make-player "Ghost-2" 3 2 :hp 0 :alive? false)})
       :events [{:type :kill :tick 3 :victim-id "player-b"}]
       :description "Aftermath — smoke clears, Ghost-2 is dead"}))

(def shoot-kill
  {:name "Shoot + Kill"
   :total-frames 5
   :frame-fn shoot-kill-frame})

;;; ---------------------------------------------------------------------------
;;; Scenario 4: Lightning Strike
;;; ---------------------------------------------------------------------------

(defn lightning-frame [frame]
  (case frame
    0 {:state (make-state
               :tick 0
               :players {"player-a" (make-player "Hunter-1" 1 1)})
       :events []
       :description "Calm before the storm"}

    1 {:state (make-state
               :tick 1
               :players {"player-a" (make-player "Hunter-1" 1 1)}
               :recent-effects [{:type :lightning :x 2 :y 2 :radius 1
                                 :cells #{[1 2] [2 1] [2 2] [2 3] [3 2]}}])
       :events [{:type :lightning :tick 1 :x 2 :y 2 :radius 1
                 :cells-destroyed 5}]
       :description "LIGHTNING STRIKE at center! Flash + bolt + blast"}

    2 {:state (make-state
               :tick 2
               :players {"player-a" (make-player "Hunter-1" 1 1)}
               :map-data (update test-map-5x5 :walls into
                                 #{[1 2] [2 1] [2 2] [2 3] [3 2]})
               :crater-fires {[1 2] 12 [2 1] 12 [2 2] 12 [2 3] 12 [3 2] 12})
       :events [{:type :lightning :tick 1 :x 2 :y 2 :radius 1
                 :cells-destroyed 5}]
       :description "Crater fire burns — 10 ticks of flames"}

    3 {:state (make-state
               :tick 11
               :players {"player-a" (make-player "Hunter-1" 1 1)}
               :map-data (update test-map-5x5 :walls into
                                 #{[1 2] [2 1] [2 2] [2 3] [3 2]})
               :crater-fires {[1 2] 12 [2 1] 12 [2 2] 12 [2 3] 12 [3 2] 12})
       :events []
       :description "Crater fire still burning (tick 11 of 12)"}

    4 {:state (make-state
               :tick 13
               :players {"player-a" (make-player "Hunter-1" 1 1)}
               :map-data (update test-map-5x5 :walls into
                                 #{[1 2] [2 1] [2 2] [2 3] [3 2]}))
       :events []
       :description "Fire expired — just crater walls remain"}))

(def lightning
  {:name "Lightning"
   :total-frames 5
   :frame-fn lightning-frame})

;;; ---------------------------------------------------------------------------
;;; Scenario 5: Ring of Fire (Battle Royale Shrink Warning)
;;; ---------------------------------------------------------------------------

(def test-map-7x7
  "Slightly larger map to show shrink ring."
  {:width 7 :height 7
   :walls (set (concat
                (for [x (range 7)] [x 0])
                (for [x (range 7)] [x 6])
                (for [y (range 7)] [0 y])
                (for [y (range 7)] [6 y])))
   :spawn-points [[1 1] [5 5]]
   :passenger-spawns [[3 3]]})

(defn ring-of-fire-frame [frame]
  (let [inner-ring (set (concat
                         (for [x (range 1 6)] [x 1])
                         (for [x (range 1 6)] [x 5])
                         (for [y (range 1 6)] [1 y])
                         (for [y (range 1 6)] [5 y])))]
    (case frame
      0 {:state (make-state
                 :tick 188
                 :map-data test-map-7x7
                 :players {"player-a" (make-player "Hunter-1" 2 2 :score 100)
                           "player-b" (make-player "Ghost-2" 4 4 :score 75)})
         :events []
         :description "Tick 188 — shrink coming at 200, no warning yet"}

      1 {:state (make-state
                 :tick 190
                 :map-data test-map-7x7
                 :shrink-warning inner-ring
                 :players {"player-a" (make-player "Hunter-1" 2 2 :score 100)
                           "player-b" (make-player "Ghost-2" 4 4 :score 75)})
         :events []
         :description "Tick 190 — FIRE WARNING! Ring of fire appears (10 ticks to shrink)"}

      2 {:state (make-state
                 :tick 195
                 :map-data test-map-7x7
                 :shrink-warning inner-ring
                 :players {"player-a" (make-player "Hunter-1" 2 2 :score 100)
                           "player-b" (make-player "Ghost-2" 4 4 :score 75)})
         :events []
         :description "Tick 195 — fire still burning, 5 ticks left!"}

      3 {:state (make-state
                 :tick 200
                 :map-data (update test-map-7x7 :walls into inner-ring)
                 :players {"player-a" (make-player "Hunter-1" 2 2 :score 100)
                           "player-b" (make-player "Ghost-2" 4 4 :score 75)})
         :events []
         :description "Tick 200 — SHRINK! Ring becomes walls. Arena is now 3x3."})))

(def ring-of-fire
  {:name "Ring of Fire"
   :total-frames 4
   :frame-fn ring-of-fire-frame
   :use-7x7 true})

;;; ---------------------------------------------------------------------------
;;; Scenario Registry
;;; ---------------------------------------------------------------------------

(def scenarios
  [sprite-cycle
   bullet-tracer
   shoot-kill
   lightning
   ring-of-fire])

(defn get-scenario [idx]
  (nth scenarios (mod idx (count scenarios))))

(defn get-frame
  "Pure function: (scenario, frame-number) → {:state :events :description}"
  [scenario frame]
  (let [f (mod frame (:total-frames scenario))]
    ((:frame-fn scenario) f)))
