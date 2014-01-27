(ns interval-metrics.core
  (:import (clojure.lang Counted
                         IDeref
                         Seqable
                         Indexed)
           (java.util.concurrent.atomic AtomicReference
                                        AtomicLong
                                        AtomicLongArray)
           (com.aphyr.interval_metrics ThreadLocalRandom)))

;; Protocols ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Metric
  (update! [this value]
           "Destructively updates the metric with a new value."))

(defprotocol Snapshot
  (snapshot! [this]
             "Returns a copy of the metric, resetting the original to a blank
             state."))

;; Utilities ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn next-long
  "Returns a pseudo-random long uniformly between 0 and n-1."
  [^long n]
  (ThreadLocalRandom/nextLong2 n))

(def default-uniform-reservoir-size 1028)
(def bits-per-long 63)

(def time-units
  "A map from units to their size in nanoseconds."
  {:nanoseconds   1
   :nanos         1
   :microseconds  1000
   :micros        1000
   :milliseconds  1000000
   :millis        1000000
   :seconds       1000000000
   :minutes       60000000000
   :hours         3600000000000
   :days          86400000000000
   :weeks         604800000000000})

(defn scale
  "Returns a conversion factor s from unit a to unit b, such that (* s
  measurement-in-a) is in units of b."
  [a b]
  (try
    (/ (get time-units a)
       (get time-units b))
    (catch NullPointerException e
      (when-not (contains? time-units a)
        (throw (IllegalArgumentException. (str "Don't know unit " a))))
      (when-not (contains? time-units b)
        (throw (IllegalArgumentException. (str "Don't know unit " b))))
      (throw e))))

(defn quantile
  "Given a sorted Indexed collection, returns the value nearest to the given
  quantile in [0,1]."
  [sorted quantile]
  (assert (<= 0.0 quantile 1.0))
  (let [n (count sorted)]
    (if (zero? n)
      nil
      (nth sorted
           (min (dec n)
                (int (Math/floor (* n quantile))))))))

(defn mean
  [coll]
  (/ (reduce + coll)
     (count coll)))

;; Atomic metrics ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Wraps another metric to support atomic snapshots. Uses a generator function
; to generate new (empty) metric objects when snapshot is called.
(deftype AtomicMetric [^AtomicReference state generator]
  IDeref
  (deref [this]
         (deref (.get state)))

  Metric
  (update! [this value]
           (update! (.get state) value))

  Snapshot
  (snapshot! [this]
             (-> state
               (.getAndSet (generator))
               deref)))

; A little gross since this is a global preference, but hopefully nobody
; else will mind
(prefer-method clojure.core/print-method clojure.lang.IRecord IDeref)

(defn atomic
  "Given a generator function which creates blank metric objects, returns an
  AtomicMetric which provides consistent snapshots of that metric."
  [generator]
  (AtomicMetric. (AtomicReference. (generator))
                 generator))


;; Rates ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def rate-scale
  "The size of a second in nanoseconds."
  1.0e-9)
  
(deftype Rate [^AtomicLong count ^AtomicLong time]
  IDeref
  (deref [this]
         (/ (.get count)
            rate-scale
            (- (System/nanoTime) (.get time))))

  Metric
  (update! [this value]
           (.addAndGet count value)
           this)

  Snapshot
  (snapshot! [this]
             (locking this
               (let [now  (System/nanoTime)
                     t (.getAndSet time now)
                     c  (.getAndSet count 0)]
                 (/ c rate-scale (- now t))))))

(defn ^Rate rate
  "Tracks the rate of values per second. (update rate 3) adds 3 to the rate's
  counter. (deref rate) returns the number of values accumulated divided by the
  time since the last snapshot was taken. (snapshot! rate) returns the number
  of values per second since the last snapshot, and resets the count to zero."
  []
  (Rate. (AtomicLong. 0) (AtomicLong. (System/nanoTime))))

;; Reservoirs ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; A mutable uniform Vitter R reservoir.
(deftype UniformReservoir [^AtomicLong size ^AtomicLongArray values]
  Counted
  (count [this]
           (min (.get size)
                (.length values)))
  
  Metric
  (update! [this value]
           (let [s (.incrementAndGet size)]
             (if (<= s (.length values))
               ; Not full
               (.set values (dec s) value)
               ; Full
               (let [r (next-long s)]
                 (when (< r (.length values))
                   (.set values r value)))))
           this)
  
  IDeref
  ; Create a sorted array of longs.
  (deref [this]
         (let [n (count this)
               ary (long-array n)]
           (dotimes [i n]
             (aset-long ary i (.get values i)))
           (java.util.Arrays/sort ary)
           (seq ary))))

(defn uniform-reservoir*
  "Creates a new uniform reservoir, optionally of a given size. Does not
  support snapshots; simply accrues new values for all time."
  ([]
   (uniform-reservoir* default-uniform-reservoir-size))
  ([^long size]
   (UniformReservoir. (AtomicLong. 0)
                      (let [values (AtomicLongArray. size)]
                        (dotimes [i size]
                          (.set values i 0))
                        values))))

(defn uniform-reservoir
  "Creates a new uniform reservoir, optionally of a given size. Supports atomic
  snapshots."
  ([]
   (atomic uniform-reservoir*))
  ([^long size]
   (atomic #(uniform-reservoir* size))))

;; Combined rates and latencies ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RateLatency [quantiles rate-scale latency-scale rate latencies]
  Metric
  (update! [this time]
           (update! latencies time)
           (update! rate 1))

  Snapshot
  (snapshot! [this]
             (let [rate      (snapshot! rate)
                   latencies (snapshot! latencies)
                   t         (/ (System/currentTimeMillis) 1000)]
               {:time t
                :rate (* rate-scale rate)
                :latencies (->> quantiles
                             (map (fn [q]
                                    [q (when-let [t (quantile latencies q)]
                                         (* t latency-scale))]))
                             (into {}))})))

(defn rate+latency
  "Returns a snapshottable metric which tracks a request rate and the latency
  of requests. Calls to update! on this metric are assumed to be in nanoseconds.
  When a snapshot is taken, returns a map like

  {
   ; The current posix time in seconds
   :time 273885884803/200
   ; The number of updates per second
   :rate 250/13
   ; A map of latency quantiles to latencies, in milliseconds
  } 

  Options:
  
  :quantiles       A list of the quantiles to sample.
  :rate-unit       The unit to report rates in: e.g. :seconds
  :latency-unit    The unit to report latencies in: e.g. :milliseconds
  :reservoir-size  The size of the uniform reservoir used to collect latencies"
  ([] (rate+latency {}))
  ([opts]
   (let [quantiles     (get opts :quantiles [0.0 0.5 0.95 0.99 0.999])
         rate-scale    (scale (get opts :rate-unit :seconds) :seconds)
         latency-scale (scale :nanos (get opts :latency-unit :millis))
         rate          (rate)
         reservoir     (if-let [s (:reservoir-size opts)]
                         (uniform-reservoir s)
                         (uniform-reservoir))]
     (RateLatency. quantiles rate-scale latency-scale rate reservoir))))
