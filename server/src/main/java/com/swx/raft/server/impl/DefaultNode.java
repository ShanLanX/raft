package com.swx.raft.server.impl;

import com.swx.raft.common.RaftRemoteException;
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
import com.swx.raft.server.util.LongConvert;
import lombok.Data;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

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
    private ElectionTask electionTask=new ElectionTask();

    private ReplicationFailQueueConsumer replicationFailQueueConsumer = new ReplicationFailQueueConsumer();

    private LinkedBlockingQueue<ReplicationFailModel> replicationFailQueue = new LinkedBlockingQueue<>(2048);




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

        RaftThreadPool.scheduleWithFixedDelay(heartBeatTask,500);
        RaftThreadPool.scheduleAtFixedRate(electionTask, 6000, 500);
        RaftThreadPool.execute(replicationFailQueueConsumer);
        LogEntry logEntry = logModule.getLast();
        if (logEntry != null) {
            currentTerm = logEntry.getTerm();
        }

        log.info("start success, selfId : {} ", peerSet.getSelf());










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

    @Override
    public RvoteResult handlerRequestVote(RvoteParam param) {
        log.warn("handlerRequestVote will be invoke , param :{}",param);
        return consensus.requestVote(param);
    }




    @Override
    public AentryResult handlerAppendEntries(AentryParam param) {
        log.warn("handlerAppendEntries will be invoke, param :{}",param);
        return consensus.appendEntries(param);
    }

    public ClientKVAck redirect(ClientKVReq request) {
        Request r = Request.builder()
                .obj(request)
                .url(peerSet.getLeader().getAddress())
                .cmd(Request.CLIENT_REQ).build();

        return rpcClient.send(r);
    }

    /**
     * 处理来自客户端的请求，客户端的请求包含一条被复制状态机执行的指令
     * 领导人把指令当作一条新的日志附加到日志上，然后并发的发起附加条目的RPC到其他跟随着者让他们复制这条日志
     * 当这条日志条目被安全的复制（下面会介绍），领导人会应用这条日志条目到它的状态机中然后把执行的结果返回给客户端。（有应用到状态机的过程）
     * 如果跟随者崩溃或者运行缓慢，再或者网络丢包，
     * 领导人会不断的重复尝试附加日志条目 RPCs （尽管已经回复了客户端）直到所有的跟随者都最终存储了所有的日志条目。
     * @param request
     * @return
     */

    @Override
    public synchronized ClientKVAck handlerClientRequest(ClientKVReq request) {
        log.warn("handlerClientRequest handler {} operation,  and key : [{}], value : [{}]",
                ClientKVReq.Type.value(request.getType()), request.getKey(), request.getValue());

        if(status!=NodeStatus.LEADER){
            log.warn("I not am leader , only invoke redirect method, leader addr : {}, my addr : {}",
                    peerSet.getLeader(), peerSet.getSelf().getAddress());
            return redirect(request);



        }
        if(request.getType()==ClientKVReq.GET){
            LogEntry logEntry=stateMachine.get(request.getKey());
            if(logEntry!=null){
                return new ClientKVAck(logEntry);
            }
            return new ClientKVAck(null);

        }
        LogEntry logEntry=LogEntry.builder()
                .command(Command.builder().key(request.getKey()).value(request.getValue()).build())
                .term(currentTerm)
                .build();

        // 提交到本地日志
        logModule.write(logEntry);
        log.info("write logModule success, logEntry info : {}, log index : {}", logEntry, logEntry.getIndex());
        final AtomicInteger success = new AtomicInteger(0);
        List<Future<Boolean>> futureList = new ArrayList<>();
        int count = 0;
        //  复制到其他机器
        for (Peer peer : peerSet.getPeersWithOutSelf()) {
            // TODO check self and RaftThreadPool
            count++;
            // 并行发起 RPC 复制.
            futureList.add(replication(peer, logEntry));
        }
        CountDownLatch latch=new CountDownLatch(futureList.size());
        List<Boolean> resultList=new CopyOnWriteArrayList<>();
        getRPCAppendResult(futureList,latch,resultList);
        try{
            latch.await(4000, MILLISECONDS);

        }catch (InterruptedException e){
            log.error(e.getMessage());
        }

        for (Boolean aBoolean : resultList) {
            if (aBoolean) {
                success.incrementAndGet();
            }
        }


        // 如果存在一个满足N > commitIndex的 N，并且大多数的matchIndex[i] ≥ N成立，
        // 并且log[N].term == currentTerm成立，那么令 commitIndex 等于这个 N （5.3 和 5.4 节）
        List<Long> matchIndexList = new ArrayList<>(matchIndexs.values());
        // 小于 2, 没有意义
        int median = 0;
        if (matchIndexList.size() >= 2) {
            Collections.sort(matchIndexList);
            median = matchIndexList.size() / 2;
        }
        Long N = matchIndexList.get(median);
        if (N > commitIndex) {
            LogEntry entry = logModule.read(N);
            if (entry != null && entry.getTerm() == currentTerm) {
                commitIndex = N;
            }
        }

        //  响应客户端(成功一半)
        if (success.get() >= (count / 2)) {
            // 更新
            commitIndex = logEntry.getIndex();
            //  应用到状态机
            getStateMachine().apply(logEntry);
            lastApplied = commitIndex;

            log.info("success apply local state machine,  logEntry info : {}", logEntry);
            // 返回成功.
            return ClientKVAck.ok();
        } else {
            // 回滚已经提交的日志.
            logModule.removeOnStartIndex(logEntry.getIndex());
            log.warn("fail apply local state  machine,  logEntry info : {}", logEntry);
            // TODO 不应用到状态机,但已经记录到日志中.由定时任务从重试队列取出,然后重复尝试,当达到条件时,应用到状态机.
            // 这里应该返回错误, 因为没有成功复制过半机器.
            return ClientKVAck.fail();
        }





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
            for(Peer peer:peerSet.getPeersWithOutSelf()){
                futureArrayList.add(RaftThreadPool.submit(()->{
                    long lastTerm=0L;
                    LogEntry last=logModule.getLast();
                    if(last!=null){
                        lastTerm=last.getTerm();
                    }
                    RvoteParam param=RvoteParam.builder()
                            .term(currentTerm)
                            .candidateId(peerSet.getSelf().getAddress())
                            .lastLogTerm(lastTerm)
                            .lastLogIndex(LongConvert.convert(logModule.getLastIndex()))
                            .build();
                    Request request=Request.builder()
                            .cmd(Request.R_VOTE)
                            .obj(param)
                            .url(peer.getAddress())
                            .build();

                    try {
                        return getRpcClient().<RvoteResult>send(request);
                    }
                    catch (RaftRemoteException e){
                        log.error("ElectionTask RPC Fail , URL : " + request.getUrl());
                        return null;
                    }

                }));

            }
            AtomicInteger success2=new AtomicInteger(0);
            CountDownLatch latch=new CountDownLatch(futureArrayList.size());
            for(Future<RvoteResult> future:futureArrayList){
                RaftThreadPool.submit(()->{
                    try{
                        RvoteResult result=future.get(3000, TimeUnit.MILLISECONDS);
                        if(result==null)
                            return -1;
                        boolean isVoteGranted=result.isVoteGranted();
                        if(isVoteGranted){
                            success2.incrementAndGet();
                        }
                        else {
                            long resTerm=result.getTerm();
                            // xQS 为什么要更新当前任期
                            if(resTerm>=currentTerm){
                                currentTerm=resTerm;

                            }
                        }
                        return 0;

                    }
                    catch (Exception e){
                        log.error("future get exception , e :  ",e);
                        return -1;


                    }
                    finally {
                        latch.countDown();
                    }

                });
            }

            try{
                latch.await(3500,TimeUnit.MILLISECONDS);


            }
            catch (InterruptedException e){
                log.warn("InterruptedException By Master election Task");
            }

            int success= success2.get();
            log.info("node {} maybe become leader , success count = {} , status : {}", peerSet.getSelf(), success, NodeStatus.Enum.value(status));
            // 如果投票期间,有其他服务器发送 appendEntry , 就可能变成 follower ,这时,应该停止.
            if (status == NodeStatus.FOLLOWER) {
                return;
            }
            // 加上自身. 如果
            if (success >= peers.size() / 2) {
                log.warn("node {} become leader ", peerSet.getSelf());
                status = NodeStatus.LEADER;
                peerSet.setLeader(peerSet.getSelf());
                votedFor = "";
                becomeLeaderToDoThing();
            } else {
                // else 重新选举
                votedFor = "";
            }
            // 再次更新选举时间
            preElectionTime = System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(200) + 150;










        }
    }

    /**
     * 日志复制失败队列，等待被消费
     */
    class ReplicationFailQueueConsumer implements Runnable {
        long intervalTime=1000*60;

        @Override
        public void run() {
            while (running){
                try{
                    ReplicationFailModel model = replicationFailQueue.poll(1000, MILLISECONDS);
                    if(model==null){
                        continue;
                    }
                    if(status!=NodeStatus.LEADER){
                        // xQs 是否应该被清空
                        replicationFailQueue.clear();
                        continue;
                    }
                    log.warn("replication Fail Queue Consumer take a task, will be retry replication, content detail : [{}]", model.logEntry);
                    long offerTime = model.offerTime;

                    // 元素offer时间与当前时间只差超过间隔
                    if (System.currentTimeMillis() - offerTime > intervalTime) {
                        log.warn("replication Fail event Queue maybe full or handler slow");
                    }
                    Callable callable = model.callable;
                    Future<Boolean> future = RaftThreadPool.submit(callable);
                    Boolean r = future.get(3000, MILLISECONDS);
                    // 重试成功.
                    if (r) {
                        // 可能有资格应用到状态机.
                        tryApplyStateMachine(model);
                    }






                }
                catch (InterruptedException e) {
                    // ignore
                } catch (ExecutionException | TimeoutException e) {
                    log.warn(e.getMessage());
                }

            }

        }
    }



    /**
     * 初始化所有的nextIndex为当前最后一条日志的index+1，如果下次RPC时，跟随着和leader不一致就会导致失败
     * 那么leader尝试递减nextIndex 并进行重试，试图达到最终一致
     *
     */

    private  void becomeLeaderToDoThing(){

        nextIndexs=new ConcurrentHashMap<>();
        matchIndexs=new ConcurrentHashMap<>();
        for(Peer peer:peerSet.getPeersWithOutSelf()){
            nextIndexs.put(peer, logModule.getLastIndex()+1);
            matchIndexs.put(peer,0L);
        }
        //
        //  创建空白日志并提交，用于处理前任领导未提交的日志，可在一致性模块中的appendEntry那里看到
        LogEntry logEntry=LogEntry.builder()
                .command(null)
                .term(currentTerm)
                .build();

        //  提交到的本地日志 这里的处理会让logEntry获取到logIndex
        logModule.write(logEntry);

        log.info("write logModule success, logEntry info : {}, log index : {}", logEntry, logEntry.getIndex());
        final AtomicInteger success=new AtomicInteger(0);
        List<Future<Boolean>> futureList=new ArrayList<>();
        int count=0;
        // 复制到其他机器
        for(Peer peer:peerSet.getPeersWithOutSelf()){
            count++;
            futureList.add(replication(peer,logEntry));
        }

        CountDownLatch latch=new CountDownLatch(futureList.size());
        List<Boolean> resultList=new CopyOnWriteArrayList<>();

        getRPCAppendResult(futureList,latch,resultList);

        try{
            latch.await(4000,TimeUnit.MILLISECONDS);
        }
        catch (Exception e){
            log.error(e.getMessage());
        }

        for (Boolean aBoolean : resultList) {
            if (aBoolean) {
                success.incrementAndGet();
            }
        }

        // 如果存在一个满足N > commitIndex的 N，并且大多数的matchIndex[i] ≥ N成立，
        // 并且log[N].term == currentTerm成立，那么令 commitIndex 等于这个 N （5.3 和 5.4 节）

        List<Long> matchIndexList = new ArrayList<>(matchIndexs.values());

        int median=0;
        while(matchIndexList.size()>=2){
            Collections.sort(matchIndexList);
            median=matchIndexList.size()/2;
        }
        Long N=matchIndexList.get(median);
        if(N>commitIndex){
            LogEntry entry=logModule.read(N);
            if(entry!=null&&entry.getTerm()==currentTerm){
                commitIndex=N;
            }
        }
        // 超越一半的节点成功
        if(success.get()>=(count/2)){

            commitIndex=logEntry.getIndex();
            // 应用到状态机 空日志会被忽略
            getStateMachine().apply(logEntry);
            lastApplied=commitIndex;
            log.info("success apply local state machine,  logEntry info : {}", logEntry);
        }
        else{

            // 回滚之前提交的日志
            logModule.removeOnStartIndex(logEntry.getIndex());
            log.warn("fail apply local state  machine,  logEntry info : {}", logEntry);

            // 无法提交空日志，让出领导者位置
            log.warn("node {} becomeLeaderToDoThing fail ", peerSet.getSelf());
            status = NodeStatus.FOLLOWER;
            peerSet.setLeader(null);
            votedFor = "";


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
                        // 对方任期没有自己大却失败了，说明term不对或者index不对
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

    /**
     * 获取rpc结果
     * @param futureList
     * @param latch
     * @param result
     */
    private void getRPCAppendResult(List<Future<Boolean>> futureList,CountDownLatch latch,List<Boolean> result){
        for(Future<Boolean> future:futureList){
            RaftThreadPool.execute(()->{
                try {
                    result.add(future.get(3000,TimeUnit.MILLISECONDS));
                } catch (Exception e){
                    log.error(e.getMessage());
                    result.add(false);
                }
                finally {
                    latch.countDown();
                }
            });

        }

    }

    // xQS 这里的作用是什么
    private void tryApplyStateMachine(ReplicationFailModel model){
        String success = stateMachine.getString(model.successKey);
        stateMachine.setString(model.successKey, String.valueOf(Integer.parseInt(success) + 1));

        String count = stateMachine.getString(model.countKey);

        if (Integer.parseInt(success) >= Integer.parseInt(count) / 2) {
            stateMachine.apply(model.logEntry);
            stateMachine.delString(model.countKey, model.successKey);
        }

    }



}
