package com.swx.raft.common.rpc;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class DefaultRpcClient implements RpcClient{
    private final static com.alipay.remoting.rpc.RpcClient CLIENT = new com.alipay.remoting.rpc.RpcClient();

    @Override
    public void init() throws Throwable {
        CLIENT.init();

    }

    @Override
    public void destroy() throws Throwable {
        CLIENT.shutdown();
        log.info("Client shutdown");
    }

    @Override
    public <R> R send(Request request) {
        return send(request,(int) TimeUnit.SECONDS.toMillis(5));
    }

    @Override
    public <R> R send(Request request, int timeout) {
       Response<R> result = null;
       try {
           result = (Response<R>) CLIENT.invokeSync(request.getUrl(), request, timeout);
           return result.getResult();
       }
       catch (Exception e){
           e.printStackTrace();
       }
       return null;

    }
}
