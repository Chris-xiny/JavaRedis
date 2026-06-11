package com.chrisxin.test;

import com.chrisxin.util.JedisConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Map;

public class JedisTest {
    private Jedis jedis;

    @BeforeEach
    public void init() {
        // 连接本地的 Redis 服务
        //jedis = new Jedis("192.168.100.128", 6379);
        jedis= JedisConnectionFactory.getJedis();
        // 设置密码
        jedis.auth("000000");
        // 选择数据库
        jedis.select(0);
    }

    @Test
    public void testString() {
        String result = jedis.set("name", "Mike");
        System.out.println(result);
        String name = jedis.get("name");
        System.out.println(name);
    }

    @Test
    public void testHash() {
        jedis.hset("user:1","name","张三");
        jedis.hset("user:1", "age", "18");
        Map<String, String> map = jedis.hgetAll("user:1");
        System.out.println(map);

    }

    @AfterEach
    public void Destroy() {
        if (jedis != null) {
            jedis.close();
        }
    }
}
