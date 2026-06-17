package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Slf4j
@Component
public class TaskAsyncUtils {

    @Async("taskExecutor")
    public <R,S> void Task(Function<S,R> task,S s){
        task.apply(s);
    }
}
