package com.swx.raft.server.impl;

import com.swx.raft.common.entity.*;
import com.swx.raft.server.Consensus;
import io.netty.util.internal.StringUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Data
public class DefaultConsensus implements Consensus {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConsensus.class);
    public  final DefaultNode node;
    /**
     * 投票锁
     */

    public final ReentrantLock voteLock=new ReentrantLock();

    /**
     * 日志追加锁
     */
    public final ReentrantLock appendLock=new ReentrantLock();

    public DefaultConsensus(DefaultNode node) {this.node=node;}

    /**
     * 请求投票 RPC
     * @param rvoteParam
     * @return
     *
     *
     * 接收者实现
     *  如果term<currentTerm 返回false
     *  如果 votedFor 为空或者就是 candidateId, 并且候选人的日志至少和自己一样新，那就投票给他
     *
     */
    public RvoteResult requestVote(RvoteParam rvoteParam) {
       try{
           if(!voteLock.tryLock()){
               return  RvoteResult.builder().term(node.getCurrentTerm()).voteGranted(false).build();

           }

           // 请求任期没有自己长
           if(rvoteParam.getTerm()<node.getCurrentTerm()){
               return RvoteResult.builder().term(node.getCurrentTerm()).voteGranted(false).build();

           }
           log.info("node {} current vote for [{}] , param candidateId : {}",node.peerSet.getSelf(),node.getVotedFor(), rvoteParam.getCandidateId());
           log.info("node {} current term {}, peer term : {}", node.peerSet.getSelf(), node.getCurrentTerm(), rvoteParam.getTerm());

           if(StringUtil.isNullOrEmpty(node.getVotedFor())||node.getVotedFor().equals(rvoteParam.getCandidateId())){
               if(node.getLogModule().getLast()!=null){
                   // 对方日志任期没有自己新
                   if(node.getLogModule().getLast().getTerm()>rvoteParam.getLastLogTerm()){
                       return RvoteResult.fail();
                   }
                   // 对方日志index没有自己新
                   if(node.getLogModule().getLastIndex()>rvoteParam.getLastLogIndex()){
                       return RvoteResult.fail();
                   }

               }
               // 否则 投票给对方并切换节点状态
               node.status= NodeStatus.FOLLOWER;
               node.peerSet.setLeader(new Peer(rvoteParam.getCandidateId()));
               node.setCurrentTerm(rvoteParam.getTerm());
               node.setVotedFor(rvoteParam.getServerId());
               // 返回成功
               return RvoteResult.builder().term(node.getCurrentTerm()).voteGranted(true).build();
           }
           return RvoteResult.builder().term(node.getCurrentTerm()).voteGranted(false).build();

       }
       finally {
           voteLock.unlock();
       }

    }

    /**
     * 附加日志
     *
     * 接收者实现：
     * 如果 term < currentTerm 就返回 false （5.1 节）
     *          如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false （5.3 节）
     *          如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的 （5.3 节）
     *          附加任何在已有的日志中不存在的条目
     *
     *
     */

    public AentryResult appendEntries(AentryParam param) {
        AentryResult result = AentryResult.fail();
        try {
            if (!appendLock.tryLock()) {
                return result;
            }
            result.setTerm(node.getCurrentTerm());

            //
            if (param.getTerm() < node.getCurrentTerm()) {
                return result;
            }

            node.preHeartBeatTime = System.currentTimeMillis();
            node.preElectionTime = System.currentTimeMillis();
            node.peerSet.setLeader(new Peer(param.getLeaderId()));

            // 任期符合要求
            if (param.getTerm() >= node.getCurrentTerm()) {
                log.debug("node {} become FOLLOWER, currenTerm : {}, param Term : {}, param serverId = {}",
                        node.peerSet.getSelf(), node.getCurrentTerm(), param.getTerm(), param.getServerId());

                //
                node.status = NodeStatus.FOLLOWER;
                node.setCurrentTerm(param.getTerm());


            }
            // 如果发送的是心跳信息
            if (param.getEntries() == null || param.getEntries().length == 0) {

                log.info("node {} append heartbeat success , he's term : {}, my term : {}", param.getLeaderId(), param.getTerm(), node.getCurrentTerm());

                // 处理 leader 已提交但未应用到节点状态机的日志

                // 下一个需要提交的日志的索引
                long nextCommit = node.getCommitIndex() + 1;

                // 如果 leaderCommit > commitIndex , 令 commitIndex 等于 leaderCommit 和 新日志目录索引中较小的一个
                if (param.getLeaderCommit() > node.getCommitIndex()) {
                    int commitIndex = (int) Math.min(param.getLeaderCommit(), node.getLogModule().getLastIndex());
                    node.setCommitIndex(commitIndex);
                    node.setLastApplied(commitIndex);
                }
                while (nextCommit <= node.getCommitIndex()) {
                    // 提交 leadercommit 之前的日志
                    node.stateMachine.apply(node.logModule.read(nextCommit));
                    nextCommit++;
                }
                return AentryResult.builder().term(node.getCurrentTerm()).success(true).build();

            }
            // 发送真实日志
            if (node.getLogModule().getLastIndex()!=0&&param.getPreLogIndex()!=0) {
                LogEntry logEntry=node.getLogModule().read(param.getPreLogIndex());
                if(logEntry!=null) {
                    // 如果当前节点日志在prelogIndex位置的日志的任期号和prelogterm不匹配，返回false。检查prelogindex及其之前的日志
                    // 需要减小nextIndex重试
                    if(logEntry.getTerm()!=param.getPreLogTerm()) {
                        return result;
                    }


                }
                else{
                    return result;
                }




            }

            // 检查prelogindex之后的日志是否会发送冲突
            // 发送冲突需删除日志
            LogEntry existLog = node.getLogModule().read(((param.getPreLogIndex() + 1)));
            if(existLog!=null&&existLog.getTerm()!=param.getEntries()[0].getTerm()){
                // 删除本条及其之后的所有日志
                node.getLogModule().removeOnStartIndex((param.getPreLogIndex()+1));

            }
            else if(existLog!=null){
                // 重复日志不再写入
                result.setSuccess(true);
                return result;
            }

            // 写进日志
            for (LogEntry entry : param.getEntries()) {
                node.getLogModule().write(entry);
                result.setSuccess(true);
            }

            //
            long nextCommit=node.getCommitIndex()+1;

            //如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
            if (param.getLeaderCommit() > node.getCommitIndex()) {
                int commitIndex = (int) Math.min(param.getLeaderCommit(), node.getLogModule().getLastIndex());
                node.setCommitIndex(commitIndex);
                node.setLastApplied(commitIndex);
            }

            while (nextCommit <= node.getCommitIndex()){
                // 提交之前的日志
                node.stateMachine.apply(node.logModule.read(nextCommit));
                nextCommit++;
            }

            result.setTerm(node.getCurrentTerm());

            node.status = NodeStatus.FOLLOWER;
            return result;







        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            appendLock.unlock();
        }

    }
    }

