package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final RedisIdWorker redisIdWorker;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService selfProxy;
    private final StringRedisTemplate stringRedisTemplate;

    @Lazy
    @Autowired
    public VoucherOrderServiceImpl(RedisIdWorker redisIdWorker, ISeckillVoucherService seckillVoucherService, IVoucherOrderService selfProxy, StringRedisTemplate stringRedisTemplate) {
        this.redisIdWorker = redisIdWorker;
        this.seckillVoucherService = seckillVoucherService;
        this.selfProxy = selfProxy;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始!");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束!");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }
        SimpleRedisLock simpleRedisLock=new SimpleRedisLock("order"+userId,stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(1200);
        if(!isLock){
            return Result.fail("禁止重复下单!");
        }
        try{
            return selfProxy.doSeckill(voucherId);
        }finally {
            simpleRedisLock.unLock();
        }
    }

    @Transactional
    @Override
    public Result doSeckill(Long voucherId) {
        //一人一单检查
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("不能重复抢购");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足!");
        }
        //6.创建订单
        VoucherOrder order = new VoucherOrder();
        //6.1.订单id
        Long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        //6.2.秒杀卷id
        order.setVoucherId(voucherId);
        //6.3.用户id
        order.setUserId(userId);
        save(order);
        //7.返回订单id
        return Result.ok(orderId);
    }
}
