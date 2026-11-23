package com.example.aichat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步上传执行器：用守护线程池处理大文件解析+索引。
 *
 * <p>非侵入：独立的线程池，不改动 Spring 默认任务执行器；
 * 实际处理逻辑回调 FileUploadService.processSync（含完整任务状态更新）。
 * 用 @Lazy 注入打破 FileUploadService ↔ 本类的循环依赖。</p>
 */
@Component
public class AsyncUploadExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncUploadExecutor.class);
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "upload-worker");
        t.setDaemon(true);
        return t;
    });

    private final FileUploadService uploadService;

    public AsyncUploadExecutor(@Lazy FileUploadService uploadService) {
        this.uploadService = uploadService;
    }

    public void submit(String taskId, byte[] bytes, String filename, String ext, String mime,
                       String hash, String user, String tenant) {
        pool.submit(() -> {
            try {
                log.info("异步上传开始 taskId={} filename={} size={}", taskId, filename, bytes.length);
                uploadService.processSync(bytes, filename, ext, mime, hash, user, tenant, taskId);
            } catch (Exception e) {
                log.error("异步上传失败 taskId={} reason={}", taskId, e.getMessage());
            }
        });
    }
}
