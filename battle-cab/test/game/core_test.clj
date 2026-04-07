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
      (is (= 500 (:hp player))))))

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
