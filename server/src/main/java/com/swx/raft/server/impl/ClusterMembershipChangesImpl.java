package com.swx.raft.server.impl;

import com.swx.raft.common.entity.LogEntry;
import com.swx.raft.common.entity.NodeStatus;
import com.swx.raft.common.entity.Peer;
import com.swx.raft.common.rpc.Request;
import com.swx.raft.server.changes.ClusterMembershipChanges;
import com.swx.raft.server.changes.Result;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClusterMembershipChangesImpl implements ClusterMembershipChanges {


    private final DefaultNode defaultNode;

    public ClusterMembershipChangesImpl(DefaultNode node){
        this.defaultNode=node;
    }


    @Override
    public synchronized Result addPeer(Peer newPeer) {
        if(defaultNode.peerSet.getPeers().contains(newPeer)){
            return new Result();
        }
        defaultNode.peerSet.getPeersWithOutSelf().add(newPeer);

        if(defaultNode.status== NodeStatus.LEADER){
            // 保存节点的nextindex状态
            defaultNode.nextIndexs.put(newPeer,0L);
            // 已复制给当前节点的所有的日志
            defaultNode.matchIndexs.put(newPeer,0L);
            for(long i=0;i< defaultNode.logModule.getLastIndex();i++){
                LogEntry entry=defaultNode.logModule.read(i);
                if(entry!=null){
                    defaultNode.replication(newPeer,entry);
                }
            }
            // 将新节点的信息同步到其他节点
            for(Peer otherPeer:defaultNode.peerSet.getPeersWithOutSelf()){
                // xQS  同步方法
                Request request = Request.builder()
                        .cmd(Request.CHANGE_CONFIG_ADD)
                        .url(newPeer.getAddress())
                        .obj(newPeer)
                        .build();

                Result result = defaultNode.rpcClient.send(request);
                if (result != null && result.getStatus() == Result.Status.SUCCESS.getCode()) {
                    log.info("replication config success, peer : {}, newServer : {}", newPeer, newPeer);
                } else {
                    log.warn("replication config fail, peer : {}, newServer : {}", newPeer, newPeer);
                }

            }
        }
        return new Result();


    }

    @Override
    public synchronized Result removePeer(Peer oldPeer) {
        // xQS 这样做是否真的可以移除
        defaultNode.peerSet.getPeersWithOutSelf().remove(oldPeer);
        defaultNode.nextIndexs.remove(oldPeer);
        defaultNode.matchIndexs.remove(oldPeer);
        return new Result();


    }
}
