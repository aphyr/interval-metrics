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
