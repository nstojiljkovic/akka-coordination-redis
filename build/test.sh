#!/bin/bash

set -e

service redis-server start

REDIS_START_CMD="service redis-server start" REDIS_STOP_CMD="redis-cli shutdown" sbt +clean +test
