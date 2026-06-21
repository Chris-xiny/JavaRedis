package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static java.lang.Thread.sleep;

//这是Spring提供的SimpleAsyncTaskExecutor默认兜底线程池
//每提交 1 个任务就新建 1 个线程，无池化，因此仅在该项目测试用
@Slf4j
@Component
public class AsyncTaskUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    //Redis缓存刷新线程池
    @Async("cacheExecutor")
    public <R, Id> void rebuildCacheAsync(Id id, Class<R> type, Function<Id, R> dbFallBack, String lockKey) {
        try {
            sleep(3000);
            R r = dbFallBack.apply(id);
            RedisData redisData = new RedisData(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL), r);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            // 无论重建是否成功，保证锁一定会释放
            stringRedisTemplate.delete(lockKey);
        }
    }

    //订单处理线程池(基于Stream的消息队列版)
    @Async("voucherOrderExecutor")
    public void voucherOrderHandleAsync(IVoucherOrderService selfProxy) {
        String queueName = "stream.orders";
        while (true) {
            try {
                //获取消息队列中的订单信息
                List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().
                        read(Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().
                                        count(1).
                                        block(Duration.ofSeconds(2)),
                                StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                //判断消息是否获取成功
                if (read == null || read.isEmpty()) {
                    //获取失败：下一轮循环获取
                    continue;
                }
                //成功：处理订单
                //解析消息中的订单信息
                MapRecord<String, Object, Object> entries = read.get(0);
                Map<Object, Object> value = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), false);
                selfProxy.voucherOrderHandle(voucherOrder);
                //ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
            } catch (Exception e) {
                log.error("订单处理异常!", e);
                //处理异常消息
                selfProxy.handlePendingList(queueName);
            }
        }
    }

    //订单处理线程池(阻塞队列版)
    /*@Async("voucherOrderExecutor")
    public void voucherOrderHandleAsync(BlockingQueue<VoucherOrder> blockingDeque,IVoucherOrderService selfProxy) {
        while(true){
            try{
                VoucherOrder order = blockingDeque.take();
                selfProxy.voucherOrderHandle(order);
            } catch (Exception e) {
                log.error("订单处理异常!",e);
            }
        }
    }*/

    //单元测试线程池
    @Async("taskExecutor")
    public <R, S> void Task(Function<S, R> task, S s) {
        task.apply(s);
    }
}