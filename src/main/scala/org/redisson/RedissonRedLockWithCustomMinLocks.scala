package org.redisson

import java.util
import org.redisson.api.RLock
import scala.collection.JavaConverters.seqAsJavaList

class RedissonRedLockWithCustomMinLocks(val fixedMinLocksAmount: Int, val rLocks: RLock*) extends RedissonRedLock(rLocks:_*) {
  override protected def failedLocksLimit: Int = {
    Math.max(0, locks.size - minLocksAmount(seqAsJavaList(rLocks)))
  }

  override protected def minLocksAmount(locks: util.List[RLock]): Int = {
    if (fixedMinLocksAmount > 0)
      fixedMinLocksAmount
    else
      super.minLocksAmount(locks)
  }
}
