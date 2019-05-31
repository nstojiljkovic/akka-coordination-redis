// Copyright (C) 2019 Nikola Stojiljković.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.nikolastojiljkovic.akka.coordination.lease

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import org.redisson.Redisson
import org.redisson.config.{Config => RedissonConfig}
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import LogHelper._
import RedissonRedLockLease._

import scala.compat.java8.FutureConverters
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success}

object RedissonRedLockLease {

  // states
  sealed trait State

  case object Idle extends State

  case object Busy extends State

  case object Locked extends State

  final case class LockConfig
  (
    lockedCountChangeCallback: Int => Unit,
    leaseSettings: LeaseSettings,
    clientConfigs: Seq[RedissonConfig],
    minLocksAmount: Int
  )

  sealed trait Data

  final case class StateDataWithoutLock
  (
    config: LockConfig
  ) extends Data

  final case class LockAndClient(lock: RedissonLockWithCustomOwnerAndFixedLeaseTime, client: Redisson)

  final case class StateDataWithLock
  (
    pipeTo: Option[ActorRef],
    config: LockConfig,
    lockedCount: Int,
    redLock: RedissonRedLockWithCustomMinLocks,
    clients: Seq[Redisson],
    locks: Seq[LockAndClient],
    leaseLostCallbacks: Seq[Option[Throwable] => Unit],
    renewTask: Option[Cancellable] = None
  ) extends Data {
    def looseLease(message: Any, parent: AnyRef)(implicit actorSystem: ActorSystem): Data = {
      config.lockedCountChangeCallback(0)
      pipeTo.foreach(_ ! message)
      renewTask match {
        case Some(task) =>
          task.cancel
        case _ =>
      }
      locks.foreach(lock => {
        RedissonManager.removeListenerOnClientShutdown(lock.client, parent)
        RedissonManager.removeLockReference(actorSystem, lock.client, lock.lock)
      })
      StateDataWithoutLock(config)
    }
  }

  // received events
  final case class Lock(leaseLostCallback: Option[Option[Throwable] => Unit])

  final case class LockResult(locked: Boolean, startTime: Long)

  final case class LockFailed(throwable: Throwable)

  final case class LeaseLost(throwable: Option[Throwable])

  final case object Renew

  final case class RenewResult(locked: Boolean, startTime: Long)

  final case class RenewFailed(throwable: Throwable)

  final case object Release

  final case class ReleaseResult(unlocked: Boolean)

  final case class ReleaseFailed(throwable: Throwable)

  class ActorFSM(lockedCountChangeCallback: Int => Unit, leaseSettings: LeaseSettings) extends FSM[State, Data] with Stash {

    import context.dispatcher

    implicit val logger: LoggingAdapter = log
    implicit val actorSystem: ActorSystem = context.system

    startWith(Idle, {
      val clientConfigs: Seq[RedissonConfig] = leaseSettings.leaseConfig.getObjectList("servers")
        .asScala.map(identity)(collection.breakOut)
        .flatMap(config => {
          logTry("Failed to parse RedissonRedLockLease config") {
            val fallbackConfig = ConfigFactory.parseString("lockWatchdogTimeout = " + leaseSettings.timeoutSettings.heartbeatTimeout.toMillis)
            org.redisson.config.Config.fromJSON(config.withFallback(fallbackConfig).render(ConfigRenderOptions.concise))
          }.toOption
        })
      val minLocksAmount = {
        val fallbackConfig: Config = ConfigFactory.parseString("min-locks-amount = " + (clientConfigs.size / 2 + 1))
        leaseSettings.leaseConfig.withFallback(fallbackConfig).getInt("min-locks-amount")
      }

      StateDataWithoutLock(LockConfig(lockedCountChangeCallback, leaseSettings, clientConfigs, minLocksAmount))
    })


    when(Idle) {
      case Event(Lock(leaseLostCallback), s: StateDataWithoutLock) =>
        val origSender = sender()
        val startTime = System.currentTimeMillis()
        val clients: Seq[Redisson] = s.config.clientConfigs.flatMap(config =>
          logTry("Failed to acquire (single) Redis lock.") {
            RedissonManager.getClient(config, context.system)
          }.toOption
        )
        if (s.config.minLocksAmount > clients.size) {
          origSender ! LockFailed(
            new RuntimeException("Could not connect to minimum of " + s.config.minLocksAmount + " Redis servers. Failing without even trying to lock."))
          stay()
        } else {
          val locks = clients.map(client => {
            val lock = new RedissonLockWithCustomOwnerAndFixedLeaseTime(
              client.getConnectionManager.getCommandExecutor,
              leaseSettings.timeoutSettings.heartbeatTimeout.toMillis,
              leaseSettings.leaseName,
              leaseSettings.ownerName)
            RedissonManager.addLockReference(context.system, client, lock, Thread.currentThread.getId)
            RedissonManager.addListenerOnClientShutdown(client, t => self ! LeaseLost(t), this)
            LockAndClient(lock, client)
          })
          val redLock = new RedissonRedLockWithCustomMinLocks(s.config.minLocksAmount, locks.map(_.lock): _*)
          FutureConverters.toScala(
            redLock.tryLockAsync(
              leaseSettings.timeoutSettings.operationTimeout.toMillis,
              -1,
              TimeUnit.MILLISECONDS
            ).toCompletableFuture
          ).andThen {
            case Success(r) => self ! LockResult(r, startTime)
            case Failure(t) => self ! LockFailed(t)
          }
          goto(Busy).using(StateDataWithLock(
            pipeTo = Some(origSender),
            config = s.config,
            lockedCount = 0,
            redLock = redLock,
            clients = clients,
            locks = locks,
            leaseLostCallbacks = leaseLostCallback.toSeq,
            renewTask = None
          ))
        }

      case Event(Release, _) =>
        sender() ! ReleaseResult(true)
        stay()

    }

