(ns game.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [game.core :as core]
            [game.maps :as maps]))

(def test-map
  (maps/parse-ascii-map
   "######\n#S..S#\n#.##.#\n#....#\n#S..S#\n######"))

(defn fresh-state []
  (core/make-initial-state test-map))

(deftest map-parsing-test
  (testing "walls are parsed correctly"
    (is (contains? (:walls test-map) [0 0]))
    (is (contains? (:walls test-map) [2 2]))
    (is (not (contains? (:walls test-map) [1 1]))))

  (testing "spawn points found"
    (is (= 4 (count (:spawn-points test-map))))))

(deftest player-join-test
  (testing "adding a player"
    (let [state (fresh-state)
          [new-state creds] (core/add-player state "Alice")]
      (is (contains? (:players new-state) (:id creds)))
      (is (string? (:token creds)))
      (is (= "Alice" (get-in new-state [:players (:id creds) :name])))))

  (testing "player starts alive with HP"
    (let [[state creds] (core/add-player (fresh-state) "Bob")
          player (get-in state [:players (:id creds)])]
      (is (:alive? player))
      (is (= core/START-HP (:hp player))))))

(deftest respawn-test
  (testing "dead player respawns with full HP after timer"
    (let [[state creds] (core/add-player (fresh-state) "Bob")
          id (:id creds)
          ;; Kill the player
          state (-> state
                    (assoc-in [:players id :hp] 0)
                    (assoc-in [:players id :alive?] false)
                    (assoc-in [:players id :respawn-at] 5))
          ;; Advance to tick 5 (respawn tick)
          state (assoc state :tick 5)
          state (core/respawn-dead-players state)
          player (get-in state [:players id])]
      (is (:alive? player))
      (is (= core/START-HP (:hp player)))))

  (testing "dead player stays dead before respawn timer"
    (let [[state creds] (core/add-player (fresh-state) "Bob")
          id (:id creds)
          state (-> state
                    (assoc-in [:players id :hp] 0)
                    (assoc-in [:players id :alive?] false)
                    (assoc-in [:players id :respawn-at] 10))
          state (assoc state :tick 5)
          state (core/respawn-dead-players state)
          player (get-in state [:players id])]
      (is (not (:alive? player))))))

(deftest movement-test
  (testing "valid move updates position"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          old-x (get-in state [:players id :x])
          new-state (core/apply-action state id {:type :move :direction :east})
          new-x (get-in new-state [:players id :x])]
      (is (= (inc old-x) new-x))))

  (testing "move into wall is rejected"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Move to wall boundary then try to move into wall
          state (-> state
                    (assoc-in [:players id :x] 1)
                    (assoc-in [:players id :y] 1))
          ;; Try moving north into wall at [1,0]
          new-state (core/apply-action state id {:type :move :direction :north})]
      (is (= 1 (get-in new-state [:players id :y]))))))

(deftest pickup-dropoff-test
  (testing "pickup and delivery cycle"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Place a passenger at player's location
          px (get-in state [:players id :x])
          py (get-in state [:players id :y])
          state (assoc state :passengers
                       [{:id "pax-1" :x px :y py
                         :dest {:x 3 :y 3} :picked-up-by nil}])
          ;; Pickup
          state (core/apply-action state id {:type :pickup})
          _ (is (some? (get-in state [:players id :passenger])))
          ;; Move to destination
          state (-> state
                    (assoc-in [:players id :x] 3)
                    (assoc-in [:players id :y] 3))
          ;; Dropoff
          state (core/apply-action state id {:type :dropoff})]
      (is (nil? (get-in state [:players id :passenger])))
      (is (= 100 (get-in state [:players id :score]))))))

(deftest tick-advance-test
  (testing "tick increments"
    (let [state (fresh-state)
          new-state (core/advance-tick state [])]
      (is (= 1 (:tick new-state)))))

  (testing "commands are applied during tick"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          old-x (get-in state [:players id :x])
          new-state (core/advance-tick state
                                       [{:player-id id :action {:type :move :direction :east}}])]
      (is (= (inc old-x) (get-in new-state [:players id :x]))))))

