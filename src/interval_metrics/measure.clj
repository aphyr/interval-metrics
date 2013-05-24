(ns interval-metrics.measure
  "Functions and macros for instrumenting your code."
  (:use interval-metrics.core))

(defn unix-time
  "Returns the current unix time, in seconds."
  []
  (/ (System/currentTimeMillis) 1000))

(defn periodically-
  "Spawns a thread which calls f every dt seconds. Returns a function which
  stops that thread."
  [dt f]
  (let [anchor (unix-time)
        running? (promise)]
    (-> (bound-fn looper []
          ; Sleep until the next tick, or when the shutdown is delivered as
          ; false.
          (while (deref running?
                        (* 1000 (- dt (mod (- (unix-time) anchor) dt)))
                        true)
            (try
              (f)
              (catch Throwable t))))
      (Thread. "interval-metrics periodic")
      (.start))
    #(deliver running? false)))

(defmacro periodically
  "Spawns a thread which executes body every dt seconds, after an initial dt
  delay. Returns a function which stops that thread."
  [dt & body]
  `(periodically- ~dt (bound-fn [] ~@body)))

(defmacro measure-latency
  "Wraps body in a macro which reports its running time in nanoseconds to a
  Metric."
  [metric & body]
  `(let [t0#    (System/nanoTime)
         value# (do ~@body)
         t1#    (System/nanoTime)]
     (update! ~metric (- t1# t0#))
     value#))
