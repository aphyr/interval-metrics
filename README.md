# interval-metrics

Data structures for measuring performance. Provides lockless, high-performance
mutable state, wrapped in idiomatic Clojure identities, without any external
dependencies.

Codahale's metrics library is designed for slowly-evolving metrics with stable
dynamics, where multiple readers may request the current value of a metric at
any time. Sometimes, you have a *single* reader which collects metrics over a
specific time window--and you want the value of the metric at the end of the
window to reflect observations from that window only, rather than including
observations from prior windows.

## Clojars

https://clojars.org/interval-metrics

## Rates

The `Metric` protocol defines an identity which wraps some mutable
measurements. Think of it like an atom or ref, only instead of (swap!) or
(alter), it accepts new measurements to merge into its state. All values in
this implementation are longs. Let's keep track of a *rate* of events per
second:

``` clj
user=> (use 'interval-metrics.core)
nil
user=> (def r (rate))
#'user/r
```

All operations are thread-safe, naturally. Let's tell the rate that we handled
2 events, then 5 more events, than... I dunno, negative 200 events.

``` clj
user=> (update! r 2)
#<Rate@df69935: 0.5458097134611932>
user=> (update! r 5)
#<Rate@df69935: 1.0991987655505422>
user=> (update! r -200)
#<Rate@df69935: -22.796646374437813>
```

Metrics implement IDeref, so you can always ask for their current value without changing anything. Rate's value is the sum of all updates, divided by the time since the last snapshot:

``` clj
user=> (deref r)
-21.14793138384688
```

The `Snapshot` protocol defines an operation which gets the current value of an
identity *and atomically resets the value*. For instance, you might call
(snapshot! some-rate) every second, and log the resulting value or send it to
Graphite:

``` clj
user=> (snapshot! r)
-14.292050358924806
user=> r
#<Rate@df69935: 0.0>
```

Note that the rate became zero when we took a snapshot. It'll start
accumulating new state afresh.

## Reservoirs

Sometimes you want a probabilistic sample of numbers. `uniform-reservoir` creates a pool of longs which represents a uniformly distributed sample of the updates. Let's create a reservoir which holds three numbers:

``` clj
user=> (def r (uniform-reservoir 3))
#'user/r
```

And update it with some values:

``` clj
user=> (update! r 2)
#<UniformReservoir@49f17507: (2)>
user=> (update! r 1)
#<UniformReservoir@49f17507: (1 2)>
user=> (update! r 3)
#<UniformReservoir@49f17507: (1 2 3)>
```

The *value* of a reservoir is a sorted list of the numbers in its current
sample. What happens when we add more than three elements?

``` clj
#<UniformReservoir@399d2d53: (1 2 3)>
user=> (update! r 4)
#<UniformReservoir@399d2d53: (1 3 4)>
user=> (update! r 5)
#<UniformReservoir@399d2d53: (1 3 4)>
```

Sampling is *probabilistic*: 4 made it in, but 5 didn't. Updates are always
constant time.

``` clj
user=> (update! r -2)
#<UniformReservoir@399d2d53: (-2 1 3)>
user=> (update! r -3)
#<UniformReservoir@399d2d53: (-2 1 3)>
user=> (update! r -4)
#<UniformReservoir@399d2d53: (-4 -2 3)>
```

Snapshots (like deref) return the sorted list in O(n log n) time. Note that
when we take a snapshot, the reservoir becomes empty again (nil). 

``` clj
user=> (snapshot! r)
(-4 -2 3)
user=> r
#<AtomicMetric@3018fc1a: nil>
user=> (update! r 42)
#<UniformReservoir@593c7b26: (42)>
```

There are also functions to extract specific values from sorted sequences. To
get the 95th percentile value:

```clj
#'user/r
user=> (dotimes [_ 10000] (update! r (rand 1000)))
nil
user=> (quantile @r 0.95)
965
```

Typically, you'd run have a thread periodically take snapshots of your metrics and report some derived values. Here, we extract the median, 95th, 99th, and maximum percentiles seen since the last snapshot:

```clj
user=> (map (partial quantile (snapshot! r)) [0.5 0.95 0.99 1])
(496 945 983 999)
```

## Measuring your code's performance

The `interval-metrics.measure` namespace has some helpers for measuring common
things about your code.

```clj
(use ['interval-metrics.measure :only '[periodically measure-latency]]
     ['interval-metrics.core    :only '[snapshot! rate+latency]])
```

Define a hybrid metric which tracks both rates and latency distributions.

```clj
(def latencies (rate+latency))
```

Start a thread to snapshot the latencies every 5 seconds.

```clj
(def poller
  (periodically 5 
    (clojure.pprint/pprint (snapshot! latencies))))
```

The measure-latency macro times how long its body takes to execute, and updates
the latencies metric each time.

```clj
(while true
  (measure-latency latencies
    (into [] (range 10))))
```

You'll see a map like this printed every 5 seconds, showing the rate of calls
per second, and the latency distribution, in milliseconds.

``` clj
{:time 1369438387321/1000,
 :rate 316831.2493798337,
 :latencies
 {0.0 2397/1000000,
  0.5 2463/1000000,
  0.95 2641/1000000,
  0.99 2371/500000,
  0.999 9597/1000000}}
```

Kill the loop with ^C, then shut down the poller thread by calling `(poller)`.

You can configure the quantiles, reservoir size, and units for both the rate
and the latencies by passing an options map to `rate+latency`:

```clj
(def latencies (rate+latency {:latency-unit :microseconds
                              :rate-unit    :weeks}))
```

## Performance

All algorithms are lockless. I sacrifice some correctness for performance, but
never drop writes. Synchronization drift should be negligible compared to
typical (~1s) sampling intervals. Rates on a highly contended JVM are
accurate, in experiments, to at least three sigfigs.

With four threads updating, and one thread taking a snapshot every n seconds,
my laptop can push roughly 15 million updates per second to a single rate
object, saturating 99% of four cores.

The same workload applied to a default-sized uniform-reservoir can push ~8.5
million updates per second, again with 99-100% CPU use.

## License

Licensed under the Eclipse Public License.

## How to run the tests

`lein midje` will run all tests.

`lein midje namespace.*` will run only tests beginning with "namespace.".

`lein midje :autotest` will run all the tests indefinitely. It sets up a
watcher on the code files. If they change, only the relevant tests will be
run again.
