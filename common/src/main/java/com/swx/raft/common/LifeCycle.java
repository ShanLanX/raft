package com.swx.raft.common;
public interface LifeCycle {
    public void init() throws Throwable;

    public void destroy() throws Throwable;
}
