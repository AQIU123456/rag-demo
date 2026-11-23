package com.example.aichat.service;

import com.example.aichat.config.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单固定窗口限流（按用户/租户维度），零新增依赖。
 *
 * <p>生产环境可替换为 Redis 分布式限流；此处为单机实现，满足演示与单体部署。</p>
 */
@Component
public class UploadRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UploadRateLimiter.class);
    private final UploadProperties props;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public UploadRateLimiter(UploadProperties props) {
        this.props = props;
    }

    /** 尝试获取一个上传配额 */
    public boolean tryAcquire(String key) {
        if (!props.isRateLimitEnabled()) return true;
        String k = key == null ? "anonymous" : key;
        long nowMinute = System.currentTimeMillis() / 60_000;
        Window w = windows.computeIfAbsent(k, x -> new Window(nowMinute, new AtomicInteger(0)));
        synchronized (w) {
            if (w.minute != nowMinute) {
                w.minute = nowMinute;
                w.count.set(0);
            }
            if (w.count.get() >= props.getRateLimitPerMinute()) {
                log.warn("上传限流触发 key={} count={} limit={}", k, w.count.get(), props.getRateLimitPerMinute());
                return false;
            }
            w.count.incrementAndGet();
            return true;
        }
    }

    private static class Window {
        long minute;
        AtomicInteger count;
        Window(long minute, AtomicInteger count) {
            this.minute = minute;
            this.count = count;
        }
    }
}
