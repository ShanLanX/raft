package com.swx.raft.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class ClientKVAck {
    Object result;

    public  static  ClientKVAck ok(){
        return new ClientKVAck("ok");
    }
    public static  ClientKVAck fail(){
        return new ClientKVAck("fail");
    }





}
