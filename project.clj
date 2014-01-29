(defproject interval-metrics "1.0.1-SNAPSHOT"
  :description "Time-windowed metric collection objects."
  :dependencies []
  :java-source-paths ["src/interval_metrics"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
;  :warn-on-reflection true
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.5.1"]
                                  [criterium "0.4.1"]
                                  [midje "1.5.0"]]}})
