package com.wrbug.polymarketbot.config

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.lang.reflect.Method
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/**
 * 全局 @Async 线程池配置。
 *
 * 根因：项目仅用 @EnableAsync 而未提供 TaskExecutor，Spring 默认回退到 SimpleAsyncTaskExecutor，
 * 对每个异步任务新建线程且无上限。决策日志（落库/推送/快照投影）在退出活跃期高频触发时会导致线程数失控、
 * 拖高 2 核机器 CPU。这里提供一个有界线程池作为所有 @Async 的默认执行器：
 *  - 队列满后用 CallerRunsPolicy 回压到调用线程（决策日志本就允许变慢，不丢事件）。
 */
@Configuration
class AsyncConfig : AsyncConfigurer {

    private val logger = LoggerFactory.getLogger(AsyncConfig::class.java)

    @Bean(name = ["cryptoTailDecisionExecutor"])
    fun cryptoTailDecisionExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.queueCapacity = 1000
        executor.keepAliveSeconds = 60
        executor.setThreadNamePrefix("ct-async-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(10)
        executor.initialize()
        return executor
    }

    override fun getAsyncExecutor(): Executor = cryptoTailDecisionExecutor()

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        AsyncUncaughtExceptionHandler { ex: Throwable, method: Method, _: Array<Any?> ->
            logger.warn("异步任务执行异常: method=${method.name}, ${ex.message}")
        }
}
