package com.netpulse.monitoring.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ExecutorConfig {
    /**
     * Virtual threads make blocking network I/O cheap: a parked thread costs a few KB, not a 1 MB OS stack.
     * They do NOT limit how much work runs at once, so the scheduler adds a semaphore on top.
     * shutdownNow interrupts running checks at shutdown (close() would wait for them indefinitely).
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService monitoringExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
