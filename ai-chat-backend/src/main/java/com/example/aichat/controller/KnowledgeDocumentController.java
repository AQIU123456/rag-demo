package com.example.aichat.controller;

import com.example.aichat.dto.DocumentResponse;
import com.example.aichat.dto.DocumentUploadRequest;
import com.example.aichat.dto.HighlightRequest;
import com.example.aichat.dto.HighlightResponse;
import com.example.aichat.dto.TaskStatusResponse;
import com.example.aichat.dto.UploadResponse;
import com.example.aichat.service.EmbeddingService;
import com.example.aichat.service.FileStorageService;
import com.example.aichat.service.FileUploadService;
import com.example.aichat.service.KnowledgeDocumentService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;
    private final EmbeddingService embeddingService;
    private final FileUploadService fileUploadService;
    private final FileStorageService fileStorageService;

    public KnowledgeDocumentController(KnowledgeDocumentService documentService,
                                       EmbeddingService embeddingService,
                                       FileUploadService fileUploadService,
                                       FileStorageService fileStorageService) {
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.fileUploadService = fileUploadService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 上传纯文本文档
     * POST /api/knowledge/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentResponse> uploadPlainText(@RequestBody DocumentUploadRequest request) {
        DocumentResponse response = documentService.uploadPlainText(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 企业级文件上传（multipart）：校验 → 去重 → 存储 → 解析 → 入库 → 索引
     * POST /api/knowledge/upload/file
     */
    @PostMapping(value = "/upload/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Upload-User", defaultValue = "anonymous") String uploadedBy,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ResponseEntity.ok(fileUploadService.upload(file, uploadedBy, tenantId));
    }

    /** 查询上传任务进度 GET /api/knowledge/upload/tasks/{taskId} */
    @GetMapping("/upload/tasks/{taskId}")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(fileUploadService.getTaskStatus(taskId));
    }

    /** 失败任务重试 POST /api/knowledge/upload/tasks/{taskId}/retry */
    @PostMapping("/upload/tasks/{taskId}/retry")
    public ResponseEntity<Void> retryTask(@PathVariable String taskId) {
        fileUploadService.retryTask(taskId);
        return ResponseEntity.ok().build();
    }

    /** 下载原文件 GET /api/knowledge/documents/{id}/download */
    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> downloadOriginal(@PathVariable Long id) {
        FileUploadService.FileDownload dl = fileUploadService.download(id);
        if (dl == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            Resource resource = new UrlResource(dl.path().toUri());
            String mime = dl.mimeType() != null ? dl.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
            String encodedName = java.net.URLEncoder.encode(dl.filename(), java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedName + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取文档列表（分页）
     * GET /api/knowledge/documents?page=0&size=10&sort=createdAt,desc
     */
    @GetMapping("/documents")
    public ResponseEntity<Page<DocumentResponse>> getDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {

        Sort sortBy = direction.equalsIgnoreCase("asc")
                ? Sort.by(sort).ascending()
                : Sort.by(sort).descending();
        PageRequest pageable = PageRequest.of(page, size, sortBy);

        Page<DocumentResponse> documents = documentService.getDocuments(pageable);
        return ResponseEntity.ok(documents);
    }

    /**
     * 获取单个文档详情
     * GET /api/knowledge/documents/{id}
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentResponse> getDocumentById(@PathVariable Long id) {
        DocumentResponse document = documentService.getDocumentById(id);
        return ResponseEntity.ok(document);
    }

    /**
     * 搜索文档（关键词搜索）
     * GET /api/knowledge/search?keyword=xxx&page=0&size=10
     */
    @GetMapping("/search")
    public ResponseEntity<Page<DocumentResponse>> searchDocuments(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<DocumentResponse> results = documentService.searchDocuments(keyword, pageable);
        return ResponseEntity.ok(results);
    }

    /**
     * 语义搜索文档（基于向量相似度）- 阶段 2 新增
     * GET /api/knowledge/semantic-search?query=xxx&maxResults=5
     */
    @GetMapping("/semantic-search")
    public ResponseEntity<List<EmbeddingService.SearchResult>> semanticSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int maxResults) {
        
        List<EmbeddingService.SearchResult> results = documentService.semanticSearch(query, maxResults);
        return ResponseEntity.ok(results);
    }

    /**
     * 阶段 3: 混合搜索 - 结合关键词搜索和语义搜索
     * GET /api/knowledge/hybrid-search?query=xxx&maxResults=5&keywordWeight=0.4&semanticWeight=0.6
     */
    @GetMapping("/hybrid-search")
    public ResponseEntity<List<KnowledgeDocumentService.HybridSearchResult>> hybridSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int maxResults,
            @RequestParam(defaultValue = "0.4") double keywordWeight,
            @RequestParam(defaultValue = "0.6") double semanticWeight) {
        
        List<KnowledgeDocumentService.HybridSearchResult> results = 
            documentService.hybridSearch(query, keywordWeight, semanticWeight, maxResults);
        return ResponseEntity.ok(results);
    }
    
    /**
     * 高亮显示搜索结果中的关键词
     * POST /api/knowledge/highlight
     */
    @PostMapping("/highlight")
    public ResponseEntity<HighlightResponse> highlightKeywords(@RequestBody HighlightRequest request) {
        
        String text = request.getText();
        String keywords = request.getKeywords();
        String highlightTag = request.getHighlightTag() != null ? request.getHighlightTag() : "<em>";
        
        String highlighted = documentService.highlightKeywords(text, keywords, highlightTag);
        
        return ResponseEntity.ok(HighlightResponse.builder()
                .highlightedText(highlighted)
                .build());
    }

    /**
     * 更新文档
     * PUT /api/knowledge/documents/{id}
     */
    @PutMapping("/documents/{id}")
    public ResponseEntity<DocumentResponse> updateDocument(
            @PathVariable Long id,
            @RequestBody DocumentUploadRequest request) {
        DocumentResponse updated = documentService.updateDocument(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除文档
     * DELETE /api/knowledge/documents/{id}
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
