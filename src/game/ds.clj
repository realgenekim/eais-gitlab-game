(ns game.ds
  "Datastar expression helpers — safe signal arithmetic + keydown builders.
   Adapted from clojure-mcp-template/ds.clj for Cab Battle."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Signal arithmetic — prevents the $foo-1 camelCase parsing bug
;; ---------------------------------------------------------------------------

(defn signal-inc
  "Increment a signal, clamped to max-val."
  [signal max-val]
  (str signal "=Math.min((" signal ") + 1," max-val ")"))

(defn signal-dec
  "Decrement a signal, clamped to 0."
  [signal]
  (str signal "=Math.max((" signal ") - 1, 0)"))

(defn signal-set
  "Set a signal to a value."
  [signal value]
  (str signal "=" value))

;; ---------------------------------------------------------------------------
;; Server action helpers — inline fetch() calls for Datastar expressions
;; ---------------------------------------------------------------------------

(defn post-action
  "Generate an inline fetch() POST call for Datastar on:click expressions."
  [endpoint payload-map]
  (let [pairs (str/join "," (map (fn [[k v]] (str (name k) ":" v)) payload-map))
        opts  (str "{method:'POST',headers:{'Content-Type':'application/json'},"
                   "body:JSON.stringify({" pairs "})}")
        catch ".catch(e=>console.error(e))"]
    (str "fetch('" endpoint "'," opts ")" catch)))
