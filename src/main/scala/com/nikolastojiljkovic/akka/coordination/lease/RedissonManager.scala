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

import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import akka.actor.ActorSystem
import org.redisson.Redisson
import org.redisson.api.RLock
import org.redisson.config.Config
import org.redisson.connection.ConnectionListener
import org.slf4j.{Logger, LoggerFactory}

import collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.implicitConversions
import LogHelper._

private[lease] object RedissonManager {
  implicit val logger: Logger = LoggerFactory.getLogger(RedissonManager.getClass)

  private val clients = new ConcurrentHashMap[ActorSystem, ConcurrentHashMap[String, Redisson]]
  private val lockReferences = new ConcurrentHashMap[ActorSystem, ConcurrentHashMap[Redisson, ConcurrentHashMap[RLock, Long]]]
  private val listenerIds = new ConcurrentHashMap[Redisson, Integer]
  private val leaseLostCallbacks = new ConcurrentHashMap[Redisson, ConcurrentHashMap[AnyRef, Option[Throwable] => Unit]]
  private val clientShutdownQuietPeriod = 2000
  private val clientShutdownTimeout = 5000

  def getClient(config: Config, actorSystem: ActorSystem)(implicit ec: ExecutionContext): Redisson = {
    val key = logTry("Failed to convert Redisson config to YAML.") {
      config.toYAML
    }.toOption.getOrElse(config.toString)

    clients.computeIfAbsent(actorSystem, (system: ActorSystem) => {
      system.registerOnTermination(() => {
        terminateActorSystemClients(system)
      })
      new ConcurrentHashMap[String, Redisson]
    }).computeIfAbsent(key, (k: String) => {
      val client = Redisson.create(config).asInstanceOf[Redisson]
      val listenerId = client.getConnectionManager.getConnectionEventsHub.addListener(new ConnectionListener() {
        override def onConnect(addr: InetSocketAddress): Unit = {

        }

        override def onDisconnect(addr: InetSocketAddress): Unit = {
          // we simplify the implementation, we call leaseLostCallback on any server disconnect
          // * in theory, this might be only one of the servers of a cluster
          // * in practice, RedissonManager will only be used for locks on multiple single server instances

          processLeaseLostCallbacks(client, "Disconnected from " + addr.toString + ".")
        }
      })
      listenerIds.put(client, listenerId)
      client
    })
  }

  def addListenerOnClientShutdown(client: Redisson, leaseLostCallback: Option[Throwable] => Unit, parent: AnyRef): Unit = {
    leaseLostCallbacks
      .computeIfAbsent(client, _ =>
        new ConcurrentHashMap[AnyRef, Option[Throwable] => Unit]).put(parent, leaseLostCallback)
  }

  def removeListenerOnClientShutdown(client: Redisson, parent: AnyRef): Unit = {
    if (leaseLostCallbacks.containsKey(client) && leaseLostCallbacks.get(client).containsKey(parent)) {
      leaseLostCallbacks.get(client).remove(parent)
    }
  }

  def addLockReference(actorSystem: ActorSystem, client: Redisson, lock: RLock, ownerThreadId: Long): Unit = {
    lockReferences
      .computeIfAbsent(actorSystem, _ =>
        new ConcurrentHashMap[Redisson, ConcurrentHashMap[RLock, Long]])
      .computeIfAbsent(client, _ =>
        new ConcurrentHashMap[RLock, Long]).put(lock, ownerThreadId)
  }

  def removeLockReference(actorSystem: ActorSystem, client: Redisson, lock: RLock): Unit = {
    lockReferences
      .computeIfAbsent(actorSystem, _ =>
        new ConcurrentHashMap[Redisson, ConcurrentHashMap[RLock, Long]])
      .computeIfAbsent(client, _ =>
        new ConcurrentHashMap[RLock, Long]).remove(lock)
  }

  private def terminateActorSystemClients(system: ActorSystem)(implicit ec: ExecutionContext): Unit = {
    if (lockReferences.containsKey(system)) {
      lockReferences.get(system).forEach((_, locks: ConcurrentHashMap[RLock, Long]) => {
        locks.forEach(RedissonManager.releaseLockIfPossible)
        locks.clear()
      })
      lockReferences.get(system).clear()
      lockReferences.remove(system)
    }
    if (clients.containsKey(system)) {
      val futures = (Seq() ++ clients.get(system).values.asScala)
        .map((client: Redisson) => Future {
          logTry("Error occurred while shutting down Redisson client on actor system termination") {
            logger.debug("Shutting down " + client.getConfig.toJSON)
            processLeaseLostCallbacks(client, "Shutting down Redisson client.")
            // remove the listener
            if (listenerIds.containsKey(client)) {
              client.getConnectionManager.getConnectionEventsHub.removeListener(listenerIds.get(client))
              listenerIds.remove(client)
            }
            client.shutdown(clientShutdownQuietPeriod, clientShutdownTimeout, TimeUnit.MILLISECONDS)
          }
        })
      clients.get(system).clear()
      clients.remove(system)
      Await.result(Future.sequence(futures), Duration.Inf)
    }
  }

  private def releaseLockIfPossible(lock: RLock, ownerThreadId: Long): Unit = {
    logTry("Error occurred while processing " + lock.getName) {
      if (lock.isLocked) if (lock.isHeldByThread(ownerThreadId)) {
        logger.debug("Unlocking " + lock.getName + ", held by thread " + ownerThreadId)
        lock.forceUnlock
      } else {
        logger.warn("Skipping unlocking of " + lock.getName + ", it is not held by thread " + ownerThreadId)
      }
    }
  }

  private def processLeaseLostCallbacks(client: Redisson, reason: String): Unit = {
    if (leaseLostCallbacks.containsKey(client)) {
      leaseLostCallbacks.get(client).forEach((_, callback) => {
        callback.apply(Some(new RuntimeException(reason)))
      })
      leaseLostCallbacks.get(client).clear()
      leaseLostCallbacks.remove(client)
    }
  }
}
