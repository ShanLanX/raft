package com.swx.raft.server.impl;

import com.swx.raft.common.entity.*;
import com.swx.raft.common.rpc.DefaultRpcClient;
import com.swx.raft.common.rpc.Request;
import com.swx.raft.common.rpc.RpcClient;
import com.swx.raft.server.Consensus;
import com.swx.raft.server.LogModule;
import com.swx.raft.server.Node;
import com.swx.raft.server.StateMachine;
import com.swx.raft.server.changes.ClusterMembershipChanges;
import com.swx.raft.server.constant.StateMachineSaveType;
import com.swx.raft.server.rpc.DefaultRpcServiceImpl;
import com.swx.raft.server.rpc.RpcService;
import com.swx.raft.server.thread.RaftThreadPool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

@Data
@Slf4j
public class DefaultNode implements Node,ClusterMembershipChanges {

    /**
     * 选举时间间隔基数
     */
    public volatile long electionTime=15*1000;
    /**
     * 上次选举的时间
     */
    public volatile long preElectionTime=0;
    /**
     * 上次心跳时间戳
     */
    public volatile long preHeartBeatTime=0;
    /**
     * 心跳间隔基数
     */
    public final long heartBeatTick=5*100;

    private HeartBeatTask heartBeatTask=new HeartBeatTask();


    /**
     * 节点状态信息
     */
    public volatile  int status= NodeStatus.FOLLOWER;

    public PeerSet peerSet;

    volatile  boolean running=false;

    /* ============ 所有服务器上持久存在的 ============= */

    /** 服务器最后一次知道的任期号（初始化为 0，持续递增） */
    volatile long currentTerm = 0;
    /**  当前获取选票的候选人的ID*/
    volatile String votedFor;

    LogModule logModule;


    /* ============ 所有服务器上经常修改的 ============= */

    /** 已知的最大的已经被提交的日志条目的索引值 */
    volatile long commitIndex;

    /** 最后被应用到状态机的日志条目索引值（初始化为 0，持续递增) */
    volatile long lastApplied = 0;

    /* ========== 在领导人里经常改变的(选举后重新初始化) ================== */

     /** 对于每一个服务器，需要发送给他的下一个日志条目的索引值（初始化为领导人最后索引值加一） */
    Map<Peer, Long> nextIndexs;

    /** 对于每一个服务器，已经复制给他的日志的最高索引值 */
    Map<Peer, Long> matchIndexs;

   /* =========================== */

    public NodeConfig config;

    public RpcService rpcServer;

    public RpcClient rpcClient = new DefaultRpcClient();

    public StateMachine stateMachine;


    /**
     * 一致性模块实现
     */

    Consensus consensus;
    ClusterMembershipChanges delegate;

    private static volatile DefaultNode INSTANCE ;
    /* ================== */

    private DefaultNode() {
    }

    public static DefaultNode getInstance() {
        if(INSTANCE==null){
            synchronized (DefaultNode.class){
                if(INSTANCE==null){
                    INSTANCE=new DefaultNode();
                }
                return INSTANCE;
            }
        }
        return INSTANCE;
    }

    // 初始化


    @Override
    public void init() throws Throwable {
        running = true;
        rpcServer.init();
        rpcClient.init();

        consensus = new DefaultConsensus(this);
        delegate = new ClusterMembershipChangesImpl(this);

        RaftThreadPool.scheduleWithFixedDelay(,500);







    }
    @Override
    public void setConfig(NodeConfig config){
        this.config = config;
        stateMachine = StateMachineSaveType.getForType(config.getStateMachineSaveType()).getStateMachine();
        logModule = DefaultLogModule.getInstance();

        peerSet=PeerSet.getInstance();
        for(String s:config.getPeerAddrs()){
            Peer peer=new Peer(s);
            peerSet.addPeer(peer);
            if(s.equals("localhost:"+config.getSelfPort()));
            {
                peerSet.setSelf(peer);
            }
        }

        // 将自己的port注册为server
        rpcServer=new DefaultRpcServiceImpl(config.selfPort,this);


    }

    class HeartBeatTask implements  Runnable{

        @Override
        public void run() {
            if(status!=NodeStatus.LEADER){
                return ;
            }
            long current=System.currentTimeMillis();
            if(current-preHeartBeatTime<heartBeatTick){
                return;
            }
            log.info("=============== NextIndex ===============");
            for(Peer peer:peerSet.getPeersWithOutSelf()){
                log.info("Peer {} nextIndex={}",peer.getAddress(),nextIndexs.get(peer));

            }
            preHeartBeatTime=System.currentTimeMillis();

            for(Peer peer:peerSet.getPeersWithOutSelf()){
                AentryParam param=AentryParam.builder()
                        .entries(null)
                        .leaderId(peerSet.getSelf().getAddress())
                        .serverId(peer.getAddress())
                        .term(currentTerm)
                        .leaderCommit(commitIndex)
                        .build();

                Request request=new Request(Request.A_ENTRIES,param,peer.getAddress());

                RaftThreadPool.execute(()->{
                    try{
                        AentryResult result=getRpcClient().send(request);
                        long term=result.getTerm();
                        if(term>currentTerm){
                            log.error("self will become follower, he's term : {}, my term : {}", term, currentTerm);
                            currentTerm = term;
                            votedFor = "";
                            status = NodeStatus.FOLLOWER;
                        }

                    }catch (Exception e){
                        log.error("HeartBeatTask RPC Fail, request URL : {} ", request.getUrl());

                    }
                },false);

            }

        }
    }

