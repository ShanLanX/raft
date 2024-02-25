package com.swx.raft.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LogEntry implements Serializable,Comparable {
    private long term;
    private Long index;
    private Command command;

    @Override
    public int compareTo(Object o) {
        if(o==null)
            return -1;
        LogEntry logEntry=(LogEntry) o;
        if(this.getIndex()>logEntry.getIndex()){
            return 1;
        }
        return -1;


    }
}
