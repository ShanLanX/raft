package com.swx.raft.common.entity;

public class NodeStatus {
   public static int FOLLOWER=0;
    public static int CANDIDATE=1;
    public static int LEADER=2;

    public enum Enum{
        FOLLOWER(0),CANDIDATE(1),LEADER(2);
        int code;
        Enum(int code){
            this.code=code;
        }
        public static Enum value(int i){
            for(Enum value:Enum.values()){
                if(value.code==i){
                    return value;
                }

            }
            return null;
        }
    }
}