    /**
     * 选举
     * 1. 当转变为candidate开始选举
     * （1）自增当前的任期号
     * （2）给自己投票
     * （3）重置选举超时计时器
     * （4）发起请求投票的RPC给其他所有服务器
     * 2. 如果收到大部分的选票则变成leader
     * 3. 如果收到来自新leader的附加日志，则转变为follower
     * 4. 如果选举过程超时，再发起一次选举
     */
    class ElectionTask implements Runnable{

        @Override
        public void run() {
            if(status==NodeStatus.LEADER){
                return;
            }
            long current=System.currentTimeMillis();
            electionTime=electionTime+ ThreadLocalRandom.current().nextInt(50);
            // 如果选举时间距离上次选举时间过短，则不进行选举
            if(current-preElectionTime<electionTime){
                return ;
            }
            status=NodeStatus.CANDIDATE;
            log.error("node {} will become CANDIDATE and start election leader, current term : [{}], LastEntry : [{}]",
                    peerSet.getSelf(), currentTerm, logModule.getLast());
            preElectionTime=System.currentTimeMillis()+ThreadLocalRandom.current().nextInt(200) + 150;

            currentTerm+=1;

            votedFor=peerSet.getSelf().getAddress();
            List<Peer> peers = peerSet.getPeersWithOutSelf();
            ArrayList<Future<RvoteResult>> futureArrayList=new ArrayList<>();

            log.info("peerList size : {}, peer list content : {}", peers.size(), peers);

            // 向其他节点发送投票请求







        }
    }

    /**
     * 日志复制 将日志复制到其他的机器上
     * 只有leader才有权力这么做
     */
    public Future<Boolean> replication(Peer peer,LogEntry logEntry){
        return RaftThreadPool.submit(()->{
            long start=System.currentTimeMillis();
            long end=start;

            while(end-start<20*1000L){
                AentryParam param=AentryParam.builder().build();
                param.setTerm(currentTerm);
                param.setServerId(peer.getAddress());
                param.setLeaderId(peerSet.getSelf().getAddress());
                param.setLeaderCommit(commitIndex);

                Long nextIndex=nextIndexs.get(peer);
                LinkedList<LogEntry> logEntries=new LinkedList<>();
                if(logEntry.getIndex()>=nextIndex){
                    // 复制从nextIndex到logEntry的index之间的日志
                    for(long i=nextIndex;i<=logEntry.getIndex();i++){
                        LogEntry l=logModule.read(i);
                        if(l!=null){
                            logEntries.add(l);
                        }
                    }
                }
                else{
                    // 只复制当前日志
                    logEntries.add(logEntry);
                }
                //
                LogEntry prelog=getPreLog(logEntries.getFirst());
                param.setPreLogTerm(prelog.getTerm());
                param.setPreLogIndex(prelog.getIndex());

                param.setEntries(logEntries.toArray(new LogEntry[0]));

                Request request=Request.builder()
                        .cmd(Request.A_ENTRIES)
                        .obj(param)
                        .url(peer.getAddress())
                        .build();

                try {
                    AentryResult result=getRpcClient().send(request);
                    if(result==null)
                    {
                        return false;
                    }
                    if(result.isSuccess()){
                        log.info("append follower entry success , follower=[{}], entry=[{}]", peer, param.getEntries());
                        nextIndexs.put(peer, logEntry.getIndex() + 1);
                        matchIndexs.put(peer, logEntry.getIndex());


                    }
                    else {
                        // 对方任期比自己大
                        if(result.getTerm()>currentTerm){
                            log.warn("follower [{}] term [{}] than more self, and my term = [{}], so, I will become follower",
                                    peer, result.getTerm(), currentTerm);
                            currentTerm=result.getTerm();
                            status=NodeStatus.FOLLOWER;
                            return false;
                        }
                        // 对方日期没有自己大却失败了，说明term不对或者index不对
                        else{

                            // 减少nextIndex
                            if(nextIndex==0){
                                nextIndex=1L;

                            }
                            nextIndexs.put(peer, nextIndex - 1);

                            log.warn("follower {} nextIndex not match, will reduce nextIndex and retry RPC append, nextIndex : [{}]", peer.getAddr(),
                                    nextIndex);
                            // 重来, 直到成功

                        }

                    }
                    end=System.currentTimeMillis();

                }
                catch (Exception e){
                    log.warn(e.getMessage(),e);
                    return false;

                }

            }
            // 超时
            return false;
        });

    }

    /**
     * 获取当前日志的前一个日志
     * @param logEntry
     * @return
     */
    private LogEntry getPreLog(LogEntry logEntry){
        LogEntry entry=logModule.read(logEntry.getIndex()-1);
        if(entry==null){
            log.info("get preLog is null , parameter logEntry : {}",logEntry);
            entry = LogEntry.builder().index(0L).term(0).command(null).build();

        }
        return entry;
    }



}
