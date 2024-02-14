package com.swx.raft.common.entity;

import lombok.Builder;

import java.util.concurrent.Callable;

@Builder
public class ReplicationFailModel {
    static String count="_count";
    static String success="_success";
    public String countKey;
    public String successKey;
    public Callable callable;
    public LogEntry logEntry;
    public Peer peer;
    public Long offerTime;



}
