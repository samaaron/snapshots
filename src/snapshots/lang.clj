(ns snapshots.lang
  (:use [clojure.pprint]
        [overtone.osc :only [osc-server osc-handle osc-now osc-client osc-send osc-close]])
  (:require [clojure.string :as str]))

(defn wrap-osc-paths-as-string
  [path]
  (string/replace path #"/[a-zA-Z0-9/_-]+" #(str "\"" % "\"")))


(defn extract-atom
  [atom event]
  ())

(defn mk-pred-fn
  [form event]
  )

(def binary-ops
  {"not=" not=
   "=" =})

(defn extract-value
  [val-name event]
  (case val-name
    "path" (:path event)))

(defn filter-drop-while
  [contents identifier op val]
  (let [op-fn (get binary-ops op)]
    (drop-while #(op-fn (extract-value identifier %) val) contents)))

(defn filter-take-while
  [contents identifier op val]
  (let [op-fn (get binary-ops op)]
    (take-while #(op-fn (extract-value identifier %) val) contents)))

(defn filter-drop
  [contents n]
  (let [n (Integer. n)]
    (drop n contents)))

(defn filter-take
  [contents n]
  (let [n (Integer. n)]
    (take n contents)))

(defn filter-filter
  [contents identifier op val]
  (let [op-fn (get binary-ops op)]
    (filter #(op-fn (extract-value identifier %) val) contents)))

(defn filter-remove
  [contents identifier op val]
  (let [op-fn (get binary-ops op)]
    (remove #(op-fn (extract-value identifier %) val) contents)))

(defn filter-contents
  [filter contents]
  (println "filter: " filter )
  (let [[cmd & args] (clojure.string/split filter #"\s+")]
    (case cmd
        "drop-while" (apply filter-drop-while contents args)
        "take-while" (apply filter-take-while contents args)
        "drop" (apply filter-drop contents args)
        "take" (apply filter-take contents args)
        "filter" (apply filter-filter contents args)
        "remove" (apply filter-remove contents args))))

(defn- history-fetch
  [storage* store-name query-id & args]
  (let [[host port] args]
    (println "fetchings: " store-name query-id host port)
    (let [c        (osc-client host port)
          snapshot (get @storage* store-name)]
      (if-not snapshot
        (osc-send c (str "snapshots/fetch-response/" query-id "/snapshot-not-found"))
        (let [snapshot @snapshot]
          (osc-send c (str "/snapshots/fetch-response/" query-id "/init") (count snapshot))
          (doseq [[idx m] (partition 2 (interleave (range) snapshot))]
            (osc-send c (str "/snapshots/fetch-response/" query-id) idx store-name (prn-str m)))
          (osc-send c (str "/snapshots/fetch-response/" query-id "/completed"))))
      ;; TODO: figure out how to close c nicely
      ;; (osc-close c)
      )))

(defn history-query
  [snapshot & filters]
  (if-not (or (empty? snapshot) (empty? filters))
    (recur (filter-contents (first filters) snapshot) (rest filters))
    snapshot))

(defn snapshots-server
  [port]
  (let [server (osc-server port)
        storage* (ref {})]

    (osc-handle server "/store"
                (fn [{[store-name event-path & args] :args}]
                  (apply history-store storage* store-name event-path args)))
    (osc-handle server "/query"
                (fn [{[query-id store-name host-name port & args] :args}]
                  (apply handle-query storage* query-id store-name host-name port args)))
    (osc-handle server "/fetch"
                (fn [{[store-name query-id & args] :args}]
                  (apply handle-fetch storage* store-name query-id args)))
    (osc-handle server "/snapshot"
                (fn [{[store-name query-id & args] :args}]
                  (apply handle-snapshot storage* store-name query-id args)))
    (SnapshotsServer. server storage*)))
