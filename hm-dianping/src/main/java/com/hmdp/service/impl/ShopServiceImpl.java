package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static java.lang.Thread.sleep;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Async("taskExecutor")
    public void asyncRebuildCache(Long id, String lockKey) {
        //异步刷新缓存
        rebuildShopCacheWithExpireSeconds(id);
        //释放锁
        unLock(lockKey);
    }

    /**
     * 根据id查询商铺信息
     *
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result QueryById(Long id) {
        //用互斥锁解决缓存穿透
        //Shop shop=queryWithMutex(id);
        //用逻辑过期解决缓存穿透
        Shop shop = queryWithLogicExpire(id);
        if (shop == null) {
            return Result.fail("商户信息不存在");
        }
        return Result.ok(shop);
    }

    private Shop queryWithLogicExpire(Long id) {
        //1.从redis中查询
        String key = CACHE_SHOP_KEY + id;
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
        String lockKey = LOCK_SHOP_KEY + id;
        //2.存在，返回
        if (StrUtil.isNotBlank(redisDataJson)) {//isNotBlank()判断的是不为空且不为空串""
            RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
            Shop shop = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                //如果逻辑过期时间未过期，直接返回信息
                return shop;
            } else {
                //如果过期了，通过判断获取锁是否成功来决定是否需要异步重建缓存，无论怎样最后都返回旧数据
                if (!getLock(lockKey)) {
                    //失败,说明已经有线程处理，直接返回旧数据
                    return shop;
                }
                //成功就异步刷新,并将锁交给新线程
                asyncRebuildCache(id, lockKey);
                return shop;
            }
        }
        //3.1不存在,循环等待获取结果或自己查询数据库
        while (true) {
            //没有就尝试加锁自己处理
            boolean flag = getLock(lockKey);
            try {
                if (!flag) {
                    //4.3获取锁失败,说明已经有线程处理，睡一会再二次检查缓存
                    sleep(50);
                    //如果已经存在，说明已经有线程刚刷新了缓存,二次缓存检查到数据就直接返回数据
                    Shop shop = isRedisDataExistInRedis(key);
                    if (shop == null) {
                        continue;
                    }
                    //直接返回
                    return shop;
                } else {
                    //成功获取锁,二次检查如果已经存在，说明已经有线程刚刷新了缓存,直接返回数据
                    Shop shop = isRedisDataExistInRedis(key);
                    //没有就查询数据库并重建缓存,然后返回新数据
                    if (shop == null) {
                        return rebuildShopCacheWithExpireSeconds(id);
                    }
                    //直接返回
                    return shop;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException();
            } finally {
                //如果成功获得锁则释放
                if (flag) {
                    unLock(lockKey);
                }
            }
        }
    }

    private Shop isRedisDataExistInRedis(String key) {
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(redisDataJson)) {
            RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
        }
        return null;
    }

    private Shop queryWithMutex(Long id) {
        while (true) {
            //1.从redis中查询
            String key = CACHE_SHOP_KEY + id;
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            Shop shop;
            //2.存在，返回
            if (StrUtil.isNotBlank(shopJson)) {//isNotBlank()判断的是不为空且不为空串""
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            //3.防止缓存穿透可能带来的问题
            if (shopJson != null) {//如果不为空，只能是空串，说明数据库没有该商铺，直接返回即可，不再查询数据库
                return null;
            }
            //4.实现缓存重建
            //4.1获取互斥锁
            String lockKey = LOCK_SHOP_KEY + id;
            boolean isLock = false;
            try {
                isLock = getLock(lockKey);
                //4.2判断获取是否成功
                if (!isLock) {
                    //4.3失败，等待并重新尝试获取
                    sleep(50);
                    continue;
                }
                //4.4成功获取锁后DoubleCheck缓存中是否存在数据(重复123步骤)，避免额外重建缓存
                shopJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(shopJson)) {
                    shop = JSONUtil.toBean(shopJson, Shop.class);
                    return shop;
                }
                if (shopJson != null) {
                    return null;
                }
                //模拟数据库业务时间比较长
                sleep(200);
                //4.5查询数据库
                shop = getById(id);

                //5.数据库中不存在，写入空值防止缓存穿透并返回错误
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                //数据库中存在，返回结果并写入redis
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                //返回结果
                return shop;
            } catch (InterruptedException e) {
                throw new RuntimeException();
            } finally {
                if (isLock) {
                    unLock(lockKey);
                }
            }
        }
    }

    private Shop rebuildShopCacheWithExpireSeconds(Long id) {
        Shop shop = getById(id);
        String shopWithExpireJson = JSONUtil.toJsonStr(new RedisData(LocalDateTime.now().plusSeconds(CACHE_SHOP_TTL), shop));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopWithExpireJson);
        if(shop==null){//如果是空数据，设置ttl防止堆积无效数据占用空间
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id,CACHE_NULL_TTL,TimeUnit.MINUTES);
        }
        return shop;
    }

    private boolean getLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据id更改商铺信息
     *
     * @param shop 商铺信息
     */
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        //1.先更新数据库
        updateById(shop);
        //2.再删除Redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();

    }
}
