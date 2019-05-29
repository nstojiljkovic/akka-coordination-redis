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

package com.nikolastojiljkovic.akka.coordination

import scala.util.{Failure, Try}

package object lease {
  def logTry[A](computation: => A)(implicit logger: LogHelper): Try[A] = {
    Try(computation) recoverWith {
      case e: Throwable =>
        logger.error(e.getMessage, e)
        Failure(e)
    }
  }

  def logTry[A](errorMessage: String)(computation: => A)(implicit logger: LogHelper): Try[A] = {
    Try(computation) recoverWith {
      case e: Throwable =>
        logger.error(e.getMessage, e)
        Failure(e)
    }
  }
}
