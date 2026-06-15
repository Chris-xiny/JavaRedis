package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result QueryById(Long id) {
        //从redis中查询
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在，返回
        if(StrUtil.isNotBlank(shopJson)){//isNotBlank()判断的是不为空且不为空串""
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //防止缓存穿透可能带来的问题
        if(shopJson!=null){//如果不为空，只能是空串，说明数据库没有该商铺，直接返回即可不在查询数据库
            return Result.fail("商户信息不存在");
        }
        //不存在，查询数据库
        Shop shop = getById(id);
        //数据库中不存在，写入空值防止缓存穿透并返回错误
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //数据库中存在，返回结果并写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回结果
        return Result.ok(shop);
    }

    /**
     * 根据id更改商铺信息
     * @param shop 商铺信息
     */
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("商铺id不能为空");
        }
        //1.先更新数据库
        updateById(shop);
        //2.再删除Redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();

    }
}
