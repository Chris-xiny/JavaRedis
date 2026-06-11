package com.chrisxin;

import com.chrisxin.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class SpringDataRedisDemoApplicationTests {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testSaveUser() {
        //创建一个User对象
        User user = new User("胡歌",18);
        //手动序列化为json
        String json = objectMapper.writeValueAsString(user);
        stringRedisTemplate.opsForValue().set("user:2",json);
        System.out.println(stringRedisTemplate.opsForValue().get("user:2"));
    }

    @Test
    void testHash(){
        stringRedisTemplate.opsForHash().put("user:3","name","张三");
        stringRedisTemplate.opsForHash().put("user:3","age","18");

        System.out.println(stringRedisTemplate.opsForHash().entries("user:3"));
    }

}
