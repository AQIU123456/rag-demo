package com.example.aichat.controller;

import com.example.aichat.dto.ChunkStrategyDTO;
import com.example.aichat.dto.ChunkUpdateRequest;
import com.example.aichat.dto.DocumentChunkDTO;
import com.example.aichat.dto.HighlightRequest;
import com.example.aichat.dto.HighlightResponse;
import com.example.aichat.enums.ChunkStrategy;
import com.example.aichat.service.DocumentChunkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

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
            @RequestBody ChunkUpdateRequest request) {
        String newContent = request.getContent();
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
     * POST /api/knowledge/chunks/regenerate?documentId=1&strategy=RECURSIVE
     */
    @PostMapping("/regenerate")
    public ResponseEntity<List<DocumentChunkDTO>> regenerateChunks(
            @RequestParam Long documentId,
            @RequestParam(defaultValue = "RECURSIVE") String strategy) {
        ChunkStrategy chunkStrategy = ChunkStrategy.fromCode(strategy);
        List<DocumentChunkDTO> chunks = chunkService.regenerateChunks(documentId, chunkStrategy);
        return ResponseEntity.ok(chunks);
    }

    /**
     * 获取支持的分块策略列表
     * GET /api/knowledge/chunks/strategies
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<ChunkStrategyDTO>> getChunkStrategies() {
        List<ChunkStrategyDTO> strategies = java.util.Arrays.stream(ChunkStrategy.values())
                .map(strategy -> ChunkStrategyDTO.builder()
                        .code(strategy.getCode())
                        .description(strategy.getDescription())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(strategies);
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
    public ResponseEntity<HighlightResponse> highlightKeywords(@RequestBody HighlightRequest request) {
        String text = request.getText();
        String keywords = request.getKeywords();
        String highlightTag = request.getHighlightTag() != null ? request.getHighlightTag() : "<em>";
        
        if (text == null || keywords == null || keywords.trim().isEmpty()) {
            return ResponseEntity.ok(HighlightResponse.builder()
                    .highlightedText(text != null ? text : "")
                    .build());
        }
        
        String[] keywordArray = keywords.split("\\s+");
        String result = text;
        
        for (String keyword : keywordArray) {
            if (keyword.trim().isEmpty()) continue;
            // 使用不区分大小写的替换
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(keyword.trim()), 
                highlightTag + "$0" + highlightTag.replace("<", "</"));
        }
        
        return ResponseEntity.ok(HighlightResponse.builder()
                .highlightedText(result)
                .build());
    }
}
