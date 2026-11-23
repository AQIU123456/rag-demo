package com.example.aichat.service;

import com.example.aichat.config.UploadProperties;
import com.example.aichat.dto.DocumentResponse;
import com.example.aichat.dto.DocumentUploadRequest;
import com.example.aichat.dto.TaskStatusResponse;
import com.example.aichat.dto.UploadResponse;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.entity.UploadTask;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import com.example.aichat.repository.UploadTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件上传统一编排：校验 → 去重 → 存储 → 解析 → 入库 → 索引。
 *
 * <p>非侵入：复用 KnowledgeDocumentService.uploadPlainText 作为唯一入库出口，
 * 解析后的文本仍走既有 scheduleIndex 链路，不触碰分块/向量代码。</p>
 */
@Service
public class FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    private final UploadProperties props;
    private final FileStorageService storage;
    private final FileTypeDetector detector;
    private final DocumentParser parser;
    private final UploadRateLimiter limiter;
    private final VirusScanner virusScanner;
    private final KnowledgeDocumentRepository docRepository;
    private final UploadTaskRepository taskRepository;
    private final KnowledgeDocumentService documentService;
    private final AsyncUploadExecutor asyncExecutor;
    private final UploadAuditService auditService;

    public FileUploadService(UploadProperties props, FileStorageService storage, FileTypeDetector detector,
                             DocumentParser parser, UploadRateLimiter limiter, VirusScanner virusScanner,
                             KnowledgeDocumentRepository docRepository, UploadTaskRepository taskRepository,
                             KnowledgeDocumentService documentService, AsyncUploadExecutor asyncExecutor,
                             UploadAuditService auditService) {
        this.props = props;
        this.storage = storage;
        this.detector = detector;
        this.parser = parser;
        this.limiter = limiter;
        this.virusScanner = virusScanner;
        this.docRepository = docRepository;
        this.taskRepository = taskRepository;
        this.documentService = documentService;
        this.asyncExecutor = asyncExecutor;
        this.auditService = auditService;
    }

    /** 上传主入口 */
    public UploadResponse upload(MultipartFile file, String uploadedBy, String tenantId) {
        String user = defaultIfBlank(uploadedBy, "anonymous");
        String tenant = defaultIfBlank(tenantId, "default");

        // 1. 限流
        if (!limiter.tryAcquire(tenant + ":" + user)) {
            throw new IllegalStateException("上传过于频繁，请稍后重试（限流 " + props.getRateLimitPerMinute() + " 次/分钟）");
        }

        // 2. 基础校验
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > props.getMaxFileSize()) {
            throw new IllegalArgumentException("文件超过大小上限 " + props.getMaxFileSize() + " 字节");
        }

        String filename = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String declaredExt = detector.extOf(filename);

        // 3. 一次性读入内存：后续 sha256 / 解析 / 存储都复用这份 byte[]。
        //    魔数嗅探基于 byte[]，不调用 InputStream.mark/reset ——
        //    MultipartFile 底层流不支持标记重现，这正是 mark/reset not supported 的根因。
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败", e);
        }
        FileTypeDetector.Detected detected = detector.detect(bytes, filename, props.isMagicSniffEnabled());
        String ext = detected.ext();
        // 魔数识别为 zip/ole 时细分到具体 Office 类型
        if ("zip".equals(ext) || "ole".equals(ext)) {
            ext = detector.resolveOfficeType(ext, declaredExt);
        }
        validateWhitelist(ext, detected.mimeType());

        // 4. 病毒扫描
        VirusScanner.ScanResult scan = virusScanner.scan(bytes, filename);
        if (!scan.clean() && props.isVirusScanBlock()) {
            auditService.log("VIRUS_BLOCKED", user, tenant, filename, scan.detail());
            throw new IllegalStateException("文件未通过安全扫描：" + scan.detail());
        }

        // 5. sha256 + 秒传去重
        String hash = storage.sha256(bytes);
        if (props.isDedupEnabled()) {
            List<KnowledgeDocument> existing = docRepository.findByFileHashOrderByIdAsc(hash);
            if (!existing.isEmpty()) {
                KnowledgeDocument hit = existing.get(0);
                auditService.log("DEDUP_HIT", user, tenant, filename, "docId=" + hit.getId());
                return UploadResponse.builder()
                        .async(false).dedupHit(true)
                        .document(documentService.getDocumentById(hit.getId()))
                        .message("秒传命中，已返回已有文档")
                        .build();
            }
        }

        // 6. 异步 or 同步
        boolean useAsync = props.isAsyncEnabled() && file.getSize() >= props.getAsyncThresholdBytes();
        if (useAsync) {
            UploadTask task = createTask(hash, filename, file.getSize(), user, tenant);
            asyncExecutor.submit(task.getTaskId(), bytes, filename, ext, detected.mimeType(), hash, user, tenant);
            auditService.log("UPLOAD_ASYNC_ACCEPTED", user, tenant, filename, "taskId=" + task.getTaskId());
            return UploadResponse.builder()
                    .async(true).taskId(task.getTaskId())
                    .message("文件已接收，正在后台解析")
                    .build();
        }
        DocumentResponse doc = processSync(bytes, filename, ext, detected.mimeType(), hash, user, tenant, null);
        auditService.log("UPLOAD_SYNC_SUCCESS", user, tenant, filename, "docId=" + doc.getId());
        return UploadResponse.builder()
                .async(false).dedupHit(false).document(doc)
                .message("上传成功")
                .build();
    }

    /**
     * 同步处理核心（异步任务也复用此方法）。
     * 更新 task 进度（若传了 taskId）。
     */
    public DocumentResponse processSync(byte[] bytes, String filename, String ext, String mime,
                                        String hash, String user, String tenant, String taskId) {
        try {
            updateTask(taskId, "PROCESSING", 20, "存储原文件");
            String storagePath = storage.storeBytes(bytes, hash, ext);
            updateTaskStoragePath(taskId, storagePath);

            updateTask(taskId, "PROCESSING", 45, "解析文档内容");
            DocumentParser.ParseResult parsed = parser.parse(bytes, ext, mime);
            if ("FAILED".equals(parsed.status())) {
                throw new IllegalStateException("文档内容解析失败（可能加密或损坏）");
            }

            updateTask(taskId, "PROCESSING", 70, "写入知识库");
            String version = props.isVersioningEnabled() ? resolveVersion(filename) : "v1";
            String title = stripExt(filename);
            DocumentUploadRequest req = DocumentUploadRequest.builder()
                    .title(title)
                    .description("由文件导入：" + filename)
                    .content(parsed.text())
                    .fileType(ext)
                    .originalFilename(filename)
                    .mimeType(mime)
                    .fileHash(hash)
                    .storagePath(storagePath)
                    .version(version)
                    .source("file-upload")
                    .uploadedBy(user)
                    .tenantId(tenant)
                    .parseStatus(parsed.status())
                    .build();
            // 复用既有入库出口：自动触发 scheduleIndex（切分 + 向量）
            DocumentResponse doc = documentService.uploadPlainText(req);

            updateTask(taskId, "SUCCESS", 100, "完成", doc.getId());
            return doc;
        } catch (Exception e) {
            log.error("上传处理失败 filename={} taskId={} reason={}", filename, taskId, e.getMessage());
            updateTaskFailed(taskId, e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException("上传处理失败", e);
        }
    }

    /** 下载原文件：返回 Resource + 建议文件名；无原文件时返回 null */
    public FileDownload download(Long docId) {
        KnowledgeDocument doc = docRepository.findById(docId).orElse(null);
        if (doc == null || doc.getStoragePath() == null || doc.getStoragePath().isBlank()) {
            return null;
        }
        java.nio.file.Path p = storage.resolve(doc.getStoragePath());
        if (!java.nio.file.Files.exists(p)) {
            return null;
        }
        String name = doc.getOriginalFilename() != null ? doc.getOriginalFilename() : "document";
        return new FileDownload(p, name, doc.getMimeType());
    }

    /** 下载结果载体 */
    public record FileDownload(java.nio.file.Path path, String filename, String mimeType) {}

    /** 查询任务状态 */
    public TaskStatusResponse getTaskStatus(String taskId) {
        UploadTask t = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        return TaskStatusResponse.builder()
                .taskId(t.getTaskId()).status(t.getStatus()).progress(t.getProgress())
                .stage(t.getStage()).docId(t.getDocId()).errorMessage(t.getErrorMessage())
                .retryCount(t.getRetryCount()).createdAt(t.getCreatedAt()).updatedAt(t.getUpdatedAt())
                .build();
    }

    /** 失败重试：重新入队 */
    public void retryTask(String taskId) {
        UploadTask t = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        if (!"FAILED".equals(t.getStatus())) {
            throw new IllegalStateException("仅失败任务可重试，当前状态：" + t.getStatus());
        }
        t.setStatus("PENDING");
        t.setProgress(0);
        t.setErrorMessage(null);
        t.setRetryCount((t.getRetryCount() == null ? 0 : t.getRetryCount()) + 1);
        taskRepository.save(t);
        // 重新读取文件字节提交
        try {
            byte[] bytes = storage.readBytes(t.getStoragePath());
            asyncExecutor.submit(t.getTaskId(), bytes, t.getFilename(),
                    detector.extOf(t.getFilename()), detector.mimeOf(detector.extOf(t.getFilename())),
                    t.getFileHash(), t.getUploadedBy(), t.getTenantId());
        } catch (IOException e) {
            throw new IllegalStateException("重试失败：无法读取原文件", e);
        }
    }

    // ==================== 内部方法 ====================

    private void validateWhitelist(String ext, String mime) {
        if (!props.getAllowedExtensions().contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：" + ext);
        }
        // MIME 白名单做二次校验（魔数命中时 mime 来自魔数映射，可信）
        if (props.isMagicSniffEnabled() && !props.getAllowedMimeTypes().contains(mime)) {
            throw new IllegalArgumentException("文件 MIME 不在白名单：" + mime);
        }
    }

    private String resolveVersion(String filename) {
        List<KnowledgeDocument> versions = docRepository.findByOriginalFilenameOrderByVersionDesc(filename);
        int max = 1;
        for (KnowledgeDocument d : versions) {
            String v = d.getVersion();
            if (v != null && v.matches("v\\d+")) {
                max = Math.max(max, Integer.parseInt(v.substring(1)));
            }
        }
        return "v" + (max + 1);
    }

    private UploadTask createTask(String hash, String filename, long size, String user, String tenant) {
        UploadTask t = UploadTask.builder()
                .taskId(UUID.randomUUID().toString().replace("-", ""))
                .fileHash(hash).filename(filename).fileSize(size)
                .status("PENDING").progress(0).stage("排队中")
                .retryCount(0).uploadedBy(user).tenantId(tenant)
                .build();
        return taskRepository.save(t);
    }

    private void updateTaskStoragePath(String taskId, String storagePath) {
        if (taskId == null) return;
        taskRepository.findByTaskId(taskId).ifPresent(t -> {
            t.setStoragePath(storagePath);
            taskRepository.save(t);
        });
    }

    private void updateTask(String taskId, String status, int progress, String stage) {
        updateTask(taskId, status, progress, stage, null);
    }

    private void updateTask(String taskId, String status, int progress, String stage, Long docId) {
        if (taskId == null) return;
        taskRepository.findByTaskId(taskId).ifPresent(t -> {
            t.setStatus(status);
            t.setProgress(progress);
            t.setStage(stage);
            if (docId != null) t.setDocId(docId);
            taskRepository.save(t);
        });
    }

    private void updateTaskFailed(String taskId, String error) {
        if (taskId == null) return;
        taskRepository.findByTaskId(taskId).ifPresent(t -> {
            t.setStatus("FAILED");
            t.setErrorMessage(error == null ? "未知错误" : error.substring(0, Math.min(1000, error.length())));
            taskRepository.save(t);
        });
    }

    private static String stripExt(String filename) {
        int i = filename.lastIndexOf('.');
        return i > 0 ? filename.substring(0, i) : filename;
    }

    private static String defaultIfBlank(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
