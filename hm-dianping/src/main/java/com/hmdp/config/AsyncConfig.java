package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("cacheExecutor")
    public Executor cacheExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);                 // 核心线程数
        executor.setMaxPoolSize(10);                 // 最大线程数
        executor.setQueueCapacity(50);              // 队列容量
        executor.setKeepAliveSeconds(20);            // 空闲线程存活时间（秒）
        executor.setThreadNamePrefix("async-cache-"); // 线程名前缀
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy()); // 拒绝策略
        executor.initialize();
        return executor;
    }
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);                // 核心线程数
        executor.setMaxPoolSize(50);                 // 最大线程数
        executor.setQueueCapacity(500);              // 队列容量
        executor.setKeepAliveSeconds(20);            // 空闲线程存活时间（秒）
        executor.setThreadNamePrefix("async-task-"); // 线程名前缀
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy()); // 拒绝策略
        executor.initialize();
        return executor;
    }
}
