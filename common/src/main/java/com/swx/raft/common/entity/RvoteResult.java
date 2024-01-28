package com.swx.raft.common.entity;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;


@Data
@Builder
/**
 * 请求投票的RPC的返回值
 */
public class RvoteResult implements Serializable {
    /**
     * 当前任期，以便候选人更新自己的任期
     */
    long term;

    /**
     * 候选人是否赢得选票
     */
    boolean voteGranted;

    public RvoteResult(boolean voteGranted) {
        this.voteGranted = voteGranted;
    }

    public static RvoteResult fail() {
        return new RvoteResult(false);
    }

    public static RvoteResult ok() {
        return new RvoteResult(true);
    }

}
