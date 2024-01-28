package com.swx.raft.common.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
@Data
@Builder
/**
 *  leader用于将日志复制给follower，同时也可当作心跳信息
 */
public class AentryParam implements Serializable {
    /**
     * leader的任期
     */
    private long term;
    /**
     * 被请求者的ID(ip:selfport)
     */
    private String serverId;
    // xQS follower重定向是什么意思
    /**
     * leader的ID，并于follower重定向请求
     */
    private String leaderId;

    /**
     * 新日志紧随的前条日志的index
     */
    private  long preLogIndex;
    /**
     * 新日志紧随的前条日志的任期
     */
    private long preLogTerm;

    /**
     * 一次发送多条日志
     */
    private LogEntry[] entries;
    /**
     * 领导人以提交的日志的索引
     */
    private long leaderCommit;






}
