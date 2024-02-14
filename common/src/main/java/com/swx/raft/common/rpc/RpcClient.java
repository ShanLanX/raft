package com.swx.raft.common.rpc;

import com.swx.raft.common.LifeCycle;

public interface RpcClient extends LifeCycle {
    /**
     *
     * @param request
     * @return
     * @param <R> 返回值泛型
     */
    <R> R send(Request request);

    /**
     *
     * @param request
     * @param timeout 超时时间设置
     * @return
     * @param <R>
     */
    <R> R send(Request request,int timeout);
}
