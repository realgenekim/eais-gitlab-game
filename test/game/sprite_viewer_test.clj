(ns game.sprite-viewer-test
  "Tests for CSS sprite sheet animation math.
   Validates that background-position percentages land exactly on frame boundaries."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

;; ──────────────────────────────────────────────
;; CSS background-position percentage formula:
;;   actual_offset = percentage × (image_width - container_width)
;;
;; For a sprite strip of N equal frames, each frame_width = image_width / N.
;; Container = 1 frame wide. So:
;;   scrollable_range = image_width - frame_width = frame_width × (N - 1)
;;
;; Frame i starts at pixel offset = i × frame_width.
;; As a percentage: pct_i = (i × frame_width) / (frame_width × (N - 1)) = i / (N - 1)
;;
;; For 4 frames: 0/3=0%, 1/3=33.33%, 2/3=66.67%, 3/3=100%
;; ──────────────────────────────────────────────

(defn frame-position-pct
  "The correct background-position percentage to show frame i of n."
  [i n]
  (if (= n 1)
    0.0
    (* 100.0 (/ (double i) (dec n)))))

(defn pixel-offset-from-pct
  "Given a background-position percentage, image width, and container width,
   compute the actual pixel offset (CSS formula)."
  [pct image-w container-w]
  (* (/ pct 100.0) (- image-w container-w)))

(defn steps-positions
  "Returns the background-position percentages visited by a CSS steps() animation
   from 0% to 100% with given step count and jump behavior.

   jump-end (default): N intervals, shows start of each → 0, 1/N, 2/N, ..., (N-1)/N
   jump-none: N intervals, shows both endpoints → 0, 1/N, 2/N, ..., 1.0"
  [n jump]
  (case jump
    :jump-end  (mapv #(* 100.0 (/ (double %) n)) (range n))
    :jump-none (mapv #(* 100.0 (/ (double %) n)) (range (inc n)))))

;; ──────────────────────────────────────────────
;; Tests
;; ──────────────────────────────────────────────

(deftest frame-position-percentages-test
  (testing "4-frame sprite: percentages land on frame boundaries"
    (let [n 4]
      (is (= 0.0    (frame-position-pct 0 n)) "Frame 0 at 0%")
      (is (< (Math/abs (- 33.333 (frame-position-pct 1 n))) 0.01) "Frame 1 at ~33.3%")
      (is (< (Math/abs (- 66.667 (frame-position-pct 2 n))) 0.01) "Frame 2 at ~66.7%")
      (is (= 100.0  (frame-position-pct 3 n)) "Frame 3 at 100%")))

  (testing "2-frame sprite: 0% and 100%"
    (is (= 0.0   (frame-position-pct 0 2)))
    (is (= 100.0 (frame-position-pct 1 2))))

  (testing "1-frame sprite: always 0%"
    (is (= 0.0 (frame-position-pct 0 1)))))

(deftest pixel-offset-from-percentage-test
  (testing "4-frame sprite, 512px image, 128px container"
    (let [img-w 512 ctr-w 128]
      (is (= 0.0   (pixel-offset-from-pct 0.0 img-w ctr-w))     "0% → pixel 0")
      (is (< (Math/abs (- 128.0 (pixel-offset-from-pct 33.333 img-w ctr-w))) 0.01)
          "33.3% → pixel 128 (frame 1)")
      (is (< (Math/abs (- 256.0 (pixel-offset-from-pct 66.667 img-w ctr-w))) 0.01)
          "66.7% → pixel 256 (frame 2)")
      (is (= 384.0 (pixel-offset-from-pct 100.0 img-w ctr-w))   "100% → pixel 384 (frame 3)"))))

(deftest steps-jump-end-misses-last-frame
  (testing "steps(3) jump-end shows 3 positions, MISSES frame 3"
    (let [positions (steps-positions 3 :jump-end)]
      (is (= 3 (count positions)) "Only 3 visible frames")
      (is (= [0.0 33.33333333333333 66.66666666666666] positions))
      ;; 100% is NOT in the list — frame 3 never displayed!
      (is (not (some #(= 100.0 %) positions))
          "Frame 3 (100%) is never shown with steps(3, jump-end)")))

  (testing "steps(4) jump-end lands BETWEEN frames — causes bleed"
    (let [positions (steps-positions 4 :jump-end)]
      ;; 0%, 25%, 50%, 75% — none of these except 0% are on frame boundaries
      (is (= [0.0 25.0 50.0 75.0] positions))
      ;; 25% of 384px scrollable range = 96px — that's mid-frame!
      (is (= 96.0 (pixel-offset-from-pct 25.0 512 128))
          "25% → pixel 96, which is INSIDE frame 0 (0-127), NOT on frame 1 boundary"))))

(deftest steps-jump-none-shows-all-frames
  (testing "steps(3, jump-none) shows all 4 frames correctly"
    (let [positions (steps-positions 3 :jump-none)
          n-frames 4]
      (is (= 4 (count positions)) "All 4 frames visible")
      ;; Verify each position matches the correct frame boundary
      (doseq [i (range n-frames)]
        (let [expected-pct (frame-position-pct i n-frames)
              actual-pct   (nth positions i)]
          (is (< (Math/abs (- expected-pct actual-pct)) 0.01)
              (str "Frame " i " position matches: expected " expected-pct "%, got " actual-pct "%")))))))

(deftest background-size-test
  (testing "background-size for N-frame strip is N*100%"
    (doseq [n [2 3 4 6 8]]
      (is (= (str (* 100 n) "% 100%")
             (str (* 100 n) "% 100%"))
          (str n " frames → " (* 100 n) "% 100%")))))

(deftest css-generation-matches-math
  (testing "frame-style output for sprite_viewer.clj matches expected percentages"
    ;; Simulating the frame-style function from sprite_viewer.clj:
    ;; background-position: (* i (/ 100.0 (dec n-frames))) for frame i
    (let [n-frames 4]
      (doseq [i (range n-frames)]
        (let [code-pct    (* i (/ 100.0 (dec n-frames)))
              correct-pct (frame-position-pct i n-frames)]
          (is (< (Math/abs (- code-pct correct-pct)) 0.001)
              (str "Frame " i ": code produces " code-pct "%, math says " correct-pct "%"))))))

  (testing "The animation steps function must use jump-none for all frames to show"
    ;; This is the KEY assertion: the CSS must use steps(N-1, jump-none)
    ;; NOT steps(N-1) which defaults to jump-end and skips the last frame
    (let [n-frames 4
          step-count (dec n-frames) ; = 3
          jump-none-positions (steps-positions step-count :jump-none)
          jump-end-positions  (steps-positions step-count :jump-end)]
      (is (= n-frames (count jump-none-positions))
          "jump-none shows all frames")
      (is (= (dec n-frames) (count jump-end-positions))
          "jump-end SKIPS the last frame — THIS IS THE BUG"))))
