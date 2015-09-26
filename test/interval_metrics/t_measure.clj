(ns interval-metrics.t-measure
  (:use midje.sweet
        interval-metrics.core
        criterium.core)
  (:import (interval_metrics.core Rate)
           (java.util.concurrent CountDownLatch
                                 TimeUnit)))
