# akka-coordination-redis

[Akka Coordination Lease](https://doc.akka.io/docs/akka/2.5.23/coordination.html) implementation using [Redis](https://redis.io/)
 and [Redlock algorithm](https://redis.io/topics/distlock). 

## Installation

Add `akka-coordination-redis` to sbt dependencies:

```
libraryDependencies ++= Seq(
  "com.github.nstojiljkovic" %% "akka-coordination-redis" % "0.0.4-SNAPSHOT"
)
```

## Compatibility

The library is currently compatible with Akka 2.5.22 and 2.5.23.

## Technical details 

[Redisson](https://github.com/redisson/redisson) is used as a Redis client library. However, 
[Redisson's RedLock implementation](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers) does
not map 1:1 to Akka Lease API. Notable changes and tweaks are listed bellow:

* [RedissonRedLock](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/RedissonRedLock.java) is
  Redis based distributed reentrant Lock object for Java and implements `java.util.concurrent.locks.Lock` interface. It 
  means that only lock owner thread can unlock it otherwise `IllegalMonitorStateException` would be thrown. 
  On the other hand, [Akka Coordination Lease API](https://doc.akka.io/docs/akka/2.5.23/coordination.html) has configurable owner (without 
  locking the leases to threads) so `akka-coordination-redis` implementation uses custom internal 
  [`RedissonLockWithCustomOwner`](https://github.com/nstojiljkovic/akka-coordination-redis/blob/master/src/main/scala/com/nikolastojiljkovic/akka/coordination/lease/RedissonLockWithCustomOwner.scala).
  Do not use it directly as it does not comply with the Java lock specification. That also means that you should, as per Akka Coordination
  specification, take special care when picking up a lease name that will be unique for your use case.
* [RedissonRedLock](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/RedissonRedLock.java) has bunch of
  functions which throw `UnsupportedOperationException` (and which would otherwise be useful for implementing Akka Coordination Lease). The `akka-coordination-redis` library has
  custom logic implemented in [`RedissonRedLockLease`](https://github.com/nstojiljkovic/akka-coordination-redis/blob/master/src/main/scala/com/nikolastojiljkovic/akka/coordination/lease/RedissonRedLockLease.scala)
  to overcome the missing functionality from [Redisson](https://github.com/redisson/redisson) RedLock implementation.
  * Expiry time is calculated from all acquired locks with auto-estimated drift (based on communication speed) deducted from it. 
    This means that expiry time will always be a bit less than `heartbeat-timeout` configuration option (so count it in when configuring).
  * It is assumed that every server configured for each of the (single) locks constituting a distributed RedLock will be actually a single 
    Redis server (though that is not a requirement). `RedissonRedLockLease` listens for disconnects from each of the configured servers and opts 
    to mark lease as lost on any server disconnect. So if you have anything besides `singleServerConfig` as a single lock server (for example Redis cluster), note that
    acquired leases will be marked as lost if any of the cluster servers goes down. This just means that you should not reconfigure/reprovision 
    both Akka cluster and Redis servers at the same time if you are using lease as an additional safety measure (to ensure that 
    two singletons don’t run at the same time or that shard does not run on two nodes).
  
## Warning

If you are not experienced with Akka you should probably think twice before using the distributed lease. It should be the solution only
if you cannot think of any other way to implement the required business logic using pure actors. Take a look at the
2 use cases already built-in into Akka: [Cluster Singletons](https://doc.akka.io/docs/akka/current/cluster-singleton.html#lease) 
(implemented in [`ClusterSingletonManagerSettings`](https://github.com/akka/akka/blob/master/akka-cluster-tools/src/main/scala/akka/cluster/singleton/ClusterSingletonManager.scala))
and [Cluster Sharding](https://doc.akka.io/docs/akka/current/cluster-sharding.html#lease) (implemented in 
[`Shard`](https://github.com/akka/akka/blob/master/akka-cluster-sharding/src/main/scala/akka/cluster/sharding/Shard.scala)).

## Configuration

Sample configuration using 5 Redis servers, with minimum amount of locks set to 4:

```
redisson-red-lock-lease {
  # reference to the lease implementation class 
  lease-class = "com.nikolastojiljkovic.akka.coordination.lease.RedissonRedLockLease"

  # if the node that acquired the leases crashes, how long should the lease be held before another owner can get it
  heartbeat-timeout = 30s

  # interval for communicating with the third party to confirm the lease is still held
  heartbeat-interval = 6s

  # interval to time out after acquire and release calls or document
  lease-operation-timeout = 1s

  # minimum number of (single) locks required, defaults to N / 2 + 1 if not defined
  min-locks-amount = 4
  
  # custom dispatcher to use, defaults to actor system default dispatcher if not specified
  dispatcher = custom-dispatcher
  
  # common configuration option, not used directly by RedissonRedLockLease
  default-server {
    singleServerConfig {
      address = "redis://10.10.11.40:6379"
      connectionMinimumIdleSize = 1
      connectionPoolSize = 1
    }
    threads = 2
    nettyThreads = 2
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

## Contributing

Please feel ree to create a new pull request for each change or bug fix. Make sure that all of the changes are covered using 
tests. You can run the full suite of tests using sbt:

```
> testOnly com.nikolastojiljkovic.akka.coordination.lease.*
```

## License

See the [LICENSE](https://github.com/nstojiljkovic/akka-coordination-redis/blob/master/LICENSE) file for details.