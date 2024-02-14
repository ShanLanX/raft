package com.swx.raft.common.entity;

public class Peer {
    private final String address;

    public Peer(String address){
        this.address=address;

    }

    @Override
    public boolean equals(Object obj) {
      if(this==obj){
          return true;

      }
      if(obj==null||obj.getClass()!=this.getClass()){
          return false;
      }

      Peer peer=(Peer) obj;
      return address.equals(peer.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    @Override
    public String toString() {
        return "Peer{" +
                "address='" + address + '\'' +
                '}';
    }
}
