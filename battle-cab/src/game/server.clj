(ns game.server
  "HTTP server — REST API + spectator SSE + WebSocket. Delegates state to game.engine."
  (:require [game.core :as core]
            [game.maps :as maps]
            [game.engine :as engine]
            [game.sse :as sse]
            [game.ws :as ws]
            [game.views :as views]
            [game.test-views :as test-views]
            [game.sprite-viewer :as sprite-viewer]
            [org.httpkit.server :as http]
            [reitit.ring :as reitit]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn parse-json-body
  "Parse JSON request body into a map with string keys."
  [request]
  (when-let [body (:body request)]
    (try
      (cond
        (string? body) (json/read-str body)
        (instance? java.io.InputStream body)
        (json/read-str (slurp body))
        :else nil)
      (catch Exception _ nil))))

(defn wrap-json [handler]
  (fn [request]
    (let [request (if (and (some-> (get-in request [:headers "content-type"])
                                   (str/includes? "json"))
                           (:body request))
                    (assoc request :body (parse-json-body request))
                    request)]
      (handler request))))

(defn- sys [] @engine/system)

(defn authenticate [request]
  (let [token (or (get-in request [:headers "authorization"])
                  (get-in request [:query-params "token"])
                  (get-in request [:params "token"])
                  (get-in request [:body "token"]))]
    (engine/authenticate (sys) token)))

;;; ---------------------------------------------------------------------------
;;; Route Handlers — Bot API
;;; ---------------------------------------------------------------------------

(defn handle-join [request]
  (let [body (:body request)
        player-name (get body "name" "anonymous")]
    (if-let [creds (engine/add-player! (sys) player-name)]
      (json-response 200 {:player-id (:id creds)
                          :token (:token creds)
                          :message (str "Welcome, " player-name "!")})
      (json-response 400 {:error "Game is full"}))))

(defn handle-state [request]
  (if-let [player-id (authenticate request)]
    (json-response 200 (core/player-view (engine/get-state) player-id))
    (json-response 401 {:error "Invalid token"})))

(defn handle-action [request]
  (if-let [player-id (authenticate request)]
    (let [body (:body request)
          action {:type (keyword (get body "action"))
                  :direction (get body "direction")
                  :angle (get body "angle")
                  :dx (get body "dx")
                  :dy (get body "dy")}]
      (engine/enqueue-command! (sys) player-id action)
      (json-response 200 {:status "queued" :tick (:tick (engine/get-state))}))
    (json-response 401 {:error "Invalid token"})))

(defn handle-scoreboard [_request]
  (let [state (engine/get-state)
        scores (->> (:players state)
                    (map (fn [[id p]]
                           {:id id
                            :name (:name p)
                            :score (:score p)
                            :alive (:alive? p)
                            :kills 0}))
                    (sort-by :score >))]
    (json-response 200 {:tick (:tick state)
                        :scores scores})))

(defn handle-map [_request]
  (let [state (engine/get-state)]
    (json-response 200 {:width (get-in state [:map :width])
                        :height (get-in state [:map :height])
                        :walls (vec (get-in state [:map :walls]))
                        :ascii (maps/render-state-ascii state)})))

(defn handle-status [_request]
  (let [state (engine/get-state)]
    (json-response 200
                   {:tick (:tick state)
                    :players (count (:players state))
                    :passengers (count (filter #(nil? (:picked-up-by %))
                                               (:passengers state)))
                    :spectators (sse/subscriber-count)
                    :running (some? @(:game-timer (sys)))})))

(defn handle-ascii [_request]
  (let [state (engine/get-state)]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (str (maps/render-state-ascii state) "\n"
                "Tick: " (:tick state) "\n"
                "Scores: "
                (str/join ", "
                          (map (fn [[id p]] (str (:name p) ":" (:score p)))
                               (:players state))))}))

;;; ---------------------------------------------------------------------------
;;; Route Handlers — Spectator
;;; ---------------------------------------------------------------------------

(defn handle-spectator-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (views/spectator-page)})

(defn handle-spectate [request]
  (sse/handle-spectate request))

(defn handle-map-swap [request]
  (let [body (:body request)
        map-id (get body "map" "arena")
        game-map (maps/get-map-by-id map-id)]
    (engine/swap-map! (sys) game-map)
    (sse/reload-browsers!)
    (json-response 200 {:status "map-swapped" :map map-id})))

