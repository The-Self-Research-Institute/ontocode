package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    
    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);
    
    @Bean(name = "owlParsingExecutor")
    public Executor owlParsingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("owl-parse-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated lane for building the desktop in-memory OWLAPI model.
     * Kept narrow (effectively serial) on purpose: a single large parse can use
     * gigabytes of heap, so running two at once risks OutOfMemoryError. Isolating
     * it from owlParsingExecutor also means a heavy model build never starves the
     * import pipeline (and vice versa). CallerRuns applies natural backpressure.
     */
    @Bean(name = "desktopModelExecutor")
    public Executor desktopModelExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("owl-model-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override 
    public Executor getAsyncExecutor() { 
        return owlParsingExecutor(); 
    }
    
    @Override 
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("Async method {} threw exception: {}", method, throwable.getMessage(), throwable);
        };
    }
}