    when(Locked) {
      case Event(Renew, s: StateDataWithLock) =>
        log.debug("Trying to renew lease " + leaseSettings.leaseName)
        val origSender = sender()
        val startTime = System.currentTimeMillis()
        Future.sequence(s.locks.map(l => l.lock.renewAsync)).andThen {
          case Success(r) => self ! RenewResult(r.forall(identity), startTime)
          case Failure(t) => self ! RenewFailed(t)
        }
        goto(Busy)

      case Event(Lock(leaseLostCallback), s: StateDataWithLock) =>
        val origSender = sender()
        val startTime = System.currentTimeMillis()
        FutureConverters.toScala(
          s.redLock.tryLockAsync(
            leaseSettings.timeoutSettings.operationTimeout.toMillis,
            -1,
            TimeUnit.MILLISECONDS
          ).toCompletableFuture
        ).andThen {
          case Success(r) => self ! LockResult(r, startTime)
          case Failure(t) => self ! LockFailed(t)
        }
        goto(Busy).using(s.copy(
          pipeTo = Some(origSender),
          leaseLostCallbacks = s.leaseLostCallbacks ++ leaseLostCallback.toSeq
        ))

      case Event(Release, s: StateDataWithLock) =>
        val origSender = sender()
        FutureConverters.toScala(
          s.redLock.unlockAsync.toCompletableFuture
        ).andThen {
          case Success(_) => self ! ReleaseResult(true)
          case Failure(t) => self ! ReleaseFailed(t)
        }
        goto(Busy).using(s.copy(
          pipeTo = Some(origSender)
        ))

      case Event(LeaseLost(throwable), s: StateDataWithLock) =>
        log.debug("Lease lost, number of callbacks: " + s.leaseLostCallbacks.size)
        s.config.lockedCountChangeCallback(0)
        s.leaseLostCallbacks.foreach(fn => {
          fn.apply(throwable)
        })
        goto(Idle).using(s.looseLease(ReleaseResult(true), this))

    }

    when(Busy) {
      case Event(Lock(_), _) =>
        stash()
        stay()

      case Event(Release, _) =>
        stash()
        stay()

      case Event(RenewResult(success, startTime), s: StateDataWithLock) if success =>
        log.debug("Successfully renewed lease " + leaseSettings.leaseName + " in " + (System.currentTimeMillis() - startTime) + "ms")
        s.renewTask match {
          case Some(task) =>
            task.cancel
          case _ =>
        }
        log.debug("Scheduling renew of lease " + leaseSettings.leaseName + " in " + s.config.leaseSettings.timeoutSettings.heartbeatInterval)
        val renewTask = context.system.scheduler.scheduleOnce(
          s.config.leaseSettings.timeoutSettings.heartbeatInterval,
          self,
          Renew
        )
        goto(Locked).using(s.copy(renewTask = Some(renewTask)))

      case Event(RenewResult(success, startTime), s: StateDataWithLock) if !success =>
        log.warning("Failed renewal of lease " + leaseSettings.leaseName + " in " + (System.currentTimeMillis() - startTime) + "ms")
        // @todo: maybe we could try to clean up the locks
        self ! LockFailed(new RuntimeException("Could not renew lock(s)."))
        stay()

      case Event(RenewFailed(t), _) =>
        log.error(t, "Failed renewal of lease " + leaseSettings.leaseName)
        // @todo: maybe we could try to clean up the locks
        self ! LockFailed(t)
        stay()

      case Event(ReleaseResult(unlocked), s: StateDataWithLock) if unlocked =>
        if (s.lockedCount == 1) {
          goto(Idle).using(s.looseLease(ReleaseResult(true), this))
        } else {
          s.config.lockedCountChangeCallback(s.lockedCount - 1)
          s.pipeTo.foreach(_ ! ReleaseResult(false))
          goto(Locked).using(s.copy(
            pipeTo = None,
            lockedCount = s.lockedCount - 1
          ))
        }

      case Event(ReleaseResult(unlocked), s: StateDataWithLock) if !unlocked =>
        s.pipeTo.foreach(_ ! ReleaseResult(false))
        goto(Locked).using(s.copy(pipeTo = None))

      case Event(ReleaseFailed(t), s: StateDataWithLock) =>
        s.pipeTo.foreach(_ ! ReleaseFailed(t))
        goto(Locked).using(s.copy(pipeTo = None))

      case Event(LockResult(locked, startTime), s: StateDataWithLock) if locked =>
        s.config.lockedCountChangeCallback(s.lockedCount + 1)

        s.renewTask match {
          case Some(task) =>
            task.cancel
          case _ =>
        }
        log.debug("Scheduling renew of lease " + leaseSettings.leaseName + " in " + s.config.leaseSettings.timeoutSettings.heartbeatInterval)

        val renewTask = context.system.scheduler.scheduleOnce(
          s.config.leaseSettings.timeoutSettings.heartbeatInterval,
          self,
          Renew
        )

        s.pipeTo.foreach(_ ! LockResult(locked, startTime))

        goto(Locked).using(s.copy(
          renewTask = Some(renewTask),
          lockedCount = s.lockedCount + 1,
          pipeTo = None
        ))

      case Event(LockResult(locked, startTime), s: StateDataWithLock) if !locked =>
        goto(Idle).using(s.looseLease(LockResult(locked, startTime), this))

      case Event(LockFailed(t), s: StateDataWithLock) =>
        goto(Idle).using(s.looseLease(LockFailed(t), this))

    }

