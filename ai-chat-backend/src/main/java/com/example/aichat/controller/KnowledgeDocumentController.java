package com.example.aichat.controller;

import com.example.aichat.dto.DocumentResponse;
import com.example.aichat.dto.DocumentUploadRequest;
import com.example.aichat.service.EmbeddingService;
import com.example.aichat.service.KnowledgeDocumentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;
    private final EmbeddingService embeddingService;

    public KnowledgeDocumentController(KnowledgeDocumentService documentService,
                                       EmbeddingService embeddingService) {
        this.documentService = documentService;
        this.embeddingService = embeddingService;
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
    public ResponseEntity<Map<String, String>> highlightKeywords(
            @RequestBody Map<String, String> request) {
        
        String text = request.get("text");
        String keywords = request.get("keywords");
        String highlightTag = request.getOrDefault("highlightTag", "<em>");
        
        String highlighted = documentService.highlightKeywords(text, keywords, highlightTag);
        
        Map<String, String> response = new HashMap<>();
        response.put("highlightedText", highlighted);
        return ResponseEntity.ok(response);
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
