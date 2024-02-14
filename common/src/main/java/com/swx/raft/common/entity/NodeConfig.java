package com.swx.raft.common.entity;

import lombok.Data;

import java.util.List;
@Data
public class NodeConfig {
    /**
     * 节点自身端口号
     */
    public int selfPort;
    /**
     * 其余节点的地址
     */
    public List<String> peerAddrs;
    /**
     * 快照存储类型
     */
    public String stateMachineSaveType;
}
