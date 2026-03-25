(ns game.server
  "HTTP server — REST API + spectator SSE. Delegates state to game.engine."
  (:require [game.core :as core]
            [game.maps :as maps]
            [game.engine :as engine]
            [game.sse :as sse]
            [game.views :as views]
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
         ["/spectate" {:get {:handler #'handle-spectate}}]])
       (reitit/create-default-handler)
       {:middleware [wrap-params wrap-json]})
      (wrap-resource "public")
      wrap-content-type))

;;; ---------------------------------------------------------------------------
;;; Main
;;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (engine/start-game! {:on-tick #'sse/on-tick})
  (let [port (Integer/parseInt (or (System/getenv "PORT") "33333"))]
    (http/run-server #'app {:port port})
    (log/info :server-started :port port
              :endpoints ["/game/join" "/game/state" "/game/action"
                          "/game/scoreboard" "/game/map" "/game/ascii"
                          "/" "/spectate"])))
