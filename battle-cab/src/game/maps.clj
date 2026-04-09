(ns game.maps
  "Game maps defined as ASCII art. Easy to read, easy to create new ones."
  (:require [clojure.string :as str]))

(defn parse-ascii-map
  "Parse an ASCII art map into game map data.
   Legend: # = wall, . = open, S = spawn point, P = passenger spawn
   Returns {:width W :height H :walls #{[x y]...} :spawn-points [[x y]...]}"
  [ascii-str]
  (let [lines (str/split-lines (str/trim ascii-str))
        height (count lines)
        width (apply max (map count lines))]
    (reduce
     (fn [m [y line]]
       (reduce
        (fn [m [x ch]]
          (case ch
            \# (update m :walls conj [x y])
            \S (update m :spawn-points conj [x y])
            \P (update m :passenger-spawns conj [x y])
            m))
        m (map-indexed vector line)))
     {:width width
      :height height
      :walls #{}
      :spawn-points []
      :passenger-spawns []}
     (map-indexed vector lines))))

;; ============================================================================
;; THE ARENA — a 20x20 maze with 4 spawn points and open combat zones
;; ============================================================================
;;
;;  Legend:  # wall   . open   S spawn   P passenger-spawn zone
;;
;;  The map has:
;;  - 4 spawn corners (S)
;;  - Central crossroads for combat
;;  - Corridors connecting areas
;;  - Alcoves for ambushes
;;  - Passenger pickup zones (P) scattered around

(def arena-ascii "
####################
#S.................S#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#..................#
#S.................S#
####################
")

;; A smaller 12x12 map for quick testing / short rounds

(def arena-small-ascii "
############
#S........S#
#.###..###.#
#.#..P...#.#
#.#.####.#.#
#....P.....#
#.#.####.#.#
#.#..P...#.#
#.###..###.#
#S........S#
############
")

;; ============================================================================
;; "The Diamond" — open center, diagonal walls, sniper duels
;; ============================================================================

(def diamond-ascii "
####################
#S.......P........S#
#..##..........##..#
#....##......##....#
#......##..##......#
#..P.....##....P...#
#........##........#
#..................#
#.......P..P.......#
#.....##....##.....#
#.....##....##.....#
#.......P..P.......#
#..................#
#........##........#
#...P....##..P.....#
#......##..##......#
#....##......##....#
#..##..........##..#
#S........P.......S#
####################
")

;; ============================================================================
;; "Four Rooms" — walled quadrants, narrow doorways, choke points
;; ============================================================================

(def four-rooms-ascii "
####################
#S...P...#..P.....S#
#........#.........#
#........#.........#
#...####.#.####....#
#........#.........#
#..P.....#.....P...#
#........#.........#
#........#.........#
######.######.######
#........#.........#
#........#.........#
#..P.....#.....P...#
#........#.........#
#...####.#.####....#
#........#.........#
#........#.........#
#........#.........#
#S...P...#..P.....S#
####################
")

;; ============================================================================
;; "The Gauntlet" — central corridor with flanking forts
;; ============================================================================

(def gauntlet-ascii "
####################
#S..#.....P.....#.S#
#...#...........#..#
#...#..########.#..#
#...#..#......#.#..#
#.P....#..PP..#....#
#...#..#......#.#..#
#...#..########.#..#
#...#...........#..#
#...............P..#
#..P...............#
#..#...........#...#
#..#.########..#...#
#..#.#......#..#...#
#....#..PP..#....P.#
#..#.#......#..#...#
#..#.########..#...#
#..#...........#...#
#S..#.....P.....#.S#
####################
")

;; ============================================================================
;; Map Registry — all available maps with display names
;; ============================================================================

(def arena-map (parse-ascii-map arena-ascii))
(def arena-small-map (parse-ascii-map arena-small-ascii))
(def diamond-map (parse-ascii-map diamond-ascii))
(def four-rooms-map (parse-ascii-map four-rooms-ascii))
(def gauntlet-map (parse-ascii-map gauntlet-ascii))

(def map-registry
  "Ordered list of available maps: [{:id :name :map} ...]"
  [{:id "arena"      :name "The Arena"      :map arena-map}
   {:id "diamond"    :name "The Diamond"    :map diamond-map}
   {:id "four-rooms" :name "Four Rooms"     :map four-rooms-map}
   {:id "gauntlet"   :name "The Gauntlet"   :map gauntlet-map}
   {:id "small"      :name "Small Arena"    :map arena-small-map}])

(defn get-map-by-id
  "Look up a map from the registry by string id. Falls back to arena."
  [id]
  (or (:map (first (filter #(= (:id %) id) map-registry)))
      arena-map))

(defn render-state-ascii
  "Render game state as ASCII art for terminal display / debugging."
  [game-state]
  (let [{:keys [width height walls]} (:map game-state)
        players (:players game-state)
        passengers (filter #(nil? (:picked-up-by %)) (:passengers game-state))
        ;; Build lookup maps
        player-pos (into {} (map (fn [[id p]] [[(:x p) (:y p)] (subs id 7 8)])
                                 (filter (fn [[_ p]] (:alive? p)) players)))
        pax-pos (set (map (fn [p] [(:x p) (:y p)]) passengers))]
    (str/join
     \newline
     (for [y (range height)]
       (apply str
              (for [x (range width)]
                (cond
                  (contains? walls [x y]) \#
                  (player-pos [x y]) (player-pos [x y])
                  (pax-pos [x y]) \$
                  :else \.)))))))
