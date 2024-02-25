package com.swx.raft.server.impl;

import com.alibaba.fastjson.JSON;
import com.swx.raft.common.entity.Command;
import com.swx.raft.common.entity.LogEntry;
import com.swx.raft.server.StateMachine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
@Slf4j
public class RedisStateMachine implements StateMachine {
    private JedisPool jedisPool;

    private RedisStateMachine() {
        init();
    }

    public static RedisStateMachine getInstance() {
        return RedisStateMachineLazyHolder.INSTANCE;
    }

    private static class RedisStateMachineLazyHolder {

        private static final RedisStateMachine INSTANCE = new RedisStateMachine();
    }

    @Override
    public void init() {
        GenericObjectPoolConfig redisConfig = new GenericObjectPoolConfig();
        redisConfig.setMaxTotal(100);
        redisConfig.setMaxWaitMillis(10 * 1000);
        redisConfig.setMaxIdle(100);
        redisConfig.setTestOnBorrow(true);
        // todo config
        jedisPool = new JedisPool(redisConfig, System.getProperty("redis.host", "127.0.0.1"), 6379);
    }

    @Override
    public void destroy() throws Throwable {
        jedisPool.close();
        log.info("destroy success");
    }

    @Override
    public void apply(LogEntry logEntry) {
        try (Jedis jedis = jedisPool.getResource()) {
            Command command = logEntry.getCommand();
            if (command == null) {
                // 忽略空日志
                log.warn("insert no-op log, logEntry={}", logEntry);
                return;
            }
            String key = command.getKey();
            jedis.set(key.getBytes(), JSON.toJSONBytes(logEntry));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public LogEntry get(String key) {
        LogEntry result = null;
        try (Jedis jedis = jedisPool.getResource()) {
            result = JSON.parseObject(jedis.get(key), LogEntry.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public String getString(String key) {
        String result = null;
        try (Jedis jedis = jedisPool.getResource()) {
            result = jedis.get(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public void setString(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void delString(String... keys) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(keys);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
