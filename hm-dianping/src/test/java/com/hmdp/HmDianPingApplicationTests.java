package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.TaskAsyncUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;

@SpringBootTest
class HmDianPingApplicationTests {


    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private TaskAsyncUtils taskAsyncUtils;

    @Test
    void testIdWorker() throws InterruptedException {
        String keyPrefix="order";
        Long start =System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(300);
        for (int i = 0; i < 300; i++) {
            taskAsyncUtils.Task(key->{
                for (int j = 0; j < 100; j++) {
                    System.out.println(redisIdWorker.nextId(key));
                }
                latch.countDown();
                return 0;
            },keyPrefix);
        }
        latch.await();
        Long end =System.currentTimeMillis();
        System.out.println(end-start);
    }

}