(deftest fog-of-war-test
  (testing "player view only shows nearby entities"
    (let [[state c1] (core/add-player (fresh-state) "Alice")
          [state c2] (core/add-player state "Bob")
          view (core/player-view state (:id c1))]
      (is (= (:id c1) (get-in view [:you :id])))
      (is (map? (:visible view)))
      (is (vector? (get-in view [:visible :players]))))))

(deftest spawn-enemies-empty-edges-test
  (testing "spawn-enemies handles fully-walled edges without crashing"
    (let [tiny-map {:width 4 :height 4
                    :walls (set (for [x (range 4) y (range 4)] [x y]))
                    :spawn-points [[1 1]]
                    :passenger-spawns []}
          state (assoc (core/make-initial-state tiny-map) :tick 100)]
      (is (= state (core/spawn-enemies state :floopy 5))))))

;;; ---------------------------------------------------------------------------
;;; No-cell-sharing invariant tests
;;; ---------------------------------------------------------------------------

(defn all-entity-positions
  "Return list of all [x y] positions of alive players and enemies."
  [state]
  (concat
   (for [[_ p] (:players state) :when (:alive? p)] [(:x p) (:y p)])
   (for [[_ e] (or (:enemies state) {})] [(:x e) (:y e)])))

(defn no-cell-sharing?
  "Assert that no two entities share the same cell."
  [state]
  (let [positions (all-entity-positions state)]
    (= (count positions) (count (set positions)))))

(deftest player-cannot-move-onto-enemy-test
  (testing "player move blocked by enemy on target cell"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Place player at [1,1], enemy at [2,1]
          state (-> state
                    (assoc-in [:players id :x] 1)
                    (assoc-in [:players id :y] 1)
                    (assoc-in [:enemies "e1"] {:x 2 :y 1 :hp 20 :damage 10 :score 10 :type :floopy}))
          new-state (core/apply-action state id {:type :move :direction :east})]
      ;; Player should NOT have moved onto the enemy's cell
      (is (= 1 (get-in new-state [:players id :x])))
      (is (no-cell-sharing? new-state)))))

(deftest player-cannot-move-onto-other-player-test
  (testing "player move blocked by another player on target cell"
    (let [[state c1] (core/add-player (fresh-state) "Alice")
          [state c2] (core/add-player state "Bob")
          ;; Place them adjacent
          state (-> state
                    (assoc-in [:players (:id c1) :x] 1)
                    (assoc-in [:players (:id c1) :y] 1)
                    (assoc-in [:players (:id c2) :x] 2)
                    (assoc-in [:players (:id c2) :y] 1))
          new-state (core/apply-action state (:id c1) {:type :move :direction :east})]
      (is (= 1 (get-in new-state [:players (:id c1) :x])))
      (is (no-cell-sharing? new-state)))))

(deftest enemy-cannot-share-cell-with-player-test
  (testing "enemy stops adjacent to player, not on top"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Player at [3,3], enemy at [3,1] — enemy wants to move south toward player
          state (-> state
                    (assoc-in [:players id :x] 3)
                    (assoc-in [:players id :y] 3)
                    (assoc-in [:enemies "e1"] {:x 3 :y 2 :hp 20 :damage 10 :score 10 :type :floopy}))
          new-state (core/move-enemies state)]
      ;; Enemy should NOT be on [3,3] (player's cell)
      (is (not= [3 3] [(get-in new-state [:enemies "e1" :x])
                        (get-in new-state [:enemies "e1" :y])]))
      (is (no-cell-sharing? new-state)))))

(deftest enemies-cannot-stack-test
  (testing "two enemies cannot occupy the same cell"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Player at [3,3], two enemies approaching from same direction
          state (-> state
                    (assoc-in [:players id :x] 3)
                    (assoc-in [:players id :y] 3)
                    (assoc-in [:enemies "e1"] {:x 3 :y 1 :hp 20 :damage 10 :score 10 :type :floopy})
                    (assoc-in [:enemies "e2"] {:x 3 :y 1 :hp 20 :damage 10 :score 10 :type :floopy}))
          ;; This starts with two enemies on same cell — after move they should separate
          new-state (core/move-enemies state)]
      ;; After movement, no two entities should share a cell
      (is (no-cell-sharing? new-state)))))

