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

import java.util

import org.redisson.RedissonRedLock
import org.redisson.api.RLock

import scala.collection.JavaConverters.seqAsJavaList

private[lease] class RedissonRedLockWithCustomMinLocks(val fixedMinLocksAmount: Int, val rLocks: RLock*) extends RedissonRedLock(rLocks:_*) {
  override protected def failedLocksLimit: Int = {
    Math.max(0, rLocks.size - minLocksAmount(seqAsJavaList(rLocks)))
  }

  override protected def minLocksAmount(locks: util.List[RLock]): Int = {
    if (fixedMinLocksAmount > 0) {
      fixedMinLocksAmount
    } else {
      super.minLocksAmount(locks)
    }
  }
}
