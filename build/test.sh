#!/bin/bash

set -e

service redis-server start

REDIS_START_CMD="service redis-server start" REDIS_STOP_CMD="redis-cli shutdown" sbt clean ++2.12.2 test