(deftest adjacent-enemy-deals-damage-test
  (testing "enemy adjacent to player deals damage"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Player at [3,3], enemy adjacent at [3,2]
          state (-> state
                    (assoc-in [:players id :x] 3)
                    (assoc-in [:players id :y] 3)
                    (assoc-in [:enemies "e1"] {:x 3 :y 2 :hp 20 :damage 10 :score 10 :type :floopy}))
          new-state (core/enemy-player-collisions state)]
      ;; Player should take 10 damage
      (is (= (- core/START-HP 10) (get-in new-state [:players id :hp]))))))

(deftest non-adjacent-enemy-no-damage-test
  (testing "enemy 2+ cells away deals no damage"
    (let [[state creds] (core/add-player (fresh-state) "Alice")
          id (:id creds)
          ;; Player at [3,3], enemy at [3,1] — distance 2
          state (-> state
                    (assoc-in [:players id :x] 3)
                    (assoc-in [:players id :y] 3)
                    (assoc-in [:enemies "e1"] {:x 3 :y 1 :hp 20 :damage 10 :score 10 :type :floopy}))
          new-state (core/enemy-player-collisions state)]
      ;; No damage — too far
      (is (= core/START-HP (get-in new-state [:players id :hp]))))))

(deftest full-tick-no-sharing-test
  (testing "no cell sharing after a full tick with players and enemies"
    (let [[state c1] (core/add-player (fresh-state) "Alice")
          [state c2] (core/add-player state "Bob")
          ;; Add some enemies
          state (-> state
                    (assoc-in [:enemies "e1"] {:x 1 :y 3 :hp 20 :max-hp 20 :damage 10 :score 10 :speed 1 :type :floopy})
                    (assoc-in [:enemies "e2"] {:x 4 :y 3 :hp 20 :max-hp 20 :damage 10 :score 10 :speed 1 :type :floopy})
                    (assoc-in [:enemies "e3"] {:x 2 :y 1 :hp 50 :max-hp 50 :damage 20 :score 25 :speed 1 :type :squanchy}))
          ;; Run several ticks
          state (reduce (fn [s _] (core/advance-tick s [])) state (range 20))]
      (is (no-cell-sharing? state)))))

(deftest no-stuck-bots-test
  (testing "players sending move commands should actually move over 20 ticks"
    (let [[state c1] (core/add-player (fresh-state) "Alice")
          id (:id c1)
          start-x (get-in state [:players id :x])
          start-y (get-in state [:players id :y])
          ;; Send a variety of move commands over 20 ticks
          dirs (cycle [:east :east :south :south :east :south :west :north])
          state (reduce
                 (fn [s dir]
                   (core/advance-tick s [{:player-id id
                                          :action {:type :move :direction dir}}]))
                 state
                 (take 20 dirs))
          end-x (get-in state [:players id :x])
          end-y (get-in state [:players id :y])]
      ;; Player should have moved from starting position
      (is (or (not= start-x end-x) (not= start-y end-y))
          "Player should not be stuck at starting position after 20 move commands")))

  (testing "oscillation prevention allows single reversal but blocks A-B-A-B"
    (let [[state c1] (core/add-player (fresh-state) "Alice")
          id (:id c1)
          ;; Move east, then west (single reversal — should be allowed)
          state (core/apply-action state id {:type :move :direction :east})
          x-after-east (get-in state [:players id :x])
          state (core/apply-action state id {:type :move :direction :west})
          x-after-west (get-in state [:players id :x])]
      ;; Single reversal should work
      (is (= (dec x-after-east) x-after-west)
          "Single reversal (east then west) should be allowed")))

  (testing "A-B-A oscillation pattern is blocked"
    (let [[state c1] (core/add-player (fresh-state) "Alice")
          id (:id c1)
          ;; East → West → East (A-B-A pattern — third move should be blocked)
          state (core/apply-action state id {:type :move :direction :east})
          state (core/apply-action state id {:type :move :direction :west})
          x-before-third (get-in state [:players id :x])
          state (core/apply-action state id {:type :move :direction :east})
          x-after-third (get-in state [:players id :x])]
      ;; The A-B-A should be blocked
      (is (= x-before-third x-after-third)
          "A-B-A oscillation pattern should be blocked"))))
