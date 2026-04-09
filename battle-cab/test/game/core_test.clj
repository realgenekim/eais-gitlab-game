(ns game.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
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

;;; ---------------------------------------------------------------------------
;;; Loadout / Gear System Tests
;;; ---------------------------------------------------------------------------

(deftest loadout-validation-test
  (testing "nil loadout is valid (defaults)"
    (let [result (core/validate-loadout nil)]
      (is (:valid? result))
      (is (= 0 (:cost result)))))

  (testing "valid loadout with weapon + armor + utility"
    (let [result (core/validate-loadout {:weapon :sniper-rifle
                                         :armor :light-vest
                                         :utility [:radar]})]
      (is (:valid? result))
      (is (= 45 (:cost result))) ;; 20 + 10 + 15
      (is (= 40 (get-in result [:effects :shoot-damage])))
      (is (= 12 (get-in result [:effects :shoot-range])))
      (is (= 600 (get-in result [:effects :max-hp])))
      (is (= 8 (get-in result [:effects :visibility-radius])))))

  (testing "over budget is rejected"
    (let [result (core/validate-loadout {:weapon :plasma-cannon     ;; 25
                                         :armor :heavy-armor        ;; 25
                                         :movement :teleporter      ;; 30
                                         :utility [:radar :decoy]})] ;; 15+20 = 35
      (is (not (:valid? result)))
      (is (some #(clojure.string/includes? % "Over budget") (:errors result)))))

  (testing "unknown gear is rejected"
    (let [result (core/validate-loadout {:weapon :laser-sword})]
      (is (not (:valid? result)))
      (is (some #(clojure.string/includes? % "Unknown") (:errors result))))))

(deftest add-player-with-loadout-test
  (testing "player with sniper gets custom damage/range"
    (let [[state creds] (core/add-player (fresh-state) "Sniper"
                                          {:weapon :sniper-rifle})
          player (get-in state [:players (:id creds)])]
      (is (= 40 (get-in player [:stats :shoot-damage])))
      (is (= 12 (get-in player [:stats :shoot-range])))
      (is (= core/START-HP (:hp player))) ;; no armor = default HP
      (is (= [:sniper-rifle] (get-in player [:loadout :items])))))

  (testing "player with heavy armor gets extra HP"
    (let [[state creds] (core/add-player (fresh-state) "Tank"
                                          {:armor :heavy-armor})
          player (get-in state [:players (:id creds)])]
      (is (= 750 (:hp player)))
      (is (= 750 (get-in player [:stats :max-hp])))))

  (testing "player with energy shield gets shield-hp"
    (let [[state creds] (core/add-player (fresh-state) "Shielded"
                                          {:armor :energy-shield})
          player (get-in state [:players (:id creds)])]
      (is (= 50 (:shield-hp player)))))

  (testing "player with extra-ammo starts with more"
    (let [[state creds] (core/add-player (fresh-state) "Ammo"
                                          {:utility [:extra-ammo]})
          player (get-in state [:players (:id creds)])]
      (is (= 10 (:ammo player)))
      (is (= 15 (get-in player [:stats :max-ammo])))))

  (testing "invalid loadout throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/add-player (fresh-state) "Cheater"
                                   {:weapon :laser-sword})))))

(deftest per-player-shoot-stats-test
  (testing "sniper does 40 damage with range 12"
    (let [[state c1] (core/add-player (fresh-state) "Sniper"
                                       {:weapon :sniper-rifle})
          [state c2] (core/add-player state "Target")
          id1 (:id c1) id2 (:id c2)
          ;; Place them on same row, within sniper range
          state (-> state
                    (assoc-in [:players id1 :x] 1)
                    (assoc-in [:players id1 :y] 3)
                    (assoc-in [:players id2 :x] 3)
                    (assoc-in [:players id2 :y] 3))
          state (core/apply-action state id1 {:type :shoot :direction :east})
          target-hp (get-in state [:players id2 :hp])]
      ;; Target should take 40 damage (sniper), not 30 (default)
      (is (= (- core/START-HP 40) target-hp))))

  (testing "shotgun does 50 damage"
    (let [[state c1] (core/add-player (fresh-state) "Shotgunner"
                                       {:weapon :shotgun})
          [state c2] (core/add-player state "Target")
          id1 (:id c1) id2 (:id c2)
          state (-> state
                    (assoc-in [:players id1 :x] 1)
                    (assoc-in [:players id1 :y] 3)
                    (assoc-in [:players id2 :x] 2)
                    (assoc-in [:players id2 :y] 3))
          state (core/apply-action state id1 {:type :shoot :direction :east})
          target-hp (get-in state [:players id2 :hp])]
      (is (= (- core/START-HP 50) target-hp)))))

(deftest shield-absorbs-damage-test
  (testing "energy shield absorbs first 50 damage"
    (let [[state c1] (core/add-player (fresh-state) "Attacker")
          [state c2] (core/add-player state "Shielded"
                                       {:armor :energy-shield})
          id1 (:id c1) id2 (:id c2)
          state (-> state
                    (assoc-in [:players id1 :x] 1)
                    (assoc-in [:players id1 :y] 3)
                    (assoc-in [:players id2 :x] 2)
                    (assoc-in [:players id2 :y] 3))
          ;; First shot: 30 damage, shield absorbs all
          state (core/apply-action state id1 {:type :shoot :direction :east})]
      (is (= core/START-HP (get-in state [:players id2 :hp])))
      (is (= 20 (get-in state [:players id2 :shield-hp]))))))

(deftest speed-boost-movement-test
  (testing "speed boost moves 2 tiles per move action"
    (let [[state creds] (core/add-player (fresh-state) "Speedy"
                                          {:movement :speed-boost})
          id (:id creds)
          state (-> state
                    (assoc-in [:players id :x] 1)
                    (assoc-in [:players id :y] 3))
          state (core/apply-action state id {:type :move :direction :east})
          new-x (get-in state [:players id :x])]
      (is (= 3 new-x))))) ;; moved 2 tiles east: 1 → 3

(deftest grenade-action-test
  (testing "grenade deals area damage and consumes a grenade"
    (let [[state c1] (core/add-player (fresh-state) "Bomber")
          [state c2] (core/add-player state "Victim")
          id1 (:id c1) id2 (:id c2)
          ;; Place bomber at [1,3], victim at [3,3] (within grenade range+radius)
          state (-> state
                    (assoc-in [:players id1 :x] 1)
                    (assoc-in [:players id1 :y] 3)
                    (assoc-in [:players id2 :x] 3)
                    (assoc-in [:players id2 :y] 3))
          grenades-before (get-in state [:players id1 :grenades])
          state (core/apply-action state id1 {:type :grenade :direction :east})
          grenades-after (get-in state [:players id1 :grenades])]
      (is (= (dec grenades-before) grenades-after))
      ;; Victim should have taken 40 grenade damage
      (is (= (- core/START-HP 40) (get-in state [:players id2 :hp]))))))

(deftest trap-action-test
  (testing "trap requires can-trap gear"
    (let [[state creds] (core/add-player (fresh-state) "NoTrap")
          id (:id creds)
          state-after (core/apply-action state id {:type :trap})]
      ;; No trap placed (player doesn't have trap gear)
      (is (empty? (:traps state-after)))))

  (testing "trap is placed and triggers on enemy step"
    (let [[state creds] (core/add-player (fresh-state) "Trapper"
                                          {:utility [:trap-mine]})
          id (:id creds)
          ;; Place trap at player's position
          state (core/apply-action state id {:type :trap})
          trap-pos [(:x (get-in state [:players id]))
                    (:y (get-in state [:players id]))]
          _ (is (= 1 (count (:traps state))))
          ;; Add an enemy at the trap position
          state (assoc-in state [:enemies "e1"]
                          {:x (first trap-pos) :y (second trap-pos)
                           :hp 100 :max-hp 100 :damage 10 :score 25 :type :floopy})
          ;; Check traps should trigger
          state (core/check-traps state)]
      ;; Trap consumed
      (is (empty? (:traps state)))
      ;; Enemy took 60 damage
      (is (= 40 (get-in state [:enemies "e1" :hp]))))))

(deftest teleport-action-test
  (testing "teleport requires teleporter gear"
    (let [[state creds] (core/add-player (fresh-state) "NoTP")
          id (:id creds)
          old-x (get-in state [:players id :x])
          old-y (get-in state [:players id :y])
          state (core/apply-action state id {:type :teleport})]
      ;; Position unchanged (no teleporter)
      (is (= old-x (get-in state [:players id :x])))
      (is (= old-y (get-in state [:players id :y])))))

  (testing "teleport works with teleporter gear"
    (let [[state creds] (core/add-player (fresh-state) "TPer"
                                          {:movement :teleporter})
          id (:id creds)
          state (assoc state :tick 25) ;; past cooldown
          state (core/apply-action state id {:type :teleport})]
      ;; Should have teleported (position changed or tracked)
      (is (some? (get-in state [:players id :last-teleport-tick]))))))

(deftest respawn-with-loadout-test
  (testing "respawned player gets loadout HP and ammo back"
    (let [[state creds] (core/add-player (fresh-state) "Tank"
                                          {:armor :heavy-armor
                                           :utility [:extra-ammo]})
          id (:id creds)
          state (-> state
                    (assoc-in [:players id :hp] 0)
                    (assoc-in [:players id :alive?] false)
                    (assoc-in [:players id :respawn-at] 5)
                    (assoc :tick 5))
          state (core/respawn-dead-players state)
          player (get-in state [:players id])]
      (is (:alive? player))
      (is (= 750 (:hp player)))     ;; heavy armor HP
      (is (= 10 (:ammo player)))))) ;; extra ammo start

(deftest spawn-enemies-empty-edges-test
  (testing "spawn-enemies handles fully-walled edges without crashing"
    (let [tiny-map {:width 4 :height 4
                    :walls (set (for [x (range 4) y (range 4)] [x y]))
                    :spawn-points [[1 1]]
                    :passenger-spawns []}
          state (assoc (core/make-initial-state tiny-map) :tick 100)]
      (is (= state (core/spawn-enemies state :floopy 5))))))
