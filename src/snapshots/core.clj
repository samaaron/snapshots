(ns snapshots.core
  (:use [clojure.pprint]
        [overtone.osc :only [osc-server osc-handle osc-now osc-client osc-send osc-close]])
  (:require [clojure.string :as string]))

(defn snapshot-send
  [receiver & args]
  (let [args (map #(if (number? %) (float %) %) args)]
    (apply osc-send receiver args)))

(defrecord SnapshotsServer [server history])

(defn wrap-osc-paths-as-string
  [path]
  (string/replace path #"/[a-zA-Z0-9/_-]+" #(str "\"" % "\"")))

(defn extract-event-info-fn
  [event extract-event-args]
  (if (= :arg (first extract-event-args))
    (fn [] (nth (:args event) (second extract-event-args)))
    (fn [] (get event (first extract-event-args)))
    ))

(declare mk-pred-fn)

(defn atom-as-fn
  [event arg]
  (if (list? arg)
    (mk-pred-fn arg event)
    (fn [] arg)))

(defn pred-and
  [event args]
  (let [args (doall (map (partial atom-as-fn event) args))]
    (fn []
      (reduce (fn [res val]
                (and res (val)))
              true
              args))))

(defn pred-or
  [event args]
  (let [args (map (partial atom-as-fn event) args)]
    (fn []
      (reduce (fn [res val] (or res (val)))
              false
              args))))

(defn pred-not=
  [event args]
  (let [args (map (fn [f] (f)) (map (partial atom-as-fn event) args ))]
    (fn []
      (apply not= args))) )

(defn pred-=
  [event args]
  (let [args (doall (map (fn [f] (f)) (map (partial atom-as-fn event) args )))]
    (fn []
      (apply = args))))

(defn pred-<
  [event args]
  (let [args (map (partial atom-as-fn event) args )]
    (fn []
      (reduce (fn [res val]
                )
              args))))

(defn pred->
  [event args]
  (let [args (map (partial atom-as-fn event) args )]
    (fn []
      (reduce (fn [res val])
              args))))

(defn pred-not
  [event args]
  (not ((atom-as-fn event (first args)))))

(defn mk-pred-fn
  [form event]
  (condp = (first form)
    'and (pred-and event (rest form))
    'or (pred-or event (rest form))
    'not= (pred-not= event (rest form))
    '= (pred-= event (rest form))
    '< (pred-< event (rest form))
    '> (pred-> event (rest form))
    'not (pred-not event (rest form))
    'event (extract-event-info-fn event (rest form))))

(defn extract-value
  [val-name event]
  (case val-name
    "path" (:path event)))

(defn filter-drop-while
  [contents args]
  (drop-while #((atom-as-fn % (first args))) contents))

(defn filter-take-while
  [contents args]
  (take-while #(atom-as-fn % (first args)) contents))

(defn filter-drop
  [contents n]
  (drop (first n) contents))

(defn filter-take
  [contents n]
  (take (first n) contents))

(defn filter-filter
  [contents args]
  (filter #((atom-as-fn % (first args))) contents))

(defn filter-remove
  [contents args]
  (remove #((atom-as-fn % (first args))) contents))

(defn filter-contents
  [filter contents]
  (let [filter (wrap-osc-paths-as-string filter)
        filter-form (read-string (str "(" filter ")"))
        [cmd & args] filter-form ]
    (condp = cmd
        'drop-while (filter-drop-while contents args)
        'take-while (filter-take-while contents args)
        'drop (filter-drop contents args)
        'take (filter-take contents args)
        'filter (filter-filter contents args)
        'remove (filter-remove contents args))))

(defn history-query
  [snapshot & filters]
  (if (or (empty? snapshot) (empty? filters))
    snapshot
    (recur (filter-contents (first filters) snapshot) (rest filters))))

(defn- history-store
  "Stores OSC event information under key store-name in a map contained
  within ref storage*"
  [storage*  store-name event-path & args]

  (let [event-info {:path event-path :args args :ts (osc-now)}]
    (dosync
     (let [bucket* (get @storage* store-name)]
       (if bucket*
         (swap! bucket* conj event-info)
         (alter storage* assoc store-name (atom [event-info])))))))

(defn- history-fetch
  [storage* store-name query-id host-name port & args]
  (let [c        (osc-client host-name port)
        snapshot (get @storage* store-name)]
    (if-not snapshot
      (snapshot-send c (str "snapshots/fetch-response/" query-id "/snapshot-not-found"))
      (let [snapshot @snapshot]
        (snapshot-send c (str "/snapshots/fetch-response/" query-id "/init") (count snapshot))
        (doseq [[idx m] (partition 2 (interleave (range) snapshot))]
          (snapshot-send c (str "/snapshots/fetch-response/" query-id) idx store-name (prn-str m)))
        (snapshot-send c (str "/snapshots/fetch-response/" query-id "/completed"))))
    ;; TODO: figure out how to close c nicely
    ;; (osc-close c)
    ))

(defn- snapshot
  [storage* store-name & args]
  (if-let [snapshot (get @storage* store-name)]
    (let [snapshot @snapshot
          new-snapshot (apply history-query snapshot args)]
      (when-not (get @storage* store-name)
        (alter storage* assoc store-name (atom new-snapshot))))))

(defn- handle-query
  [storage* store-name query-id host-name port & args]
  (let [c        (osc-client host-name port)
        snapshot (get @storage* store-name)]
    (if-not snapshot
      (snapshot-send c (str "/snapshots/query-response/" query-id "/snapshot-not-found"))
      (let [snapshot @snapshot
            subshot (apply history-query snapshot args)]
        (snapshot-send c (str "/snapshots/query-response/" query-id "/init") (count subshot))
        (doseq [[idx m] (partition 2 (interleave (range) subshot))]
          (snapshot-send c (str "/snapshots/query-response/" query-id) idx store-name (prn-str m)))
        (snapshot-send c (str "/snapshots/query-response/" query-id "/completed"))))))

(defn- handle-fetch
  [& args]
  (apply history-fetch args))

(defn- handle-snapshot
  [& args]
  (apply snapshot args))

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
                (fn [{[store-name query-id host-name port & args] :args}]
                  (apply handle-fetch storage* store-name query-id host-name port args)))
    (osc-handle server "/snapshot"
                (fn [{[store-name & args] :args}]
                  (apply handle-snapshot storage* store-name args)))
    (SnapshotsServer. server storage*)))
