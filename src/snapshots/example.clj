(ns snapshots.example
  (:use [overtone.live]
        [overtone.inst.sampled-piano]
        [clojure.pprint])
  (:require [snapshots.core :as snap]))


(def c (osc-client "localhost" 9851))
(def s (snap/snapshots-server 9851))

(def recv-s (osc-server 9852))
(osc-listen recv-s (fn [msg] (println " listener: " msg)) :foo)

(osc-send c "/store" "/my-stream" "/note/off" 60 10 48)

(osc-send c "/fetch" "/my-stream" 42 "localhost" 9852)
















(on-event [:midi :note-on]
          (fn [msg]
            (let [vel  (float (/ (:velocity msg) 127))
                  note (:note msg)]
              (sampled-piano note :level vel)
              (osc-send c "/store" "/sam/piano2" "/note/on" note vel)
              (println [note vel])))
          ::debug)


(osc-send c "/query" "/sam/piano2" 42 "localhost" 9852 "take 2")































(defn query-snapshot
  ([snapshot] (query-snapshot snapshot []))
  ([snapshot & query-params]
     (apply snap/history-query @(get @(:history s) snapshot) query-params)))

(let [snapshot (query-snapshot "/sam/piano2"  "take 5")]
  (map #(at (+ (now) (- (:ts %) (:ts (first snapshot)))) (sampled-piano (first (:args %)))) snapshot))

(let [snapshot (query-snapshot "/sam/piano2" "filter (or (= (event :arg 0) 60) (= (event :arg 0) 64))" "take 10")]
  (map #(at (+ (now) (- (:ts %) (:ts (first snapshot)))) (sampled-piano (first (:args %)))) snapshot))
