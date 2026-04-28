package com.hmdp.utils;

// 分布式锁
public interface ILock {
    boolean tryLock(long timeoutSec);
    void unLock();
}
