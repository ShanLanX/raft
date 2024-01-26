package com.swx.raft.common.entity;

/**
 *  RPC请求投票的参数 候选人向其他节点发起投票请求
 */
public class RvoteParam {
    /**
     * 候选人的任期号
     */
    private long term;
    /**
     * 请求者的ID
     */
    private String serverId;
    /**
     * 候选人的ID
     */
    private String candidateId;
    /**
     * 候选人最后日志条目的索引值
     */
    private long lastLogIndex;

    /**
     * 候选人最后日志条目的任期号
     */
    private long lastLogTerm;

}
