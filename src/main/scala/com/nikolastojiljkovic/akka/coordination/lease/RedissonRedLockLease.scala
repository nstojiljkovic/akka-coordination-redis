package com.nikolastojiljkovic.akka.coordination.lease

import java.util.{Timer, TimerTask}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ExtendedActorSystem
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import org.redisson.Redisson
import org.redisson.api.RLock
import org.redisson.config.{Config => RedissonConfig}
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import scala.compat.java8.FutureConverters
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

class RedissonRedLockLease(override val settings: LeaseSettings, val actorSystem: ExtendedActorSystem) extends Lease(settings) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val clientConfigs: Seq[RedissonConfig] = settings.leaseConfig.getObjectList("servers")
    .asScala.map(identity)(collection.breakOut)
    .flatMap(config => {
      try {
        val fallbackConfig = ConfigFactory.parseString("lockWatchdogTimeout = " + settings.timeoutSettings.getHeartbeatInterval.toMillis)
        val cfg = org.redisson.config.Config.fromJSON(config.withFallback(fallbackConfig).render(ConfigRenderOptions.concise))
        Some(cfg)
      } catch {
        case t: Throwable =>
          logger.error("Failed to parse RedissonRedLockLease config", t)
          None
      }
    })
  private val minLocksAmount = {
    val fallbackConfig: Config = ConfigFactory.parseString("min-locks-amount = " + (clientConfigs.size / 2 + 1))
    settings.leaseConfig.withFallback(fallbackConfig).getInt("min-locks-amount")
  }
  private val timer = new Timer
  implicit private val ec: ExecutionContext = if (settings.leaseConfig.hasPath("dispatcher")) {
    actorSystem.dispatchers.lookup(settings.leaseConfig.getString("dispatcher"))
  } else {
    actorSystem.dispatcher
  }

  private val lockedCount = new AtomicInteger(0)

  private var lock: RedissonRedLockWithCustomMinLocks = _
  private var clients = Seq.empty[Redisson]
  private var locks = Seq.empty[(RLock, Redisson)]
  private var leaseLostCallbacks = Seq.empty[Option[Throwable] => Unit]
  private var expireTask: TimerTask = _

  override def acquire: Future[Boolean] = { // acquire should complete with:
    // * true if the lease has been acquired,
    // * false if the lease is taken by another owner, or
    // * fail if it can’t communicate with the third party system implementing the lease.
    acquire(_ => ())
  }

  private def onLeaseLost(throwable: Option[Throwable]): Unit = {
    logger.debug("Lease lost, number of callbacks: " + leaseLostCallbacks.size)
    lockedCount.getAcquire
    leaseLostCallbacks.foreach(fn => {
      fn.apply(throwable)
    })
    resetOnUnlock()
    lockedCount.setRelease(0)
  }

  private def resetOnUnlock(): Unit = {
    locks.foreach(lock => {
      RedissonManager.removeLockReference(actorSystem, lock._2, lock._1)
    })
    lock = null
    clients = Seq.empty
    locks = Seq.empty
    leaseLostCallbacks = Seq.empty
    if (expireTask != null) {
      expireTask.cancel
      expireTask = null
      timer.purge
    }
  }

  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    // The acquire lease lost callback should only be called after an acquire CompletionStage has completed and
    // should be called if the lease is lose e.g. due to losing communication with the third party system.
    if (lock == null) {
      if (leaseLostCallback != null) {
        leaseLostCallbacks = leaseLostCallbacks :+ leaseLostCallback
      }
      clients = clientConfigs.flatMap(config =>
        try
          Some(RedissonManager.getClient(config, actorSystem))
        catch {
          case t: Throwable =>
            logger.error("Failed to acquire (single) Redis lock.", t)
            None
        }
      )
      if (minLocksAmount > clients.size)
        return Future.failed(new RuntimeException("Could not connect to minimum of " + minLocksAmount + " Redis server instances. Failing without even trying to lock."))

      locks = this.clients.map(client => {
        val lock = new RedissonLockWithCustomOwner(client.getConnectionManager.getCommandExecutor, settings.leaseName, settings.ownerName)
        RedissonManager.addLockReference(actorSystem, client, lock, Thread.currentThread.getId)
        RedissonManager.listenOnClientShutdown(client, this.onLeaseLost, this)
        (lock, client)
      })

      lock = new RedissonRedLockWithCustomMinLocks(minLocksAmount, locks.map(_._1): _*)
    }

    FutureConverters.toScala(
      lock
        .tryLockAsync(
          settings.timeoutSettings.getOperationTimeout.toMillis,
          settings.timeoutSettings.getHeartbeatTimeout.toMillis,
          TimeUnit.MILLISECONDS
        ).toCompletableFuture
    ).map(result => {
      if (result) {
        lockedCount.incrementAndGet
        if (expireTask != null) {
          expireTask.cancel
          expireTask = null
          timer.purge
        }
        var remainTimeToLive = -1L
        var ttlOperationDuration = 0L
        try {
          val startTtl = System.currentTimeMillis
          val ttlFutures = locks.map(lock => FutureConverters.toScala(lock._1.remainTimeToLiveAsync().toCompletableFuture))
          val ttls = Await.result(Future.sequence(ttlFutures), Duration.Inf)
          remainTimeToLive = ttls.foldLeft(-3L)((s, ttl) => {
            logger.debug("Single lock expiry: " + ttl)
            if (s < 0)
              ttl
            else if (ttl < 0)
              s
            else
              Math.min(s, ttl)
          })
          ttlOperationDuration = System.currentTimeMillis - startTtl
        } catch {
          case t: Throwable =>
            logger.error("Error occurred while processing remainTimeToLive", t)
        }
        // this is a safe bet for the timeout, a bit over-constrained
        // better safe than sorry
        val expiryDelay = if (remainTimeToLive < 0)
          settings.timeoutSettings.getHeartbeatTimeout.toMillis - settings.timeoutSettings.getOperationTimeout.toMillis
        else
          remainTimeToLive - ttlOperationDuration
        logger.debug("Evaluated remain time to live " + remainTimeToLive + ", expiry delay " + expiryDelay + ", heartbeat timeout " + settings.timeoutSettings.getHeartbeatTimeout.toMillis + ", operation timeout " + settings.timeoutSettings.getOperationTimeout.toMillis)

        expireTask = new TimerTask() {
          override def run(): Unit = {
            onLeaseLost(None)
          }
        }
        timer.schedule(expireTask, expiryDelay)
      }
      result
    })
  }

  override def release: Future[Boolean] = {
    // release should complete with:
    // * true if the lease has definitely been released,
    // * false if the lease has definitely not been released, or
    // * fail if it is unknown if the lease has been released.

    if (lock != null) {
      FutureConverters.toScala(lock.unlockAsync.toCompletableFuture).map(r => {
        val newCnt = lockedCount.getAcquire - 1
        val unlocked = newCnt <= 0
        if (unlocked) resetOnUnlock()
        lockedCount.setRelease(newCnt)
        unlocked
      })
    } else {
      Future.successful(false)
    }
  }

  override def checkLease: Boolean = {
    // checkLease should return
    // * false until an acquire CompletionStage has completed and should return
    // * false if the lease is lost due to an error communicating with the third party.
    // Check lease should also not block.

    if (lockedCount.get <= 0) {
      false
    } else {
      // RedissonRedLockWithCustomMinLocks does not support isLocked. However, every single RLock does. In theory, we could
      // implement check on all locks and see if we got N/2+1 locks. Unfortunately, this is expensive operation and
      // furthermore, it does not comply with interface specification - this check should be fast and synchronous!

      // locks.foldLeft(0)((i, lock) => if (lock._1.isLocked && lock._1.isHeldByCurrentThread) i + 1 else i) >= minLocksAmount

      // We just check if some of the clients we got lock on is no longer active
      clients.foldLeft(true)((s, client) => s && !client.isShuttingDown && !client.isShutdown)
    }
  }
}
