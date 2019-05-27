package com.nikolastojiljkovic.akka.coordination.lease

import org.redisson.RedissonLock
import org.redisson.command.CommandAsyncExecutor

class RedissonLockWithCustomOwner(val commandAsyncExecutor: CommandAsyncExecutor, val name: String, val owner: String) extends RedissonLock(commandAsyncExecutor, name) {
  override protected def getLockName(threadId: Long): String = {
    // as per Akka coordination specs, do not respect thread ids
    // owner + ":" + threadId
    owner
  }
}
