package com.swx.raft.common.entity;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.apache.log4j.Logger;

import java.io.Serializable;

/**
 *  RPC请求投票的参数 候选人向其他节点发起投票请求
        */
@Builder
@Data
public class RvoteParam implements Serializable {
    /**
     * 候选人的任期号
     */
    private long term;
    /**
     * 被请求者的ID(ip:selfport)
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
