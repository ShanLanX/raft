
package com.swx.raft.server.changes;


import com.swx.raft.common.entity.Peer;

/**
 *
 * 集群配置变更接口.
 *
 *
 */
public interface ClusterMembershipChanges {

    /**
     * 添加节点.
     * @param newPeer
     * @return
     */
    Result addPeer(Peer newPeer);

    /**
     * 删除节点.
     * @param oldPeer
     * @return
     */
    Result removePeer(Peer oldPeer);
}

