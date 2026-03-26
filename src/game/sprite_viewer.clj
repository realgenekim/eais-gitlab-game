(ns game.sprite-viewer
  "Sprite sheet viewer — shows all frames side by side and animated.
   /sprite-viewer endpoint."
  (:require [hiccup2.core :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- list-sprites
  "List game-ready PNG files in the sprites directory (skip -hires and -annotated)."
  []
  (let [dir (io/file "resources/public/sprites")]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".png"))
           (remove #(str/includes? (.getName %) "-hires"))
           (remove #(str/includes? (.getName %) "-annotated"))
           (map #(.getName %))
           sort
           vec))))

(def ^:private css-text
  ":root { --bg: #0a0a0f; --bg-panel: #12121a; --border: #2a2a3a;
        --text: #e0e0e0; --text-dim: #888; --neon-cyan: #4ecdc4; --neon-yellow: #ffe66d; }
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: var(--bg); color: var(--text); font-family: 'Courier New', monospace; padding: 2rem; }
h1 { color: var(--neon-yellow); letter-spacing: 0.2em; margin-bottom: 1.5rem; }
h2 { color: var(--neon-cyan); font-size: 1rem; letter-spacing: 0.15em; margin-bottom: 0.5rem; }
.sprite-sheet { background: var(--bg-panel); border: 1px solid var(--border);
  border-radius: 6px; padding: 1.5rem; margin-bottom: 1.5rem; }
.sprite-name { color: var(--neon-yellow); font-size: 1.2rem; margin-bottom: 0.3rem; }
.sprite-meta { color: var(--text-dim); font-size: 0.8rem; margin-bottom: 1rem; }
.frames-row { display: flex; gap: 1rem; align-items: flex-end; flex-wrap: wrap; }
.frame-box { text-align: center; }
.frame-label { color: var(--text-dim); font-size: 0.7rem; margin-bottom: 0.3rem; }
.frame-img { image-rendering: pixelated; border: 1px solid var(--border); background: var(--bg); }
.anim-row { display: flex; gap: 2rem; align-items: center; margin-top: 1rem;
  padding-top: 1rem; border-top: 1px solid var(--border); }
.anim-box { text-align: center; }
.anim-label { color: var(--text-dim); font-size: 0.7rem; margin-bottom: 0.3rem; }
.anim-sprite { image-rendering: pixelated; background-repeat: no-repeat;
  background-position: 0% 0%; display: inline-block; }
.anim-sprite.a4 { animation: sheet4 1s steps(4) infinite; }
@keyframes sheet4 { from { background-position: 0% 0%; } to { background-position: 100% 0%; } }
.full-sheet { margin-top: 1rem; padding-top: 1rem; border-top: 1px solid var(--border); }
.full-sheet img { image-rendering: pixelated; border: 1px solid var(--border);
  background: var(--bg); max-width: 100%; }
.checkerboard { background-image: repeating-conic-gradient(#1a1a2a 0% 25%, #0f0f1a 0% 50%);
  background-size: 16px 16px; }

/* Stacked overlay view — frames on top of each other to detect misalignment */
.stacked-section { margin-top: 1rem; padding-top: 1rem; border-top: 1px solid var(--border); }
.stacked-row { display: flex; gap: 2rem; align-items: flex-start; }
.stacked-box { text-align: center; }
.stacked-container { position: relative; border: 1px solid var(--border); }
.stacked-frame { position: absolute; top: 0; left: 0; image-rendering: pixelated;
  background-repeat: no-repeat; }
.stacked-frame.f0 { opacity: 1; }
.stacked-frame.f1 { opacity: 0.5; }
.stacked-frame.f2 { opacity: 0.35; }
.stacked-frame.f3 { opacity: 0.25; }

/* Color-tinted overlays for distinguishing frames */
.stacked-tinted .f0 { filter: hue-rotate(0deg); }
.stacked-tinted .f1 { filter: hue-rotate(90deg); }
.stacked-tinted .f2 { filter: hue-rotate(180deg); }
.stacked-tinted .f3 { filter: hue-rotate(270deg); }
")

(defn- frame-style [url frame-count i size]
  (str "width:" size "px;height:" size "px;overflow:hidden;"
       "background-image:url(" url ");"
       "background-size:" (* 100 frame-count) "% 100%;"
       "background-position:" (* i (/ 100.0 (dec frame-count))) "% 0%;"
       "image-rendering:pixelated;"))

(defn- anim-style [url frame-count size]
  (str "width:" size "px;height:" size "px;"
       "background-image:url(" url ");"
       "background-size:" (* 100 frame-count) "% 100%;"))

(defn- sprite-card [sprite-name]
  (let [url (str "/sprites/" sprite-name)
        base-name (str/replace sprite-name #"(-hires|-annotated)?\.png$" "")
        annotated-url (str "/sprites/" base-name "-annotated.png")
        frame-count 4
        ;; Check if annotated version exists
        annotated? (.exists (io/file (str "resources/public/sprites/" base-name "-annotated.png")))]
    [:div.sprite-sheet
     [:div.sprite-name sprite-name]
     [:div.sprite-meta (str url " \u2022 " frame-count " frames")]

     ;; Annotated source image — cut lines + bounding boxes
     (when annotated?
       [:div {:style "margin-bottom:1rem;"}
        [:h2 "SOURCE WITH CUT LINES"]
        [:img.checkerboard {:src annotated-url
                            :style "max-width:100%;height:auto;image-rendering:pixelated;border:1px solid var(--border);"}]])

     ;; STACKED VIEW — frames overlaid to detect misalignment
     [:div.stacked-section
      [:h2 "STACKED OVERLAY (detect bad cuts)"]
      [:div.stacked-row
       [:div.stacked-box
        [:div.anim-label "128px opacity"]
        [:div.stacked-container.checkerboard
         {:style (str "width:128px;height:128px;")}
         (for [i (range frame-count)]
           [:div.stacked-frame
            {:class (str "f" i)
             :style (str "width:128px;height:128px;"
                         "background-image:url(" url ");"
                         "background-size:" (* 100 frame-count) "% 100%;"
                         "background-position:" (* i (/ 100.0 (dec frame-count))) "% 0%;")}])]]
       [:div.stacked-box
        [:div.anim-label "256px opacity"]
        [:div.stacked-container.checkerboard
         {:style (str "width:256px;height:256px;")}
         (for [i (range frame-count)]
           [:div.stacked-frame
            {:class (str "f" i)
             :style (str "width:256px;height:256px;"
                         "background-image:url(" url ");"
                         "background-size:" (* 100 frame-count) "% 100%;"
                         "background-position:" (* i (/ 100.0 (dec frame-count))) "% 0%;")}])]]
       [:div.stacked-box
        [:div.anim-label "256px color-tinted"]
        [:div.stacked-container.stacked-tinted.checkerboard
         {:style (str "width:256px;height:256px;")}
         (for [i (range frame-count)]
           [:div.stacked-frame
            {:class (str "f" i)
             :style (str "width:256px;height:256px;opacity:0.6;"
                         "background-image:url(" url ");"
                         "background-size:" (* 100 frame-count) "% 100%;"
                         "background-position:" (* i (/ 100.0 (dec frame-count))) "% 0%;")}])]]]]

     ;; Individual frames side by side at 128px
     [:h2 {:style "margin-top:1rem;"} "INDIVIDUAL FRAMES (128px)"]
     [:div.frames-row
      (for [i (range frame-count)]
        [:div.frame-box
         [:div.frame-label (str "Frame " (inc i))]
         [:div.frame-img.checkerboard {:style (frame-style url frame-count i 128)}]])]

     ;; Animated at various sizes
     [:div.anim-row
      (for [size [24 32 48 64 96 128]]
        [:div.anim-box
         [:div.anim-label (str size "px")]
         [:div.anim-sprite.a4.checkerboard {:style (anim-style url frame-count size)}]])]

     ;; Full sheet
     [:div.full-sheet
      [:h2 "FULL SHEET"]
      [:img.checkerboard {:src url :style "height:96px;"}]]]))

(defn sprite-viewer-page []
  (let [sprites (list-sprites)]
    (str
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:title "Sprite Viewer"]
        [:style (h/raw css-text)]]
       [:body
        [:h1 "SPRITE VIEWER"]
        [:p {:style "color:var(--text-dim);margin-bottom:1.5rem;"}
         (str (count sprites) " sprite sheet(s) in /sprites/ \u2022 "
              "Add PNGs to resources/public/sprites/ and refresh")]
        (if (empty? sprites)
          [:div {:style "color:var(--text-dim);font-style:italic;padding:2rem;text-align:center;"}
           "No sprite sheets found. Drop PNGs in resources/public/sprites/"]
          (for [s sprites]
            (sprite-card s)))]]))))
