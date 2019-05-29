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

import org.redisson.RedissonLock
import org.redisson.command.CommandAsyncExecutor

class RedissonLockWithCustomOwner(val commandAsyncExecutor: CommandAsyncExecutor, val name: String, val owner: String)
  extends RedissonLock(commandAsyncExecutor, name) {

  override protected def getLockName(threadId: Long): String = {
    // as per Akka coordination specs, do not respect thread ids
    // owner + ":" + threadId
    owner
  }
}