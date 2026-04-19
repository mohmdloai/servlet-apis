package com.loai.inventory.common;

import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisFactory {
  private static final Logger log = LoggerFactory.getLogger(RedisFactory.class);

  private RedisFactory() {}

  public static JedisPool build() {
    Properties props = new Properties();
    try (InputStream is =
        RedisFactory.class.getClassLoader().getResourceAsStream("redis.properties")) {
      if (is == null) throw new RuntimeException("redis.properties not found on classpath");
      props.load(is);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load redis.properties", e);
    }

    String host = props.getProperty("redis.host", "localhost");
    int port = Integer.parseInt(props.getProperty("redis.port", "6379"));
    int maxTotal = Integer.parseInt(props.getProperty("redis.maxTotal", "16"));
    int maxIdle = Integer.parseInt(props.getProperty("redis.maxIdle", "8"));
    int timeout = Integer.parseInt(props.getProperty("redis.timeout", "2000"));
    log.info("Initialising Redis connection pool → {}:{}", host, port);

    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(maxTotal);
    poolConfig.setMaxIdle(maxIdle);

    return new JedisPool(poolConfig, host, port, timeout);
  }
}
