package com.hmdp.service;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static java.lang.Thread.sleep;

// 新建独立的异步缓存服务
@Slf4j
@Service
public class ShopCacheAsyncService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Async("taskExecutor")
    public <R,Id> void rebuildShopCacheAsync(Id id, Class<R> type, Function<Id,R> dbFallBack, String lockKey) {
        try {
            sleep(3000);
            R r = dbFallBack.apply(id);
            RedisData redisData = new RedisData(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL), r);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        }
        catch (InterruptedException e){
            throw  new RuntimeException();
        }finally {
            // 无论重建是否成功，保证锁一定会释放
            stringRedisTemplate.delete(lockKey);
        }
    }
}