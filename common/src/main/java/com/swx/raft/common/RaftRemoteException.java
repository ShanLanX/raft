package com.swx.raft.common;

public class RaftRemoteException extends RuntimeException{
    public RaftRemoteException(){
        super();
    }
    public RaftRemoteException(String message){
        super(message);
    }
    public RaftRemoteException(Throwable cause){
        super(cause);
    }
    public RaftRemoteException(String message,Throwable cause){
        super(message,cause);
    }

}
