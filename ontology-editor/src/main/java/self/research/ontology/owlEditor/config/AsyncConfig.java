package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import self.research.ontology.owlEditor.service.Limit;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    
    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public AsyncConfig(CapacityProperties capacity) {
    }
    
    @Bean(name = "owlParsingExecutor")
    public AsyncTaskExecutor owlParsingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.OWL_PARSING_CORE_THREADS.value(), Limit.OWL_PARSING_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.OWL_PARSING_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.OWL_PARSING_QUEUE.value());
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
    /** Post-import OWLAPI hierarchy snapshot builds (index for cloud web UI). */
    @Bean(name = "hierarchyIndexExecutor")
    public Executor hierarchyIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.HIERARCHY_INDEX_CORE_THREADS.value(), Limit.HIERARCHY_INDEX_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.HIERARCHY_INDEX_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.HIERARCHY_INDEX_QUEUE.value());
        executor.setThreadNamePrefix("hierarchy-idx-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * DL Query reasoning lane — serial by default (max-concurrent=1) because each job
     * can load a full ontology + reasoner into heap. Isolated from import parsing.
     */
    @Bean(name = "dlQueryExecutor")
    public Executor dlQueryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.DL_QUERY_CORE_THREADS.value(), Limit.DL_QUERY_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.DL_QUERY_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.DL_QUERY_QUEUE.value());
        executor.setThreadNamePrefix("dl-query-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    @Bean(name = "desktopModelExecutor")
    public Executor desktopModelExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.DESKTOP_MODEL_CORE_THREADS.value(), Limit.DESKTOP_MODEL_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.DESKTOP_MODEL_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.DESKTOP_MODEL_QUEUE.value());
        executor.setThreadNamePrefix("owl-model-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Interactive OWLAPI warm lane — serves a user actively opening a file so the
     * parse never queues behind background bulk-upload warms on desktopModelExecutor.
     * Serial (1 thread): only one project is opened interactively at a time, and a
     * second concurrent parse would double heap pressure.
     */
    @Bean(name = "desktopInteractiveWarmExecutor")
    public Executor desktopInteractiveWarmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Limit.DESKTOP_WARM_THREADS.value());
        executor.setMaxPoolSize(Limit.DESKTOP_WARM_THREADS.value());
        executor.setQueueCapacity(Limit.DESKTOP_WARM_QUEUE.value());
        executor.setThreadNamePrefix("owl-warm-ui-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated lane for copy-on-switch draft graph copies (full SPARQL INSERT WHERE).
     * Serial by design: concurrent full-graph copies would double memory/IO pressure.
     */
    @Bean(name = "draftCopyExecutor")
    public Executor draftCopyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.DRAFT_COPY_CORE_THREADS.value(), Limit.DRAFT_COPY_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.DRAFT_COPY_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.DRAFT_COPY_QUEUE.value());
        executor.setThreadNamePrefix("draft-copy-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
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