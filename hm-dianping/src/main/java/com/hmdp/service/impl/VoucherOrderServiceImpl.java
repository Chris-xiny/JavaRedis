package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.AsyncTaskUtils;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final RedisIdWorker redisIdWorker;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService selfProxy;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    @Resource
    private AsyncTaskUtils asyncTaskUtils;

    @Lazy
    @Autowired
    public VoucherOrderServiceImpl(RedisIdWorker redisIdWorker, ISeckillVoucherService seckillVoucherService, IVoucherOrderService selfProxy, StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.redisIdWorker = redisIdWorker;
        this.seckillVoucherService = seckillVoucherService;
        this.selfProxy = selfProxy;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private final BlockingQueue<VoucherOrder> blockingDeque=new ArrayBlockingQueue<>(1024*1024);

    @PostConstruct
    private void init(){
        //开启线程阻塞等待订单并处理
        asyncTaskUtils.voucherOrderHandleAsync(blockingDeque,selfProxy);
        asyncTaskUtils.voucherOrderHandleAsync(blockingDeque,selfProxy);
    }


    //优惠券秒杀优化版:用Redis配合lua脚本判断库存及一人一单
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断结果为0
        int r = result.intValue();
        if (r != 0) {
            //2.1.不为0，代表没有资格购买
            return Result.fail(r==1?"库存不足!":"不能重复购买!");
        }
        //2.2.为0，有购买资格，将订单信息保存到阻塞队列
        //3.创建订单
        VoucherOrder order = new VoucherOrder();
        //3.1.订单id
        Long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        //3.2.秒杀卷id
        order.setVoucherId(voucherId);
        //3.3.用户id
        order.setUserId(userId);
        //4.保存阻塞队列
        blockingDeque.add(order);
        //5.返回订单信息
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void voucherOrderHandle(VoucherOrder order) {
        //5.1.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", order.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足!");
            return;
        }
        //5.2.保存订单到数据库
        save(order);
    }

    //优惠券秒杀
    /*@Override
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
        //SimpleRedisLock lock=new SimpleRedisLock("order"+userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("禁止重复下单!");
        }
        try{
            return selfProxy.doSeckill(voucherId);
        }finally {
            lock.unlock();
        }
    }*/

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
