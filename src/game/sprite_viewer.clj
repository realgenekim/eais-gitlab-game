(ns game.sprite-viewer
  "Sprite sheet viewer — shows all frames side by side and animated.
   /sprite-viewer endpoint."
  (:require [hiccup2.core :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [game.views :as views]))

(defn- list-sprites
  "List game-ready PNG files with their dimensions."
  []
  (let [dir (io/file "resources/public/sprites")]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".png"))
           (remove #(str/includes? (.getName %) "-hires"))
           (remove #(str/includes? (.getName %) "-annotated"))
           (remove #(str/includes? (.getName %) "-source"))
           (mapv (fn [f]
                   (let [img (javax.imageio.ImageIO/read f)
                         w (.getWidth img) h (.getHeight img)
                         n 4 ;; assume 4 frames
                         fw (/ w n)]
                     {:name (.getName f) :width w :height h
                      :frame-w fw :frame-h h :n-frames n
                      :aspect (/ (double fw) h)})))
           (sort-by :name)))))

(def ^:private css-text
  ":root { --bg: #0a0a0f; --bg-panel: #12121a; --border: #2a2a3a;
        --text: #e0e0e0; --text-dim: #888; --neon-cyan: #4ecdc4; --neon-yellow: #ffe66d; }
* { margin: 0; padding: 0; box-sizing: border-box; }
.nav-bar { display:flex;gap:0;background:#0d0d14;border-bottom:1px solid var(--border);font-size:0.75rem;letter-spacing:0.1em; }
.nav-link { color:#888;text-decoration:none;padding:0.3rem 1rem;text-transform:uppercase;font-weight:bold;font-family:'Helvetica Neue',Arial,sans-serif;transition:color 0.15s,background 0.15s; }
.nav-link:hover { color:#4ecdc4;background:rgba(78,205,196,0.08); }
.nav-link.active { color:#ffe66d;border-bottom:2px solid #ffe66d; }
body { background: var(--bg); color: var(--text); font-family: 'Courier New', monospace; padding: 0; }
h1 { padding: 1.5rem 2rem; }
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
/* Composed animation — 4 stacked frames cycling visibility (same as emoji sprites) */
.anim-composed { position: relative; display: inline-block; }
.anim-composed .anim-frame { position: absolute; top: 0; left: 0;
  image-rendering: pixelated; background-repeat: no-repeat; opacity: 0; }
.anim-composed .af0 { animation: af-show 1s steps(1) infinite 0s; }
.anim-composed .af1 { animation: af-show 1s steps(1) infinite -0.75s; }
.anim-composed .af2 { animation: af-show 1s steps(1) infinite -0.5s; }
.anim-composed .af3 { animation: af-show 1s steps(1) infinite -0.25s; }
@keyframes af-show { 0% { opacity: 1; } 25% { opacity: 0; } 100% { opacity: 0; } }
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

/* Step sections */
.step-section { margin-top: 1.5rem; padding: 1rem; border: 1px solid var(--border);
  border-radius: 4px; background: rgba(255,255,255,0.02); }
.step-header { color: var(--neon-yellow) !important; font-size: 1.1rem !important;
  letter-spacing: 0.15em; margin-bottom: 0.3rem !important; }
.step-desc { color: var(--text-dim); font-size: 0.8rem; margin-bottom: 1rem;
  font-style: italic; }

/* Color-tinted overlays for distinguishing frames */
.stacked-tinted .f0 { filter: hue-rotate(0deg); }
.stacked-tinted .f1 { filter: hue-rotate(90deg); }
.stacked-tinted .f2 { filter: hue-rotate(180deg); }
.stacked-tinted .f3 { filter: hue-rotate(270deg); }

/* Frame inspector */
.frame-inspector { margin-top: 1rem; }
.frame-inspector-label { color: var(--neon-yellow); font-size: 1rem; font-weight: bold;
  min-width: 5rem; text-align: center; }
.scrub-btn { background: var(--bg-panel); color: var(--neon-cyan); border: 1px solid var(--border);
  padding: 0.3rem 0.8rem; font-size: 1rem; cursor: pointer; border-radius: 4px;
  font-family: inherit; }
.scrub-btn:hover { background: rgba(78,205,196,0.15); }
")

(defn- frame-style
  "CSS for showing a single frame from a sprite sheet. Height-based sizing."
  [url n-frames aspect i h]
  (let [w (int (* h aspect))]
    (str "width:" w "px;height:" h "px;overflow:hidden;"
         "background-image:url(" url ");"
         "background-size:" (* 100 n-frames) "% 100%;"
         "background-position:" (if (= n-frames 1) "0" (* i (/ 100.0 (dec n-frames)))) "% 0%;"
         "image-rendering:pixelated;")))

(defn- anim-style
  "CSS for animated sprite preview. Height-based sizing with correct aspect."
  [url n-frames aspect h]
  (let [w (int (* h aspect))]
    (str "width:" w "px;height:" h "px;"
         "background-image:url(" url ");"
         "background-size:" (* 100 n-frames) "% 100%;")))

(defn- sprite-card [{:keys [name n-frames aspect] :as sprite-info}]
  (let [url (str "/sprites/" name)
        base-name (str/replace name #"(-hires|-annotated)?\.png$" "")
        annotated-url (str "/sprites/" base-name "-annotated.png")
        annotated? (.exists (io/file (str "resources/public/sprites/" base-name "-annotated.png")))]
    [:div.sprite-sheet
     [:div.sprite-name name]
     [:div.sprite-meta (str url " \u2022 " n-frames " frames \u2022 "
                            (:frame-w sprite-info) "x" (:frame-h sprite-info) " per frame"
                            " \u2022 aspect " (format "%.2f" aspect) ":1")]

     ;; ========== STEP 1: CONFIRM REGISTRATION ==========
     [:div.step-section
      [:h2.step-header "STEP 1: CONFIRM REGISTRATION"]
      [:p.step-desc "Registration lines from " base-name ".edn. Verify car body aligns across all frames. If not, adjust EDN and re-annotate."]

      ;; Annotated source image
      (when annotated?
        [:div {:style "margin-bottom:1rem;"}
         [:img.checkerboard {:src annotated-url
                             :style "max-width:100%;height:auto;image-rendering:pixelated;border:1px solid var(--border);"}]])

      ;; Stacked overlays
      [:div.stacked-row
       (for [[label h] [["128px" 128] ["256px" 256] ["256px tinted" 256]]]
         (let [w (int (* h aspect))
               tinted? (str/includes? label "tinted")]
           [:div.stacked-box
            [:div.anim-label label]
            [:div.stacked-container.checkerboard
             {:class (when tinted? "stacked-tinted")
              :style (str "width:" w "px;height:" h "px;")}
             (for [i (range n-frames)]
               [:div.stacked-frame
                {:class (str "f" i)
                 :style (str "width:" w "px;height:" h "px;"
                             (when tinted? "opacity:0.6;")
                             "background-image:url(" url ");"
                             "background-size:" (* 100 n-frames) "% 100%;"
                             "background-position:" (* i (/ 100.0 (dec n-frames))) "% 0%;")}])]]))]]

     ;; ========== STEP 2: CONFIRM ANIMATION ==========
     [:div.step-section
      [:h2.step-header "STEP 2: CONFIRM ANIMATION"]
      [:p.step-desc "Generated sprite sheet from registered frames. Verify smooth animation with no bleeding or jitter."]

      ;; Individual frames
      [:h2 {:style "margin-top:0.5rem;"} "INDIVIDUAL FRAMES"]
      [:div.frames-row
       (for [i (range n-frames)]
         [:div.frame-box
          [:div.frame-label (str "Frame " (inc i))]
          [:div.frame-img.checkerboard {:style (frame-style url n-frames aspect i 128)}]])]

      ;; Frame inspector — arrow keys, URL state
      [:div.frame-inspector {:id (str "inspector-" base-name)
                             :data-sprite base-name
                             :data-url url
                             :data-n-frames (str n-frames)
                             :data-aspect (str aspect)}
       [:h2 {:style "margin-top:1rem;"} "FRAME INSPECTOR"]
       [:div {:style "display:flex;align-items:center;gap:1rem;margin:0.5rem 0;"}
        [:button.scrub-btn {:onclick (str "inspectorNav('" base-name "',-1)")} "\u25C0"]
        [:span.frame-inspector-label {:id (str "inspector-label-" base-name)} "Frame 1"]
        [:button.scrub-btn {:onclick (str "inspectorNav('" base-name "',1)")} "\u25B6"]
        [:span {:style "color:var(--text-dim);font-size:0.75rem;margin-left:0.5rem;"} "(← → arrow keys)"]]
       [:div.frame-inspector-view.checkerboard
        {:id (str "inspector-view-" base-name)
         :style (let [h 256 w (int (* h aspect))]
                  (str "width:" w "px;height:" h "px;overflow:hidden;"
                       "background-image:url(" url ");"
                       "background-size:" (* 100 n-frames) "% 100%;"
                       "background-position:0% 0%;"
                       "image-rendering:pixelated;"
                       "border:1px solid var(--border);"))}]]

      ;; Animated at various sizes — composed from same frames as inspector
      [:h2 {:style "margin-top:1rem;"} "ANIMATED"]
      [:div.anim-row
       (for [h [32 48 64 96 128 192]]
         (let [w (int (* h aspect))]
           [:div.anim-box
            [:div.anim-label (str h "px")]
            [:div.anim-composed.checkerboard {:style (str "width:" w "px;height:" h "px;")}
             (for [i (range n-frames)]
               [:div.anim-frame
                {:class (str "af" i)
                 :style (frame-style url n-frames aspect i h)}])]]))]

      ;; Full sheet
      [:div.full-sheet
       [:h2 "FULL SHEET"]
       [:img.checkerboard {:src url :style "height:96px;"}]]]]))

(def ^:private inspector-js
  "// Frame inspector state & navigation
var inspectorState = {};

function initInspectors() {
  document.querySelectorAll('.frame-inspector').forEach(function(el) {
    var name = el.dataset.sprite;
    var n = parseInt(el.dataset.nFrames);
    var aspect = parseFloat(el.dataset.aspect);
    // Read initial frame from URL
    var params = new URLSearchParams(window.location.search);
    var f = parseInt(params.get('frame')) || 0;
    if (f < 0 || f >= n) f = 0;
    inspectorState[name] = {frame: f, nFrames: n, aspect: aspect};
    updateInspector(name);
  });
}

function inspectorNav(name, delta) {
  var s = inspectorState[name];
  if (!s) return;
  s.frame = (s.frame + delta + s.nFrames) % s.nFrames;
  updateInspector(name);
  updateURL();
}

function updateInspector(name) {
  var s = inspectorState[name];
  var view = document.getElementById('inspector-view-' + name);
  var label = document.getElementById('inspector-label-' + name);
  if (!view || !label) return;
  var pct = s.nFrames === 1 ? 0 : (s.frame / (s.nFrames - 1)) * 100;
  view.style.backgroundPosition = pct + '% 0%';
  label.textContent = 'Frame ' + (s.frame + 1) + ' / ' + s.nFrames;
}

function updateURL() {
  var params = new URLSearchParams(window.location.search);
  // Use first inspector's frame (single-sprite view)
  var names = Object.keys(inspectorState);
  if (names.length === 1) {
    params.set('frame', inspectorState[names[0]].frame);
  } else if (names.length > 1) {
    // Multi-sprite: encode as sprite:frame
    var parts = names.map(function(n) { return n + ':' + inspectorState[n].frame; });
    params.set('frame', parts.join(','));
  }
  history.replaceState(null, '', '?' + params.toString());
}

document.addEventListener('keydown', function(e) {
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    e.preventDefault();
    var delta = e.key === 'ArrowRight' ? 1 : -1;
    // Navigate all visible inspectors
    Object.keys(inspectorState).forEach(function(name) {
      inspectorNav(name, delta);
    });
  }
});

document.addEventListener('DOMContentLoaded', initInspectors);
")

(defn sprite-viewer-page
  "Render the sprite viewer. Optional `selected` name filters to one sprite."
  ([] (sprite-viewer-page nil nil))
  ([selected] (sprite-viewer-page selected nil))
  ([selected frame-str]
   (let [sprites (list-sprites)
         shown (if selected
                 (filter #(= (:name %) selected) sprites)
                 sprites)]
     (str
      (h/html
       [:html {:lang "en"}
        [:head
         [:meta {:charset "utf-8"}]
         [:title "Sprite Viewer"]
         [:style (h/raw css-text)]
         [:script (h/raw inspector-js)]]
        [:body
         (views/nav-bar :sprites)
         [:h1 "SPRITE VIEWER"]
         [:div {:style "display:flex;align-items:center;gap:1rem;margin:0 2rem 1.5rem;"}
          [:select {:onchange "window.location.href='/sprite-viewer'+(this.value ? '?sprite='+this.value : '')"
                    :style "background:var(--bg-panel);color:var(--text);border:1px solid var(--border);padding:0.4rem 0.8rem;font-family:inherit;font-size:0.9rem;border-radius:4px;cursor:pointer;"}
           [:option {:value "" :selected (when-not selected "selected")} "All sprites"]
           (for [{:keys [name]} sprites]
             [:option {:value name :selected (when (= name selected) "selected")} name])]
          [:span {:style "color:var(--text-dim);font-size:0.8rem;"}
           (str (count sprites) " sprite sheet(s)")]]
         (if (empty? shown)
           [:div {:style "color:var(--text-dim);font-style:italic;padding:2rem;text-align:center;"}
            "No sprite sheets found. Drop PNGs in resources/public/sprites/"]
           (for [s shown]
             (sprite-card s)))]])))))
