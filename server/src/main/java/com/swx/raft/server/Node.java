package com.swx.raft.server;

import com.swx.raft.common.LifeCycle;
import com.swx.raft.common.entity.*;

public interface Node extends LifeCycle {
    /**
     * 设置配置文件
     * @param nodeConfig
     */
    void setConfig(NodeConfig nodeConfig);

    /**
     * 处理投票请求
     * @param param
     * @return
     */

     RvoteResult handlerRequestVote(RvoteParam param);

    /**
     * 处理附加日志请求
     * @param param
     * @return
     */

     AentryResult handlerAppendEntries(AentryParam param);

    /**
     * 处理客户端请求
     * @param request
     * @return
     */
    ClientKVAck handlerClientRequest(ClientKVReq request);




}
