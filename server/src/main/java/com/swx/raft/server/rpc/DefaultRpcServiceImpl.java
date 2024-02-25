package com.swx.raft.server.rpc;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.RpcServer;
import com.swx.raft.common.entity.AentryParam;
import com.swx.raft.common.entity.ClientKVReq;
import com.swx.raft.common.entity.Peer;
import com.swx.raft.common.entity.RvoteParam;
import com.swx.raft.common.rpc.Request;
import com.swx.raft.common.rpc.Response;
import com.swx.raft.server.changes.ClusterMembershipChanges;
import com.swx.raft.server.impl.DefaultNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultRpcServiceImpl implements RpcService{
    private final DefaultNode node;

    private final RpcServer rpcServer;

    public DefaultRpcServiceImpl(int port, DefaultNode node){
        rpcServer=new RpcServer(port,false,false);

        rpcServer.registerUserProcessor(new RaftUserProcessor<Request>() {
            @Override
            public Object handleRequest(BizContext bizContext, Request request) throws Exception {
                return handlerRequest(request);
            }
        });
        this.node = node;
    }

    @Override
    public Response<?> handlerRequest(Request request) {
        if (request.getCmd() == Request.R_VOTE) {
            return new Response<>(node.handlerRequestVote((RvoteParam) request.getObj()));
        } else if (request.getCmd() == Request.A_ENTRIES) {
            return new Response<>(node.handlerAppendEntries((AentryParam) request.getObj()));
        } else if (request.getCmd() == Request.CLIENT_REQ) {
            return new Response<>(node.handlerClientRequest((ClientKVReq) request.getObj()));
        } else if (request.getCmd() == Request.CHANGE_CONFIG_REMOVE) {
            return new Response<>(((ClusterMembershipChanges) node).removePeer((Peer) request.getObj()));
        } else if (request.getCmd() == Request.CHANGE_CONFIG_ADD) {
            return new Response<>(((ClusterMembershipChanges) node).addPeer((Peer) request.getObj()));
        }
        return null;


    }

    @Override
    public void init() throws Throwable {
        rpcServer.startup();
    }

    @Override
    public void destroy() throws Throwable {
        rpcServer.shutdown();
        log.info("destroy success");

    }
}
