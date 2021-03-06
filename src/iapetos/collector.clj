(ns iapetos.collector
  (:require [iapetos.metric :as metric])
  (:import [io.prometheus.client
            CollectorRegistry
            SimpleCollector
            SimpleCollector$Builder]))

;; ## Protocol

(defprotocol Collector
  "Protocol for Collectors to be registered with a iapetos registry."
  (instantiate [this registry registry-options]
    "Return a `clojure.lang.Delay` with an instance of this collector
     registered to the given `CollectorRegistry`.")
  (metric [this]
    "Return a `iapetos.metric/Metric` for this collector.")
  (label-instance [this instance values]
    "Add labels to the given collector instance produced by `instantiate`."))


;; ## Labels

(defn- label-array
  ^"[Ljava.lang.String;"
  [labels]
  (into-array String (map metric/sanitize labels)))

(defn- label-names
  [labels]
  (map metric/dasherize labels))

(defn- set-labels
  "Attach labels to the given `SimpleCollector` instance."
  [^SimpleCollector instance labels values]
  (let [label->value (->> (for [[k v] values]
                            [(-> k metric/dasherize) v])
                          (into {})
                          (comp str))
        ordered-labels (->> labels (map label->value) (into-array String))]
    (.labels instance ordered-labels)))

;; ## Record

(defn- check-subsystem
  [{subsystem :subsystem} {subsystem' :subsystem}]
  (when (and subsystem subsystem' (not= subsystem subsystem'))
    (throw
      (IllegalArgumentException.
        (format
          "collector subsystem (%s) is conflicting with registry subsystem (%s)."
          (pr-str subsystem')
          (pr-str subsystem)))))
  (or subsystem subsystem'))

(defrecord SimpleCollectorImpl [type
                                namespace
                                name
                                description
                                subsystem
                                labels
                                labels-for-builder
                                builder-constructor
                                lazy?]
  Collector
  (instantiate [this registry registry-options]
    (let [subsystem (check-subsystem this registry-options)
          deferred-collector (delay
                               (-> ^SimpleCollector$Builder
                                   (builder-constructor)
                                   (.name name)
                                   (.namespace namespace)
                                   (.help description)
                                   (.labelNames (label-array labels))
                                   (cond-> subsystem (.subsystem subsystem))
                                   (.register ^CollectorRegistry registry)))]
      (when-not lazy?
        @deferred-collector)
      deferred-collector))
  (metric [_]
    {:name      name
     :namespace namespace})
  (label-instance [_ instance values]
    (set-labels instance labels values)))

(defn make-simple-collector
  "Create a new simple collector representation to be instantiated and
   registered with a iapetos registry."
  [{:keys [^String name
           ^String namespace
           ^String subsystem
           ^String description
           labels
           lazy?]}
   collector-type
   builder-constructor]
  {:pre [type name namespace description]}
  (map->SimpleCollectorImpl
    {:type                collector-type
     :namespace           namespace
     :name                name
     :description         description
     :subsystem           subsystem
     :labels              (label-names labels)
     :labels-for-builder  (label-array labels)
     :builder-constructor builder-constructor
     :lazy?               lazy?}))

;; ## Implementation for Raw Collectors

(defn- raw-metric
  [v]
  {:name      (.getName (class v))
   :namespace "raw"})

(extend-protocol Collector
  io.prometheus.client.SimpleCollector
  (instantiate [this registry _]
    (.register
      ^io.prometheus.client.Collector this
      ^CollectorRegistry registry)
    (delay this))
  (metric [this]
    (raw-metric this))
  (label-instance [_ instance values]
    ;; not possible to read required labels from SimpleCollector :|
    #_(let [labels (.-labelNames ^SimpleCollector instance)]
        (set-labels instance labels values))
    instance)

  io.prometheus.client.Collector
  (instantiate [this registry _]
    (.register
      ^io.prometheus.client.Collector this
      ^CollectorRegistry registry)
    (delay this))
  (metric [this]
    (raw-metric this))
  (label-instance [_ instance _]
    instance))

;; ## Named Collector

(defn named
  [metric ^io.prometheus.client.Collector instance]
  (reify Collector
    (instantiate [_ registry _]
      (.register instance ^CollectorRegistry registry)
      (delay instance))
    (metric [_]
      metric)
    (label-instance [_ _ _]
      (throw (UnsupportedOperationException.)))))

;; ## Collector Bundle

(defn bundle
  [metric instances]
  (let [instances (filter identity instances)]
    (reify Collector
      (instantiate [_ registry _]
        (doseq [^io.prometheus.client.Collector collector instances]
          (.register collector registry))
        (delay instances))
      (metric [_]
        metric)
      (label-instance [_ _ _]
        (throw (UnsupportedOperationException.))))))
