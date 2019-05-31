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

import java.lang.Runtime.getRuntime

import akka.actor.ActorSystem
import akka.coordination.lease.scaladsl.LeaseProvider
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class RedissonRedLockLeaseSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit private val actorSystem: ActorSystem = ActorSystem("AkkaCoordinationRedis")
  private val log: Logger = LoggerFactory.getLogger(getClass)
  private val atMost = 5 minutes
  private val startRedisCmd = if (System.getenv("REDIS_START_CMD") == null) {
    if (System.getProperty("os.name").toLowerCase.contains("mac")) {
      "brew services start redis"
    } else {
      "service redis-server start"
    }
  } else {
    System.getenv("REDIS_START_CMD")
  }
  private val stopRedisCmd = if (System.getenv("REDIS_STOP_CMD") == null) {
    if (System.getProperty("os.name").toLowerCase.contains("mac")) {
      "brew services stop redis"
    } else {
      "service redis-server stop"
    }
  } else {
    System.getenv("REDIS_STOP_CMD")
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(actorSystem, 10 seconds, verifySystemShutdown = true)
  }

  "RedissonRedLockLease" should {
    "allow reentrant leases" in {
      val lockName = "test-reentrant-lease"

      val leaseA = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease", "owner1")
      leaseA.settings.leaseName should equal(lockName)
      leaseA.settings.ownerName should equal("owner1")
      leaseA.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseA.checkLease should equal(false)

      val leaseB = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease", "owner2")
      leaseB.settings.leaseName should equal(lockName)
      leaseB.settings.ownerName should equal("owner2")
      leaseB.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseB.checkLease should equal(false)

      // lock A 1st time
      Await.result(leaseA.acquire, atMost) should equal(true)
      leaseA.checkLease should equal(true)

      // lock A 2nd time
      Await.result(leaseA.acquire, atMost) should equal(true)
      leaseA.checkLease should equal(true)

      // try to lock B
      Await.result(leaseB.acquire, atMost) should equal(false)
      leaseB.checkLease should equal(false)

      // release A 1st time
      Await.result(leaseA.release, atMost) should equal(false)
      leaseA.checkLease should equal(true)

      // release A 2nd time
      Await.result(leaseA.release, atMost) should equal(true)
      leaseA.checkLease should equal(false)

      // lock B 1st time
      Await.result(leaseB.acquire, atMost) should equal(true)
      leaseB.checkLease should equal(true)

      // lock B 2nd time
      Await.result(leaseB.acquire, atMost) should equal(true)
      leaseB.checkLease should equal(true)

      // release B 1st time
      Await.result(leaseB.release, atMost) should equal(false)
      leaseB.checkLease should equal(true)

      // release B 2nd time
      Await.result(leaseB.release, atMost) should equal(true)
      leaseB.checkLease should equal(false)
    }

    "fail if Redis connection gets lost and recover if Redis gets up and running afterwards" in {
      val lockName = "test-failed-lease"
      val probe = new TestProbe(actorSystem)

      val leaseA = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease", "owner1")
      leaseA.settings.leaseName should equal(lockName)
      leaseA.settings.ownerName should equal("owner1")
      leaseA.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseA.checkLease should equal(false)

      log.info("Lock A 1st time...")
      Await.result(leaseA.acquire(_ => probe.ref ! "lease-lost"), atMost) should equal(true)
      leaseA.checkLease should equal(true)

      log.info("Stopping Redis... " + stopRedisCmd)
      val stop = getRuntime.exec(Array("bash", "-c", stopRedisCmd))
      log.info(scala.io.Source.fromInputStream(stop.getErrorStream).mkString)
      log.info(scala.io.Source.fromInputStream(stop.getInputStream).mkString)

      log.info("Wait till we get lease-lost message...")
      probe.expectMsg(10 seconds, "lease-lost")
      leaseA.checkLease should equal(false)

      log.info("Wait a bit more till we start Redis again...")
      Thread.sleep(leaseA.settings.timeoutSettings.operationTimeout.toMillis)

      log.info("Starting Redis... " + startRedisCmd)
      val start = getRuntime.exec(Array("bash", "-c", startRedisCmd))
      log.info(scala.io.Source.fromInputStream(start.getErrorStream).mkString)
      log.info(scala.io.Source.fromInputStream(start.getInputStream).mkString)
      Thread.sleep(leaseA.settings.timeoutSettings.operationTimeout.toMillis)

      val leaseB = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease", "owner2")
      leaseB.settings.leaseName should equal(lockName)
      leaseB.settings.ownerName should equal("owner2")
      leaseB.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseB.checkLease should equal(false)

      log.info("Double check that we cannot acquire another lease with the same name (different owner)...")
      Await.result(leaseB.acquire(), atMost) should equal(false)
      leaseB.checkLease should equal(false)

      val leaseC = LeaseProvider.get(actorSystem).getLease(lockName + "X", "redisson-red-lock-lease", "owner1")
      leaseC.settings.leaseName should equal(lockName + "X")
      leaseC.settings.ownerName should equal("owner1")
      leaseC.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseC.checkLease should equal(false)

      log.info("Double check that we can acquire another lease with a different name (C)...")
      Await.result(leaseC.acquire(), atMost) should equal(true)
      leaseC.checkLease should equal(true)

      log.info("Release C...")
      Await.result(leaseC.release(), atMost) should equal(true)
      leaseC.checkLease should equal(false)

      log.info("Double check that we still don't have A lease...")
      leaseA.checkLease should equal(false)

      log.info("Wait for the lease to expire on Redis...")
      Thread.sleep(leaseA.settings.timeoutSettings.getHeartbeatTimeout.toMillis)

      log.info("Lock A 2nd time...")
      Await.result(leaseA.acquire(), atMost) should equal(true)
      probe.expectNoMessage(10 seconds)
      leaseA.checkLease should equal(true)

      log.info("Release A...")
      Await.result(leaseA.release(), atMost) should equal(true)
      leaseA.checkLease should equal(false)

      log.info("Double check that we can acquire another lease with the same name (which failed previously)...")
      Await.result(leaseB.acquire(), atMost) should equal(true)
      leaseB.checkLease should equal(true)

      log.info("Release C...")
      Await.result(leaseB.release(), atMost) should equal(true)
      leaseB.checkLease should equal(false)
    }

    "fail if there are not enough Redis servers available" in {
      val lockName = "test-no-connection-lease"

      val leaseA = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease-with-custom-min-lease", "owner1")
      leaseA.settings.leaseName should equal(lockName)
      leaseA.settings.ownerName should equal("owner1")
      leaseA.settings.leaseConfig.getInt("min-locks-amount") should equal(2)
      leaseA.checkLease should equal(false)

      log.info("Lock A 1st time...")
      (the[RuntimeException] thrownBy {
        Await.result(leaseA.acquire, atMost)
      }).getMessage should include("Failing without even trying to lock.")
    }
  }
}
