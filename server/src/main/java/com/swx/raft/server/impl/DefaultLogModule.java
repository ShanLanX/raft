package com.swx.raft.server.impl;

import com.alibaba.fastjson.JSON;
import com.swx.raft.common.entity.LogEntry;
import com.swx.raft.server.LogModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Data
public class DefaultLogModule implements LogModule {
    public String dbDir;
    public String logsDir;

    public RocksDB logDb;

    public final static byte[] LAST_INDEX_KEY = "LAST_INDEX_KEY".getBytes();

    private ReentrantLock lock = new ReentrantLock();



    private DefaultLogModule(){
        if(dbDir==null){
            dbDir="./rocksDB-raft/" + System.getProperty("serverPort");

        }
        if(logsDir==null){
            logsDir=dbDir+"/logModule";
        }
        RocksDB.loadLibrary();
        Options options=new Options();
        options.setCreateIfMissing(true);
        boolean success=false;
        File file=new File(logsDir);

        if(!file.exists()){
            success=file.mkdirs();
        }
        if(success){
            log.warn("make a new dir : "+logsDir);

        }
        try{
            logDb=RocksDB.open(options,logsDir);

        }
        catch (RocksDBException e){

            log.warn(e.getMessage());

        }
    }
    public static DefaultLogModule getInstance() {
        return DefaultLogsLazyHolder.INSTANCE;
    }

    private static class DefaultLogsLazyHolder {

        private static final DefaultLogModule INSTANCE = new DefaultLogModule();
    }

    @Override
    public void init() throws Throwable {


    }

    @Override
    public void destroy() throws Throwable {
        logDb.close();
        log.info("destroy success");

    }

    @Override
    public void write(LogEntry logEntry) {
        boolean success = false;
        boolean result;
        try{
            result=lock.tryLock(3000, TimeUnit.MILLISECONDS);
            if(!result){
                throw new RuntimeException("write fail, tryLock fail.");
            }
            logEntry.setIndex(getLastIndex()+1);
            logDb.put(logEntry.getIndex().toString().getBytes(), JSON.toJSONBytes(logEntry));
        } catch (InterruptedException  | RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
          if(success){
              updateLastIndex(logEntry.getIndex());
          }
          lock.unlock();
        }


    }

    @Override
    public LogEntry read(Long index) {
        try {
            byte[] result=logDb.get(convert(index));
            if(result==null){
                return null;
            }
            return JSON.parseObject(result,LogEntry.class);

        }
         catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeOnStartIndex(Long startIndex) {
        boolean success=false;
        int count=0;
        boolean tryLock;
        try{
            tryLock=lock.tryLock(3000,TimeUnit.MILLISECONDS);
            if(!tryLock){
                throw new RuntimeException("tryLock fail, removeOnStartIndex fail");
            }
            for(long i=startIndex;i<=getLastIndex();i++){
                logDb.delete(String.valueOf(i).getBytes());
                count++;
            }
            success=true;
            log.warn("rocksDB removeOnStartIndex success, count={} startIndex={}, lastIndex={}", count, startIndex, getLastIndex());


        }
        catch (InterruptedException | RocksDBException e){
            throw  new RuntimeException(e);
        }
        finally {
            if(success){
                updateLastIndex(getLastIndex()-count);
            }
            lock.unlock();
        }

    }

    @Override
    public LogEntry getLast() {
        try {
            byte[] result=logDb.get(convert(getLastIndex()));
            if(result==null){
                return null;
            }
            return JSON.parseObject(result,LogEntry.class);
        }
        catch (RocksDBException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public Long getLastIndex() {
       byte[] lastIndex;
       try{
           lastIndex=logDb.get(LAST_INDEX_KEY);
           if(lastIndex==null){
               lastIndex="-1".getBytes();
           }
       }
       catch (RocksDBException e){
           throw new RuntimeException(e);
       }
       return Long.valueOf(new String(lastIndex));
    }

    private void updateLastIndex(Long index) {
        try {
            // overWrite
            logDb.put(LAST_INDEX_KEY, index.toString().getBytes());
        } catch (RocksDBException e)
        {
            throw new RuntimeException(e);
        }
    }
    private byte[] convert(Long key) {
        return key.toString().getBytes();
    }
}
