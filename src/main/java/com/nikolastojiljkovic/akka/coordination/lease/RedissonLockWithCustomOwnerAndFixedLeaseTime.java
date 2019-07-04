package com.nikolastojiljkovic.akka.coordination.lease;

import org.redisson.RedissonLock;
import org.redisson.api.RFuture;
import org.redisson.command.CommandAsyncExecutor;

import java.util.concurrent.TimeUnit;

public class RedissonLockWithCustomOwnerAndFixedLeaseTime extends RedissonLock {

    private final long leaseTime;
    private final String owner;

    public RedissonLockWithCustomOwnerAndFixedLeaseTime(final CommandAsyncExecutor commandExecutor, final long leaseTime, final String name, final String owner) {
        super(commandExecutor, name);
        this.leaseTime = leaseTime;
        this.owner = owner;
    }

    @Override
    protected String getLockName(long threadId) {
        // as per Akka coordination specs, do not respect thread ids
        // owner + ":" + threadId
        return owner;
    }

    @Override
    public RFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit, long currentThreadId) {
        if (leaseTime == -1) {
            // Parent RedissonRedLockWithCustomMinLocks is initialized with leaseTime set to -1.
            // RedissonLock internally performs renewal on 1/3 of lockWatchdogTimeout when leaseTime is set to -1.
            // However, as we want custom heartbeat-interval, we need to use the predefined leaseTime here (which should be
            // initialized to heartbeat-timeout) and perform manually the renewal on heartbeat-interval.
            return super.tryLockAsync(waitTime, this.leaseTime, unit, currentThreadId);
        } else {
            return super.tryLockAsync(waitTime, leaseTime, unit, currentThreadId);
        }
    }

    public RFuture<Boolean> renewAsync() {
        return renewAsync(Thread.currentThread().getId());
    }

    private RFuture<Boolean> renewAsync(final long threadId) {
        return super.renewExpirationAsync(threadId);
    }
}
