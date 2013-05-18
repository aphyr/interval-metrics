(defproject windowed-metrics "0.0.1-SNAPSHOT"
  :description "Time-windowed metric collection objects."
  :dependencies [[org.clojure/clojure "1.5.0"]]
  :java-source-paths ["src/windowed_metrics"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
;  :warn-on-reflection true
  :profiles {:dev {:dependencies [[criterium "0.4.1"]
                                  [midje "1.5.0"]]}})  
