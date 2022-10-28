(defproject interval-metrics "1.0.1"
  :description "Time-windowed metric collection objects."
  :dependencies []
  :java-source-paths ["src/interval_metrics"]
  :javac-options ["-target" "11" "-source" "11"]
;  :warn-on-reflection true
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.1"]
                                  [criterium "0.4.6"]
                                  [midje "1.10.6"]]
                   :plugins [[lein-midje "3.2.1"]]}})
