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

import java.lang
import java.util.concurrent.TimeUnit

import org.redisson.RedissonLock
import org.redisson.api.RFuture
import org.redisson.command.CommandAsyncExecutor

import scala.compat.java8.FutureConverters
import scala.concurrent.{ExecutionContext, Future}

private[lease] class RedissonLockWithCustomOwnerAndFixedLeaseTime
  (commandAsyncExecutor: CommandAsyncExecutor, leaseTime: Long, name: String, owner: String) extends RedissonLock(commandAsyncExecutor, name) {

  override protected def getLockName(threadId: Long): String = {
    // as per Akka coordination specs, do not respect thread ids
    // owner + ":" + threadId
    owner
  }

  override def tryLockAsync(waitTime: Long, leaseTime: Long, unit: TimeUnit, currentThreadId: Long): RFuture[lang.Boolean] = {
    if (leaseTime == -1) {
      // Parent RedissonRedLockWithCustomMinLocks is initialized with leaseTime set to -1.
      // RedissonLock internally performs renewal on 1/3 of lockWatchdogTimeout when leaseTime is set to -1.
      // However, as we want custom heartbeat-interval, we need to use the predefined leaseTime here (which should be
      // initialized to heartbeat-timeout) and perform manually the renewal on heartbeat-interval.
      super.tryLockAsync(waitTime, this.leaseTime, unit, currentThreadId)
    } else {
      super.tryLockAsync(waitTime, leaseTime, unit, currentThreadId)
    }
  }

  def renewAsync(implicit ec: ExecutionContext):Future[Boolean] = {
    renewAsync(Thread.currentThread().getId)
  }

  def renewAsync(threadId: Long)(implicit ec: ExecutionContext):Future[Boolean] = {
    FutureConverters.toScala(super.renewExpirationAsync(threadId).toCompletableFuture).map(Boolean.box(_))
  }
}
