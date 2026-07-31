package com.example.aichat.controller;

import com.example.aichat.dto.DocumentChunkDTO;
import com.example.aichat.service.DocumentChunkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分块管理控制器
 * 提供分块的查看、编辑、删除、重新生成等功能
 */
@RestController
@RequestMapping("/api/knowledge/chunks")
@CrossOrigin(origins = "*")
public class DocumentChunkController {

    private final DocumentChunkService chunkService;

    public DocumentChunkController(DocumentChunkService chunkService) {
        this.chunkService = chunkService;
    }

    /**
     * 获取文档的所有分块
     * GET /api/knowledge/chunks?documentId=1
     */
    @GetMapping
    public ResponseEntity<List<DocumentChunkDTO>> getChunksByDocument(
            @RequestParam Long documentId) {
        List<DocumentChunkDTO> chunks = chunkService.getChunksByDocumentId(documentId);
        return ResponseEntity.ok(chunks);
    }

    /**
     * 更新单个分块内容
     * PUT /api/knowledge/chunks/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<DocumentChunkDTO> updateChunk(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String newContent = request.get("content");
        if (newContent == null || newContent.trim().isEmpty()) {
            throw new IllegalArgumentException("分块内容不能为空");
        }
        
        DocumentChunkDTO updated = chunkService.updateChunk(id, newContent.trim());
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除单个分块
     * DELETE /api/knowledge/chunks/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteChunk(@PathVariable Long id) {
        chunkService.deleteChunk(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 重新生成分块（基于当前文档内容重新切分）
     * POST /api/knowledge/chunks/regenerate?documentId=1
     */
    @PostMapping("/regenerate")
    public ResponseEntity<List<DocumentChunkDTO>> regenerateChunks(
            @RequestParam Long documentId) {
        List<DocumentChunkDTO> chunks = chunkService.regenerateChunks(documentId);
        return ResponseEntity.ok(chunks);
    }

    /**
     * 批量更新分块顺序
     * POST /api/knowledge/chunks/reorder
     */
    @PostMapping("/reorder")
    public ResponseEntity<Void> reorderChunks(
            @RequestBody List<Long> chunkIds) {
        chunkService.updateChunkOrder(chunkIds);
        return ResponseEntity.ok().build();
    }

    /**
     * 高亮显示分块中的关键词
     * POST /api/knowledge/chunks/highlight
     */
    @PostMapping("/highlight")
    public ResponseEntity<Map<String, String>> highlightKeywords(
            @RequestBody Map<String, String> request) {
        String text = request.get("text");
        String keywords = request.get("keywords");
        String highlightTag = request.getOrDefault("highlightTag", "<em>");
        
        if (text == null || keywords == null || keywords.trim().isEmpty()) {
            Map<String, String> response = new HashMap<>();
            response.put("highlightedText", text != null ? text : "");
            return ResponseEntity.ok(response);
        }
        
        String[] keywordArray = keywords.split("\\s+");
        String result = text;
        
        for (String keyword : keywordArray) {
            if (keyword.trim().isEmpty()) continue;
            // 使用不区分大小写的替换
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(keyword.trim()), 
                highlightTag + "$0" + highlightTag.replace("<", "</"));
        }
        
        Map<String, String> response = new HashMap<>();
        response.put("highlightedText", result);
        return ResponseEntity.ok(response);
    }
}
