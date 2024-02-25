package com.swx.raft.server;

import com.swx.raft.common.LifeCycle;
import com.swx.raft.common.entity.LogEntry;

public interface StateMachine extends LifeCycle {
    /**
     * 将数据运用到状态机
     * @param logEntry
     */
    void apply(LogEntry logEntry);

    LogEntry get(String key);

    String getString(String key);

    void setString(String key, String value);

    void delString(String... key);
}
