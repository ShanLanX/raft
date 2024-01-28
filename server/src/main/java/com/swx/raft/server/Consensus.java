package com.swx.raft.server;

import com.swx.raft.common.entity.AentryParam;
import com.swx.raft.common.entity.AentryResult;
import com.swx.raft.common.entity.RvoteParam;
import com.swx.raft.common.entity.RvoteResult;

public interface Consensus {

    /**
     * 发送投票请求RPC，候选者发送请求投票
     * 接收者实现：
     *  if term<currentTerm 返回false
     *  if 节点votedFor为空或candidateId，并且候选人日志和自己一样新就投票给它
     *
     */
    RvoteResult requestVote(RvoteParam rvoteParam);

    /**
     * 日志追加请求RPC
     *
     * 接收者实现：
     * if term<currentTerm 返回false
     * 如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false
     * 如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的
     * 附加任何在已有的日志中不存在的条目
     * 如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
     *
     * @param param
     * @return
     */
    AentryResult appendEntries(AentryParam param);









}
