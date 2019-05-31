# akka-coordination-redis

[![Build Status](https://travis-ci.org/nstojiljkovic/akka-coordination-redis.svg?branch=master)](https://travis-ci.org/nstojiljkovic/akka-coordination-redis)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/45cbfc3238074124b2097e24d921efda)](https://www.codacy.com/app/nstojiljkovic/akka-coordination-redis?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=nstojiljkovic/akka-coordination-redis&amp;utm_campaign=Badge_Grade)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.nstojiljkovic/akka-coordination-redis_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.nstojiljkovic/akka-coordination-redis_2.12)

[Akka Coordination Lease](https://doc.akka.io/docs/akka/2.5.23/coordination.html) implementation using [Redis](https://redis.io/)
 and [Redlock algorithm](https://redis.io/topics/distlock).

## Compatibility

The library is currently compatible with Akka 2.5.22 and 2.5.23. It is tested with 2 use cases built-in into Akka:

*   [Cluster Singletons](https://doc.akka.io/docs/akka/current/cluster-singleton.html#lease) (implemented in
    [`ClusterSingletonManagerSettings`](https://github.com/akka/akka/blob/master/akka-cluster-tools/src/main/scala/akka/cluster/singleton/ClusterSingletonManager.scala))

*   [Cluster Sharding](https://doc.akka.io/docs/akka/current/cluster-sharding.html#lease) (implemented in
    [`Shard`](https://github.com/akka/akka/blob/master/akka-cluster-sharding/src/main/scala/akka/cluster/sharding/Shard.scala)).

## Installation

Add `akka-coordination-redis` to sbt dependencies:

```scala
libraryDependencies ++= Seq(
  "com.github.nstojiljkovic" %% "akka-coordination-redis" % "0.1.2"
)
```

## Configuration

Sample configuration using 5 Redis servers, with minimum amount of locks set to 4:

```hocon
redisson-red-lock-lease {
  # reference to the lease implementation class
  lease-class = "com.nikolastojiljkovic.akka.coordination.lease.RedissonRedLockLease"

  # if the node that acquired the leases crashes, how long should the lease be held before another owner can get it
  heartbeat-timeout = 120s

  # interval for communicating with the third party to confirm the lease is still held
  heartbeat-interval = 12s

  # interval to time out after acquire and release calls or document
  lease-operation-timeout = 5s

  # minimum number of (single) locks required, defaults to N / 2 + 1 if not defined
  min-locks-amount = 4

  # custom dispatcher to use, defaults to actor system default dispatcher if not specified
  dispatcher = custom-dispatcher

  # common configuration option, not used directly by RedissonRedLockLease
  default-server {
    singleServerConfig {
      address = "redis://10.10.11.40:6379"
      connectionMinimumIdleSize = 8
      connectionPoolSize = 16
    }
    threads = 64
    nettyThreads = 64
    transportMode = NIO
    transportMode = NIO
  }

  # list of servers
  servers = [
    ${redisson-red-lock-lease.default-server} {singleServerConfig.address = "redis://10.10.11.40:6379"},
    ${redisson-red-lock-lease.default-server} {singleServerConfig.address = "redis://10.10.11.41:6379"},
    ${redisson-red-lock-lease.default-server} {singleServerConfig.address = "redis://10.10.11.42:6379"},
    ${redisson-red-lock-lease.default-server} {singleServerConfig.address = "redis://10.10.11.43:6379"},
    ${redisson-red-lock-lease.default-server} {singleServerConfig.address = "redis://10.10.11.44:6379"}
  ]
}
```

If `min-locks-amount` is not configured it will be calculated as `N / 2 + 1` where `N` is the number of configured Redis servers.
The `default-server` from the above sample is just used to copy the common configuration to each of the configured `servers`.
Each of the servers in the `servers` array accepts any of the configuration options as per
[JSON/YAML based Redisson configuration](https://github.com/redisson/redisson/wiki/2.-Configuration). You should also probably take special
care when configuring the Redisson thread and connection pools.

There's also an option to specify custom execution context used by `RedissonRedLockLease` (which is completely separate
from the Redisson thread pool) using the `dispatcher` configuration option. It should point to the name of a custom Akka
dispatcher, configured as per [Akka official documentation](https://doc.akka.io/docs/akka/2.5.23/dispatchers.html).

The `lease-class`, `heartbeat-timeout`, `heartbeat-interval` and `lease-operation-timeout` configuration options all come from
[Akka Coordination Lease API](https://doc.akka.io/docs/akka/2.5.23/coordination.html).

## Technical details

[Redisson](https://github.com/redisson/redisson) is used as a Redis client library. However,
[Redisson's RedLock implementation](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers) does
not map 1:1 to Akka Lease API. Notable changes and tweaks are listed bellow:

*   [RedissonRedLock](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/RedissonRedLock.java) is
    Redis based distributed reentrant Lock object for Java and implements `java.util.concurrent.locks.Lock` interface. It
    means that only lock owner thread can unlock it otherwise `IllegalMonitorStateException` would be thrown.
    On the other hand, [Akka Coordination Lease API](https://doc.akka.io/docs/akka/2.5.23/coordination.html) has configurable owner (without
    locking the leases to threads) so `akka-coordination-redis` implementation uses custom internal
    [`RedissonLockWithCustomOwnerAndFixedLeaseTime`](https://github.com/nstojiljkovic/akka-coordination-redis/blob/master/src/main/scala/com/nikolastojiljkovic/akka/coordination/lease/RedissonLockWithCustomOwnerAndFixedLeaseTime.scala).
    It does not comply with the Java lock specification and is therefore marked private. That also means that you should, as per Akka Coordination
    specification, take special care when picking up a lease name that will be unique for your use case.

*   [RedissonRedLock](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/RedissonRedLock.java) has bunch of
    functions which throw `UnsupportedOperationException` (and which would otherwise be useful for implementing Akka Coordination Lease). The
    `akka-coordination-redis` library has custom logic implemented in [`RedissonRedLockLease`](https://github.com/nstojiljkovic/akka-coordination-redis/blob/master/src/main/scala/com/nikolastojiljkovic/akka/coordination/lease/RedissonRedLockLease.scala)
    to overcome the missing functionality from [Redisson](https://github.com/redisson/redisson) RedLock implementation.

    *   Akka allows to specify both `heartbeat-timeout` and `heartbeat-interval`. Redisson allows to specify `lockWatchdogTimeout` (which maps to
        Akka's `heartbeat-timeout` for locks with `leastTime` set to -1), but the `heartbeat-interval` is then locked to 1/3 of `lockWatchdogTimeout`
        (hardcoded in a private method in `RedissonLock`). In order to fully allow Akka's `heartbeat-interval` setting, `RedissonRedLockLease` does not
        use Redisson's watchdog, but instead renews the lease itself.

    *   It is assumed that every server configured for each of the (single) locks constituting a distributed RedLock will be actually a single
        Redis server (though that is not a requirement). `RedissonRedLockLease` listens for disconnects from each of the configured servers and
        opts to mark lease as lost on any server disconnect. So if you have anything besides `singleServerConfig` as a single lock server (for
        example Redis cluster), note that acquired leases will be marked as lost if any of the cluster servers goes down.

## Contributing

Please feel ree to create a new pull request for each change or bug fix. Make sure that all of the changes are covered using
tests. You can run the full suite of tests using sbt:

```sbtshell
> testOnly com.nikolastojiljkovic.akka.coordination.lease.*
```

## License

See the [LICENSE](https://github.com/nstojiljkovic/akka-coordination-redis/blob/master/LICENSE) file for details.