    // When transitioning into another state, unstash all messages.
    onTransition {
      case Busy -> _ =>
        unstashAll()
    }

    whenUnhandled {
      case Event(Lock(_), _) =>
        sender() ! LockResult(locked = false, System.currentTimeMillis())
        stay()

      case Event(Release, _) =>
        sender() ! ReleaseResult(false)
        stay()

    }

    initialize()
  }

}

class RedissonRedLockLease(override val settings: LeaseSettings, val actorSystem: ExtendedActorSystem) extends Lease(settings) {

  private val lockCount = new AtomicInteger(0)
  private val customDispatcherName = if (settings.leaseConfig.hasPath("dispatcher")) {
    Some(settings.leaseConfig.getString("dispatcher"))
  } else {
    None
  }
  implicit private val ec: ExecutionContext = customDispatcherName match {
    case Some(d) => actorSystem.dispatchers.lookup(d)
    case _ => actorSystem.dispatcher
  }
  private val props = customDispatcherName match {
    case Some(d) => Props(classOf[ActorFSM], (cnt: Int) => lockCount.set(cnt), settings).withDispatcher(d)
    case _ => Props(classOf[ActorFSM], (cnt: Int) => lockCount.set(cnt), settings)
  }
  private val actor = actorSystem.actorOf(
    props,
    settings.leaseName.replaceAll("[^a-zA-Z0-9-_.*$+:@&=,!~';.]", "_") + "-" + UUID.randomUUID().toString
  )

  private val logger = LoggerFactory.getLogger(getClass)

  // there's some small drift in calculating operation timeout using RedLock,
  // so just double it to avoid unnecessary AskTimeoutExceptions
  // this is especially important for failed acquire, they will return false
  // after operationTimeout and some small drift
  implicit val operationTimeout: Timeout = 2 * settings.timeoutSettings.operationTimeout

  override def acquire: Future[Boolean] = {
    // acquire should complete with:
    // * true if the lease has been acquired,
    // * false if the lease is taken by another owner, or
    // * fail if it can’t communicate with the third party system implementing the lease.

    acquireImpl(None)
  }

  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    // The acquire lease lost callback should only be called after an acquire CompletionStage has completed and
    // should be called if the lease is lose e.g. due to losing communication with the third party system.

    acquireImpl(Some(leaseLostCallback))
  }

  private def acquireImpl(leaseLostCallback: Option[Option[Throwable] => Unit]): Future[Boolean] = {
    val start = System.currentTimeMillis()
    (actor ? Lock(leaseLostCallback)).recover({
      case e => LockFailed(e)
    }).map {
      case LockResult(locked, _) =>
        logger.debug("Acquire duration (" + locked + "): " + (System.currentTimeMillis() - start))
        locked
      case LockFailed(throwable) =>
        logger.error("Failed acquire duration: " + (System.currentTimeMillis() - start))
        throw throwable
    }
  }

  override def release: Future[Boolean] = {
    // release should complete with:
    // * true if the lease has definitely been released,
    // * false if the lease has definitely not been released, or
    // * fail if it is unknown if the lease has been released.

    val start = System.currentTimeMillis()
    (actor ? Release).recover({
      case e => ReleaseFailed(e)
    }).map {
      case ReleaseResult(unlocked) =>
        logger.debug("Release duration (" + unlocked + "): " + (System.currentTimeMillis() - start))
        unlocked
      case ReleaseFailed(throwable) =>
        logger.error("Failed release duration: " + (System.currentTimeMillis() - start))
        throw throwable
    }
  }

  override def checkLease: Boolean = {
    // checkLease should return
    // * false until an acquire CompletionStage has completed and should return
    // * false if the lease is lost due to an error communicating with the third party.
    // Check lease should also not block.

    lockCount.get() > 0
  }
}
