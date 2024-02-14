package com.swx.raft.server;

import com.swx.raft.common.LifeCycle;
import com.swx.raft.common.entity.LogEntry;

public interface LogModule extends LifeCycle {
    void write(LogEntry logEntry);

    LogEntry read(Long index);

    void removeOnStartIndex(Long startIndex);

    LogEntry getLast();

    Long getLastIndex();
}
