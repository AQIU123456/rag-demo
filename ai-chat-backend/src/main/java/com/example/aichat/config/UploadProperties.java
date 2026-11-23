package com.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业级上传配置。
 *
 * <p>非侵入式：所有字段都有安全默认值，不配置也能启动；
 * 通过 application.yml 的 upload.* 覆盖。缺省值与现有行为一致（快速录入仍可用）。</p>
 */
@Data
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

    /** 单文件大小上限（字节），默认 50MB */
    private long maxFileSize = 50L * 1024 * 1024;

    /** 解析后纯文本最大长度（字符），默认 200000，超出截断并标记 */
    private int maxParsedTextLength = 200000;

    /** 原文件存储根目录（相对当前工作目录或绝对路径） */
    private String storageRoot = "data/uploads";

    /** 扩展名 + MIME 双重白名单，默认支持常见文档格式 */
    private List<String> allowedExtensions = new ArrayList<>(List.of(
            "txt", "md", "markdown", "json", "xml", "csv", "log",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"));

    private List<String> allowedMimeTypes = new ArrayList<>(List.of(
            "text/plain", "text/markdown", "application/json", "application/xml", "text/xml",
            "text/csv", "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    /** 是否启用魔数嗅探（不信任扩展名），默认开 */
    private boolean magicSniffEnabled = true;

    /** 是否启用秒传去重（sha256 命中直接返回已有文档），默认开 */
    private boolean dedupEnabled = true;

    /** 是否启用版本化（同文件名不同 hash 生成新版本），默认开 */
    private boolean versioningEnabled = true;

    /** 是否启用上传限流，默认开 */
    private boolean rateLimitEnabled = true;

    /** 限流：每分钟最大上传次数 */
    private int rateLimitPerMinute = 60;

    /** 是否启用异步上传（大文件走任务队列），默认开 */
    private boolean asyncEnabled = true;

    /** 超过该字节数走异步任务，小于则同步返回 */
    private long asyncThresholdBytes = 2L * 1024 * 1024;

    /** 病毒扫描器类型：noop（默认，无外部服务）/ clamav（预留） */
    private String virusScanner = "noop";

    /** 病毒扫描器是否阻断上传（默认阻断） */
    private boolean virusScanBlock = true;
}
