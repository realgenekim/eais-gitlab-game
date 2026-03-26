(ns game.config
  "Game configuration — loads from config.edn.
   Checks filesystem first (next to jar for production tuning),
   then classpath (baked into uberjar)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

(def defaults
  {:tick-ms              250
   :game-duration-ticks  1000

   :max-players          8
   :visibility-radius    5
   :respawn-ticks        10

   :shoot-range          20
   :shoot-damage         30
   :max-ammo             10
   :ammo-regen-ticks     5

   :max-passengers       6

   :shrink-start         200
   :shrink-interval      50
   :shrink-warn-ticks    10

   :lightning-chance      0.008
   :lightning-min-radius  2
   :lightning-max-radius  4
   :lightning-grace-ticks 20
   :crater-fire-ticks    10})

(defn load-config
  "Load config.edn merged over defaults.
   Filesystem ./config.edn wins over classpath resource."
  []
  (let [fs-file (io/file "config.edn")
        source  (cond
                  (.exists fs-file) {:src "filesystem" :data (edn/read-string (slurp fs-file))}
                  (io/resource "config.edn") {:src "classpath" :data (edn/read-string (slurp (io/resource "config.edn")))}
                  :else {:src "defaults" :data {}})
        cfg (merge defaults (:data source))]
    (log/info :config-loaded :source (:src source)
              :overrides (count (:data source)))
    cfg))
