(ns game.test-views
  "Visual test page — zoomed grid for debugging sprites, effects, and animations.
   Renders pre-built scenarios frame by frame. No SSE, pure server-rendered."
  (:require [hiccup2.core :as h]
            [game.views :as views]
            [game.test-scenes :as scenes]))

(defn test-page
  "Full HTML page for /test. Scenario and frame are query params."
  [scenario-idx frame]
  (let [scenario (scenes/get-scenario scenario-idx)
        {:keys [state events description]} (scenes/get-frame scenario frame)
        total (:total-frames scenario)]
    (str
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (str "Visual Test — " (:name scenario))]
        [:link {:rel "stylesheet" :href "/css/spectator.css"}]
        [:link {:rel "stylesheet" :href "/css/test.css"}]]
       [:body.test-body
        (views/nav-bar :test)

        ;; Header
        [:div.test-header
         [:h1 "VISUAL TEST"]
         [:div.test-controls
          ;; Scenario dropdown
          [:select#scenario-select.map-select
           {:onchange "switchScenario(this.value)"}
           (map-indexed
            (fn [i s]
              [:option {:value i :selected (= i scenario-idx)}
               (:name s)])
            scenes/scenarios)]

          ;; Frame nav
          [:span.frame-info
           (str "Frame " (inc (mod frame total)) "/" total)]
          [:span.tick-info
           (str "Tick " (:tick state))]

          [:button.scrub-btn {:onclick "step(-1)" :title "Previous (J)"}
           "\u25C0 J"]
          [:button.scrub-btn {:onclick "step(1)" :title "Next (K)"}
           "K \u25B6"]
          [:button.scrub-btn.resume-btn {:onclick "autoPlay()" :title "Auto-play"}
           "\u25B6\u25B6 Auto"]]]

        [:div.test-arena
         ;; Zoomed grid
         [:div.test-map
          [:div.test-grid-label "ZOOMED GRID"]
          [:div#test-grid.test-grid-container
           (views/game-map-fragment state events {:sprite-height 64})]]

         ;; Sidebar
         [:div.test-sidebar
          ;; Scoreboard
          [:div.panel
           [:h2 "SCOREBOARD"]
           [:div#test-scoreboard
            (views/scoreboard-fragment state)]]

          ;; Events
          [:div.panel
           [:h2 "EVENTS"]
           [:div#test-events
            (views/event-feed-fragment events)]]

          ;; Frame description
          [:div.panel.description-panel
           [:h2 "THIS FRAME"]
           [:div.frame-description description]]]]

        ;; Keyboard + auto-play JS
        [:script
         (h/raw
          (str "
var scenario = " scenario-idx ";
var frame = " frame ";
var total = " total ";
var autoTimer = null;

function navigate(s, f) {
  var url = '/test?scenario=' + s + '&frame=' + f;
  history.replaceState(null, '', url);
  location.reload();
}

function step(delta) {
  frame = ((frame + delta) % total + total) % total;
  navigate(scenario, frame);
}

function switchScenario(s) {
  navigate(parseInt(s), 0);
}

function autoPlay() {
  if (autoTimer) { clearInterval(autoTimer); autoTimer = null; return; }
  autoTimer = setInterval(function() { step(1); }, 500);
}

document.addEventListener('keydown', function(e) {
  if (e.target.tagName === 'SELECT') return;
  if (e.key === 'j' || e.key === 'J' || e.key === 'ArrowLeft') {
    e.preventDefault(); step(-1);
  } else if (e.key === 'k' || e.key === 'K' || e.key === 'ArrowRight') {
    e.preventDefault(); step(1);
  } else if (e.key === ' ') {
    e.preventDefault(); autoPlay();
  }
});
"))]]]))))
