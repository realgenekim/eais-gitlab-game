(ns game.replay
  "Command logging and deterministic replay engine.
   Every command and state transition is persisted for full replay."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [game.core :as core]))

;;; ---------------------------------------------------------------------------
;;; Command Log — append-only, immutable history
;;; ---------------------------------------------------------------------------

(defn make-recorder
  "Create a new game recorder. Returns an atom holding the full history."
  [initial-state]
  (atom {:initial-state initial-state
         :command-log   []       ;; [{:tick N :commands [...] :timestamp inst}]
         :snapshots     {0 initial-state}}))

(defn record-tick!
  "Record commands and resulting state for a tick."
  [recorder tick-num commands new-state]
  (swap! recorder
         (fn [r]
           (-> r
               (update :command-log conj
                       {:tick      tick-num
                        :commands  commands
                        :timestamp (java.util.Date.)})
               ;; Snapshot every 10 ticks for fast seek
               (cond-> (zero? (mod tick-num 10))
                 (assoc-in [:snapshots tick-num] new-state))))))

;;; ---------------------------------------------------------------------------
;;; Replay — deterministic playback from command log
;;; ---------------------------------------------------------------------------

(defn replay-to-tick
  "Replay the command log up to target-tick. Returns the state at that tick."
  [recorder target-tick]
  (let [{:keys [initial-state command-log snapshots]} @recorder
        ;; Find nearest snapshot at or before target
        nearest-snap  (->> (keys snapshots)
                           (filter #(<= % target-tick))
                           sort
                           last)
        start-state   (get snapshots (or nearest-snap 0) initial-state)
        start-tick    (or nearest-snap 0)
        relevant-cmds (->> command-log
                           (filter #(and (>= (:tick %) start-tick)
                                         (< (:tick %) target-tick)))
                           (sort-by :tick))]
    (reduce (fn [state {:keys [commands]}]
              (core/advance-tick state commands))
            start-state
            relevant-cmds)))

;;; ---------------------------------------------------------------------------
;;; Persistence — save/load to JSONL
;;; ---------------------------------------------------------------------------

(defn save-recording!
  "Save the full recording to a JSONL file."
  [recorder filepath]
  (let [{:keys [initial-state command-log]} @recorder]
    (with-open [w (io/writer filepath)]
      ;; Line 1: initial state (without walls as set — convert to vec)
      (.write w (json/write-str
                 {:type "init"
                  :state (update-in initial-state [:map :walls] vec)}))
      (.newLine w)
      ;; Remaining lines: one per tick
      (doseq [entry command-log]
        (.write w (json/write-str
                   {:type "tick"
                    :tick (:tick entry)
                    :commands (:commands entry)
                    :timestamp (str (:timestamp entry))}))
        (.newLine w)))))

(defn load-recording
  "Load a recording from a JSONL file. Returns recorder atom."
  [filepath]
  (with-open [r (io/reader filepath)]
    (let [lines     (line-seq r)
          init-line (json/read-str (first lines) :key-fn keyword)
          init-state (-> (:state init-line)
                         (update-in [:map :walls] set))
          cmd-lines (map #(json/read-str % :key-fn keyword) (rest lines))]
      (atom {:initial-state init-state
             :command-log   (vec (map (fn [l]
                                        {:tick     (:tick l)
                                         :commands (:commands l)
                                         :timestamp (:timestamp l)})
                                      cmd-lines))
             :snapshots     {0 init-state}}))))
