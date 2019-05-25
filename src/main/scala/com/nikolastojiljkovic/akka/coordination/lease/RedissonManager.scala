package com.nikolastojiljkovic.akka.coordination.lease

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import akka.actor.ExtendedActorSystem
import org.redisson.Redisson
import org.redisson.api.RLock
import org.redisson.config.Config
import org.redisson.connection.ConnectionListener
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object RedissonManager {
  private val logger = LoggerFactory.getLogger(RedissonManager.getClass)
  private val clients = new ConcurrentHashMap[ExtendedActorSystem, ConcurrentHashMap[String, Redisson]]
  private val lockReferences = new ConcurrentHashMap[ExtendedActorSystem, ConcurrentHashMap[Redisson, ConcurrentHashMap[RLock, Long]]]
  private val listenerIds = new ConcurrentHashMap[Redisson, Integer]
  private val leaseLostCallbacks = new ConcurrentHashMap[Redisson, ConcurrentHashMap[AnyRef, Option[Throwable] => Unit]]

  def getClient(config: Config, actorSystem: ExtendedActorSystem)(implicit ec: ExecutionContext): Redisson = {
    val key =
      try
        config.toYAML
      catch {
        case e: IOException =>
          e.printStackTrace()
          config.toString
      }

    clients.computeIfAbsent(actorSystem, (system: ExtendedActorSystem) => {
      system.registerOnTermination(() => {
        if (lockReferences.containsKey(system)) {
          lockReferences.get(system).forEach((client: Redisson, locks: ConcurrentHashMap[RLock, Long]) => {
            locks.forEach(RedissonManager.releaseLockIfPossible)
            locks.clear()
          })
          lockReferences.get(system).clear()
          lockReferences.remove(system)
        }
        if (clients.containsKey(system)) {
          val futures = clients.get(system).values
            .asScala.map(identity)(collection.breakOut)
            .map((client: Redisson) => Future {
              try {
                logger.debug("Shutting down " + client.getConfig.toJSON)
                processLeaseLostCallbacks(client)
                // remove the listener
                if (listenerIds.containsKey(client)) {
                  client.getConnectionManager.getConnectionEventsHub.removeListener(listenerIds.get(client))
                  listenerIds.remove(client)
                }
                client.shutdown(2000, 5000, TimeUnit.MILLISECONDS)
              } catch {
                case t: Throwable =>
                  logger.error("Error occurred while shutting down Redisson client on actor system termination", t)
              }
            })
          clients.get(system).clear()
          clients.remove(system)
          Await.result(Future.sequence(futures), Duration.Inf)
        }
      })
      new ConcurrentHashMap[String, Redisson]
    }).computeIfAbsent(key, (k: String) => {
      val client = Redisson.create(config).asInstanceOf[Redisson]
      val listenerId = client.getConnectionManager.getConnectionEventsHub.addListener(new ConnectionListener() {
        override def onConnect(addr: InetSocketAddress): Unit = {
        }

        override

        def onDisconnect(addr: InetSocketAddress): Unit = {
          // we simplify the implementation, we call leaseLostCallback on any server disconnect
          // * in theory, this might be only one of the servers of a cluster
          // * in practice, RedissonManager will only be used for locks on multiple single server instances
          if (listenerIds.containsKey(client)) {
            client.getConnectionManager.getConnectionEventsHub.removeListener(listenerIds.get(client))
            listenerIds.remove(client)
          }
          // remove the client so a new clean connection is retried on next usage
          if (clients.containsKey(actorSystem)) clients.get(actorSystem).remove(k)
          processLeaseLostCallbacks(client)
          // force unlock all the single locks we might have
          if (lockReferences.containsKey(actorSystem) && lockReferences.get(actorSystem).containsKey(client)) {
            // lockReferences.get(actorSystem).get(client).forEach(RedissonManager::releaseLockIfPossible);
            lockReferences.get(actorSystem).get(client).clear()
            lockReferences.get(actorSystem).remove(client)
          }
          client.shutdown()
        }
      })
      listenerIds.put(client, listenerId)
      client
    })
  }

  private def releaseLockIfPossible(lock: RLock, ownerThreadId: Long): Unit = {
    try
        if (lock.isLocked) if (lock.isHeldByThread(ownerThreadId)) {
          logger.debug("Unlocking " + lock.getName + ", held by thread " + ownerThreadId)
          lock.forceUnlock
        }
        else logger.warn("Skipping unlocking of " + lock.getName + ", it is not held by thread " + ownerThreadId)
    catch {
      case t: Throwable =>
        logger.error("Error occurred while processing " + lock.getName, t)
    }
  }

  private def processLeaseLostCallbacks(client: Redisson): Unit = {
    if (leaseLostCallbacks.containsKey(client)) {
      leaseLostCallbacks.get(client).forEach((p, callback) => {
        callback.apply(None)
      })
      leaseLostCallbacks.get(client).clear()
      leaseLostCallbacks.remove(client)
    }
  }

  def listenOnClientShutdown(client: Redisson, leaseLostCallback: Option[Throwable] => Unit, parent: AnyRef): Unit = {
    leaseLostCallbacks
      .computeIfAbsent(client, client =>
        new ConcurrentHashMap[AnyRef, Option[Throwable] => Unit]).put(parent, leaseLostCallback)
  }

  def addLockReference(actorSystem: ExtendedActorSystem, client: Redisson, lock: RLock, ownerThreadId: Long): Unit = {
    lockReferences
      .computeIfAbsent(actorSystem, actorSystem =>
        new ConcurrentHashMap[Redisson, ConcurrentHashMap[RLock, Long]])
      .computeIfAbsent(client, client =>
        new ConcurrentHashMap[RLock, Long]).put(lock, ownerThreadId)
  }

  def removeLockReference(actorSystem: ExtendedActorSystem, client: Redisson, lock: RLock): Unit = {
    lockReferences
      .computeIfAbsent(actorSystem, actorSystem =>
        new ConcurrentHashMap[Redisson, ConcurrentHashMap[RLock, Long]])
      .computeIfAbsent(client, client =>
        new ConcurrentHashMap[RLock, Long]).remove(lock)
  }
}
