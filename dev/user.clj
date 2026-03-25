(ns user
  (:require [game.core :as core]
            [game.maps :as maps]
            [game.replay :as replay]))

(comment
  ;; === REPL playground ===

  ;; Create a fresh game state
  (def state (core/make-initial-state maps/arena-map))

  ;; Add some players
  (let [[s1 creds1] (core/add-player state "Alice")
        [s2 creds2] (core/add-player s1 "Bob")]
    (def state s2)
    (def alice creds1)
    (def bob creds2))

  ;; View the map
  (println (maps/render-state-ascii state))

  ;; Advance a tick with commands
  (def state
    (core/advance-tick state
                       [{:player-id (:id alice) :action {:type :move :direction :east}}
                        {:player-id (:id bob)   :action {:type :move :direction :north}}]))

  (println (maps/render-state-ascii state))

  ;; Check player view (fog of war)
  (core/player-view state (:id alice))

  ;; Test replay
  (def rec (replay/make-recorder (core/make-initial-state maps/arena-map))))
