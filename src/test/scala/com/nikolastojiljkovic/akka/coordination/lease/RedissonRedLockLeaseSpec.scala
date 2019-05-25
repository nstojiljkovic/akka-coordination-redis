package com.nikolastojiljkovic.akka.coordination.lease

import java.lang.Runtime.getRuntime

import akka.actor.ActorSystem
import akka.coordination.lease.scaladsl.LeaseProvider
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class RedissonRedLockLeaseSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit private val actorSystem: ActorSystem = ActorSystem("AkkaCoordinationRedis")
  private val atMost = 5 minutes
  private val (startRedisCmd, stopRedisCmd) = if (System.getProperty("os.name").toLowerCase.contains("mac")) {
    ("brew services start redis", "brew services stop redis")
  } else {
    ("service redis start", "service redis stop")
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

      System.out.println("Lock A 1st time...")
      Await.result(leaseA.acquire(_ => probe.ref ! "lease-lost"), atMost) should equal(true)
      leaseA.checkLease should equal(true)

      System.out.println("Stopping Redis...")
      getRuntime.exec(stopRedisCmd)

      System.out.println("Wait till we get lease-lost message...")
      probe.expectMsg(10 seconds, "lease-lost")
      leaseA.checkLease should equal(false)

      System.out.println("Wait a bit more till we start Redis again...")
      Thread.sleep(10000)

      System.out.println("Starting Redis...")
      getRuntime.exec(startRedisCmd)

      Thread.sleep(10000)

      val leaseB = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease", "owner2")
      leaseB.settings.leaseName should equal(lockName)
      leaseB.settings.ownerName should equal("owner2")
      leaseB.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseB.checkLease should equal(false)

      System.out.println("Double check that we cannot acquire another lease with the same name (different owner)...")
      Await.result(leaseB.acquire(), atMost) should equal(false)
      leaseB.checkLease should equal(false)

      val leaseC = LeaseProvider.get(actorSystem).getLease(lockName + "X", "redisson-red-lock-lease", "owner1")
      leaseC.settings.leaseName should equal(lockName + "X")
      leaseC.settings.ownerName should equal("owner1")
      leaseC.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseC.checkLease should equal(false)

      System.out.println("Double check that we can acquire another lease with a different name (C)...")
      Await.result(leaseC.acquire(), atMost) should equal(true)
      leaseC.checkLease should equal(true)

      System.out.println("Release C...")
      Await.result(leaseC.release(), atMost) should equal(true)
      leaseC.checkLease should equal(false)

      System.out.println("Double check that we still don't have A lease...")
      leaseA.checkLease should equal(false)

      System.out.println("Wait for the lease to expire on Redis...")
      Thread.sleep(leaseA.settings.timeoutSettings.getHeartbeatTimeout.toMillis)

      System.out.println("Lock A 2nd time...")
      Await.result(leaseA.acquire(), atMost) should equal(true)
      probe.expectNoMessage(10 seconds)
      leaseA.checkLease should equal(true)

      System.out.println("Release A...")
      Await.result(leaseA.release(), atMost) should equal(true)
      leaseA.checkLease should equal(false)

      System.out.println("Double check that we can acquire another lease with the same name (which failed previously)...")
      Await.result(leaseB.acquire(), atMost) should equal(true)
      leaseB.checkLease should equal(true)

      System.out.println("Release C...")
      Await.result(leaseB.release(), atMost) should equal(true)
      leaseB.checkLease should equal(false)
    }

    "expire after configured time and call leaseLostCallback" in {
      val lockName = "test-expired-lease"
      val probe = new TestProbe(actorSystem)

      val leaseA = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease", "owner1")
      leaseA.settings.leaseName should equal(lockName)
      leaseA.settings.ownerName should equal("owner1")
      leaseA.settings.leaseConfig.getString("dispatcher") should equal("custom-dispatcher")
      leaseA.checkLease should equal(false)

      System.out.println("Lock A 1st time...")
      Await.result(leaseA.acquire(_ => {
        System.out.println("Sending lease-lost")
        probe.ref ! "lease-lost"
      }), atMost) should equal(true)
      leaseA.checkLease should equal(true)

      probe.expectNoMessage((leaseA.settings.timeoutSettings.getHeartbeatTimeout.toMillis - leaseA.settings.timeoutSettings.getOperationTimeout.toMillis) millis)
      System.out.println("Wait till we get lease-lost message...")
      probe.expectMsg((2 * leaseA.settings.timeoutSettings.getOperationTimeout.toMillis) millis, "Did not receive lease-lost message!", "lease-lost")
      Thread.sleep(100)
      leaseA.checkLease should equal(false)

      Thread.sleep(5000)

      // test reentrant lease
      System.out.println("Lock A 1st time...")
      Await.result(leaseA.acquire(_ => {
        System.out.println("Sending reentrant-lease-lost")
        probe.ref ! "reentrant-lease-lost"
      }), atMost) should equal(true)
      leaseA.checkLease should equal(true)

      Thread.sleep(leaseA.settings.timeoutSettings.getHeartbeatTimeout.toMillis / 2)

      System.out.println("Lock A 2nd time...")
      Await.result(leaseA.acquire, atMost) should equal(true)
      leaseA.checkLease should equal(true)

      probe.expectNoMessage((leaseA.settings.timeoutSettings.getHeartbeatTimeout.toMillis - leaseA.settings.timeoutSettings.getOperationTimeout.toMillis) millis)
      System.out.println("Wait till we get reentrant-lease-lost message...")
      probe.expectMsg((2 * leaseA.settings.timeoutSettings.getOperationTimeout.toMillis) millis, "Did not receive reentrant-lease-lost message!", "reentrant-lease-lost")
      Thread.sleep(100)
      leaseA.checkLease should equal(false)
    }

    "fail if there are not enough Redis servers available" in {
      val lockName = "test-no-connection-lease"
      val probe = new TestProbe(actorSystem)

      val leaseA = LeaseProvider.get(actorSystem).getLease(lockName, "redisson-red-lock-lease-with-custom-min-lease", "owner1")
      leaseA.settings.leaseName should equal(lockName)
      leaseA.settings.ownerName should equal("owner1")
      leaseA.settings.leaseConfig.getInt("min-locks-amount") should equal(2)
      leaseA.checkLease should equal(false)

      System.out.println("Lock A 1st time...")
      (the[RuntimeException] thrownBy {
        Await.result(leaseA.acquire, atMost)
      }).getMessage should include("Failing without even trying to lock.")
    }
  }
}