(defn handle-restart [request]
  (let [body (:body request)
        map-id (get body "map" "arena")
        game-map (maps/get-map-by-id map-id)]
    (engine/stop-game!)
    (let [sys (engine/start-game! {:on-tick #'sse/on-tick
                                   :game-map game-map})]
      ;; Start 5 demo bots
      (require 'game.bots)
      (let [start-team! (resolve 'game.bots/start-team!)
            hunter (resolve 'game.bots/hunter-think)
            courier (resolve 'game.bots/courier-think)
            random (resolve 'game.bots/random-think)]
        (start-team!
         [{:name "Hunter-1" :think-fn @hunter}
          {:name "Hunter-2" :think-fn @hunter}
          {:name "Courier-1" :think-fn @courier}
          {:name "Courier-2" :think-fn @courier}
          {:name "RandomBot" :think-fn @random}]))
      (sse/reload-browsers!)
      (json-response 200 {:status "restarted" :map map-id :tick 0}))))

(defn handle-lightning [_request]
  (let [result (engine/trigger-lightning! (sys))]
    (json-response 200 {:status "lightning"
                        :x (:x result)
                        :y (:y result)
                        :radius (:radius result)})))

(defn handle-seek [request]
  (let [body (:body request)
        tick (get body "tick" 0)]
    (let [result (engine/seek-to-tick! (sys) tick)]
      (json-response 200 {:status "seeked"
                          :tick (:tick result)
                          :max-tick (:max-tick result)}))))

(defn handle-resume [_request]
  (engine/resume-game!)
  (json-response 200 {:status "resumed"}))

(defn handle-sprite-viewer [request]
  (let [selected (get-in request [:query-params "sprite"])
        frame-str (get-in request [:query-params "frame"])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (sprite-viewer/sprite-viewer-page selected frame-str)}))

(defn handle-test [request]
  (let [params (:query-params request)
        scenario (Integer/parseInt (or (get params "scenario") "0"))
        frame (Integer/parseInt (or (get params "frame") "0"))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (test-views/test-page scenario frame)}))

;;; ---------------------------------------------------------------------------
;;; Router
;;; ---------------------------------------------------------------------------

(def app
  (-> (reitit/ring-handler
       (reitit/router
        [;; Bot API
         ["/game/join" {:post {:handler #'handle-join}}]
         ["/game/state" {:get {:handler #'handle-state}}]
         ["/game/action" {:post {:handler #'handle-action}}]
         ["/game/scoreboard" {:get {:handler #'handle-scoreboard}}]
         ["/game/map" {:get {:handler #'handle-map}}]
         ["/game/status" {:get {:handler #'handle-status}}]
         ["/game/ascii" {:get {:handler #'handle-ascii}}]
         ;; Spectator
         ["/" {:get {:handler #'handle-spectator-page}}]
         ["/spectate" {:get {:handler #'handle-spectate}}]
         ["/spectate-ws" {:get {:handler #'ws/handle-ws-spectate}}]
         ["/game/restart" {:post {:handler #'handle-restart}}]
         ["/game/map-swap" {:post {:handler #'handle-map-swap}}]
         ["/game/lightning" {:post {:handler #'handle-lightning}}]
         ["/game/seek" {:post {:handler #'handle-seek}}]
         ["/game/resume" {:post {:handler #'handle-resume}}]
         ;; Visual test & tools
         ["/test" {:get {:handler #'handle-test}}]
         ["/sprite-viewer" {:get {:handler #'handle-sprite-viewer}}]
         ;; Dev reload endpoint (browser-reload polls this)
         ["/dev/reload-check" {:get {:handler (fn [_]
                                                (if-let [handler (resolve 'browser-reload.core/reload-check-handler)]
                                                  (handler _)
                                                  {:status 200 :body "0"}))}}]])
       (reitit/create-default-handler)
       {:middleware [wrap-params wrap-json]})
      (wrap-resource "public")
      wrap-content-type))

;;; ---------------------------------------------------------------------------
;;; Main
;;; ---------------------------------------------------------------------------

(defn- make-dev-app
  "Wrap app with code-reload + browser-reload for dev mode."
  []
  (let [wrap-reload-script (requiring-resolve 'browser-reload.core/wrap-reload-script)
        wrap-reload (requiring-resolve 'ring.middleware.reload/wrap-reload)]
    (-> #'app
        wrap-reload-script
        (wrap-reload {:dirs ["src" "resources"]
                      :reload-compile-errors? true}))))

(defn- on-tick-all
  "Composed on-tick: pushes to both SSE (HTML) and WebSocket (JSON) spectators."
  [sys game-state]
  (sse/on-tick sys game-state)
  (ws/on-tick sys game-state))

(defn -main [& _args]
  (engine/start-game! {:on-tick #'on-tick-all})
  (let [port (Integer/parseInt (or (System/getenv "PORT") "33333"))
        is-dev (= "dev" (System/getenv "ENV"))
        handler (if is-dev (make-dev-app) #'app)]
    (when is-dev
      (when-let [start-watcher (requiring-resolve 'browser-reload.core/start-file-watcher!)]
        (start-watcher ["src" "resources"] #{"clj" "css" "js" "html" "edn"}))
      (log/info :dev-mode :reload true :browser-reload true))
    (http/run-server handler {:port port})
    (log/info :server-started :port port
              :endpoints ["/game/join" "/game/state" "/game/action"
                          "/game/scoreboard" "/game/map" "/game/ascii"
                          "/" "/spectate" "/spectate-ws"])))
