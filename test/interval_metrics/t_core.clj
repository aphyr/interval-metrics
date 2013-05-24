(ns interval-metrics.t-core
  (:use midje.sweet
        interval-metrics.core
        criterium.core)
  (:import (interval_metrics.core Rate)
           (java.util.concurrent CountDownLatch
                                 TimeUnit)))

(defn fill! [reservoir coll]
  (->> coll
    (partition-all 10000)
    (map shuffle)
    (map (fn [chunk]
           (dorun (pmap #(update! reservoir %)
                       chunk))))
    dorun)
  reservoir)

(facts "reservoirs"
       (facts "a few numbers"
              (let [r (uniform-reservoir)]
                (fact (snapshot! r) => nil)

                (update! r 1)
                (fact (snapshot! r) => (just [1]))

                (update! r 1)
                (update! r -10)
                (update! r 23)
                (fact (snapshot! r) => (just [-10 1 23]))))

       (facts "linear naturals"
              (let [capacity 1000
                    n (* capacity 10)
                    r (uniform-reservoir capacity)
                    tolerance capacity]

                ; Uniformly distributed numbers
                (fill! r (range n))
                (let [snap (snapshot! r)]
                  ; Flood with zeroes
                  (dotimes [i capacity]
                    (update! r 0))

                  (fact (count snap) => capacity)
                  (fact (quantile snap 0)   => (roughly 0 tolerance))
                  (fact (quantile snap 0.5) => (roughly (/ n 2) tolerance)
                        (fact (quantile snap 1)   => (roughly n tolerance))))

                ; Check for those zeroes (to verify the snapshot isolated us)
                (let [snap (snapshot! r)]
                  (fact (quantile snap 0) => 0)
                  (fact (quantile snap 0.5) => 0)
                  (fact (quantile snap 1) => 0))

                ; Check to see that the most recent snapshot emptied things out
                (fact (snapshot! r) => nil))))

(defn nanos->seconds
  [nanos]
  (* 1e-9 nanos))

(defn expected-rate
  [total ^Rate r]
  (let [x (/ total (nanos->seconds
                    (- (System/nanoTime)
                       (.time r))))]
    (roughly x (/ x 20))))

(facts "rates"
       (let [r (rate)
             t0 (System/nanoTime)
             n 100000
             total (* 0.5 n (inc n))
             _ (fill! r (range n))
             ; Verify that dereferencing the rate in progress is correct
             _ (fact @r => (expected-rate total r))
             _ (Thread/sleep 10)
             _ (fact @r => (expected-rate total r))
             ; And take a snapshot
             snap (snapshot! r)
             t1 (System/nanoTime)]
         ; Since the snapshot completed, the rate should be zero.
         (fact @r => 0.0)

         ; But our snapshot should be the mean.
         (fact snap => (roughly (/ total (nanos->seconds (- t1 t0)))))

         ; Additional snapshots should be zero.
         (fact (snapshot! r) => 0.0)))

(facts "rate+latency"
       (let [n  100000
             r  (rate+latency {:rate-unit   :nanoseconds
                               :latency-unit :micros
                               :quantiles    [0 1/2 1]})
             t0 (System/nanoTime)
             _  (dotimes [i n] (update! r i))
             snap (snapshot! r)
             t1 (System/nanoTime)]
         (fact (:time snap) => pos?)
         (fact (:rate snap) => (roughly (/ n (- t1 t0))
                                        (/ (:rate snap) 5)))
         (let [ls (:latencies snap)]
           (fact (get ls 0)   => (roughly (/ 0 n 1000) 1))
           (fact (get ls 1/2) => (roughly (/ n 2 1000) (/ n 10000)))
           (fact (get ls 1)   => (roughly (/ n 1000)   1)))))

(defn stress [metric n]
  (let [threads 4
        per-worker (/ n threads)
        latch (CountDownLatch. threads)
        t0 (System/nanoTime)
        workers (map (fn [_] (future
                               (dotimes [i per-worker]
                                 (update! metric i))
                               (.countDown latch)))
                     (range threads))
        reader (future
                 (while (not (.await latch 1 TimeUnit/SECONDS))
;                   (prn (snapshot! metric))
                        ))]
    (dorun (map deref workers))
    @reader
    (let [t1 (System/nanoTime)
          dt (nanos->seconds (- t1 t0))
          rate (/ n dt)]
      (println "Completed" n "updates in" (format "%.3f" dt) "s: "
               (format "%.3f" rate) "updates/sec"))))

(facts "performance"
       (println "Benchmarking rate")
       (stress (rate) 1e9)

       (println "Benchmarking reservoir")
       (stress (uniform-reservoir) 1e9)
       
       (println "Benchmarking rate+latency")
       (stress (rate+latency) 1e9))
