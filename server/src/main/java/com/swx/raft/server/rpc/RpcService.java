package com.swx.raft.server.rpc;

import com.swx.raft.common.LifeCycle;
import com.swx.raft.common.rpc.Request;
import com.swx.raft.common.rpc.Response;

public interface RpcService extends LifeCycle {
    Response<?> handlerRequest(Request request);
}
