(defproject interval-metrics "1.0.1-SNAPSHOT"
  :description "Time-windowed metric collection objects."
  :dependencies [[org.clojure/clojure "1.7.0"]]
  :java-source-paths ["src/interval_metrics"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [midje "1.7.0"]]}})
