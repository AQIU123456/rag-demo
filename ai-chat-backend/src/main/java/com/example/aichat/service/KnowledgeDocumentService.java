package com.example.aichat.service;

import com.example.aichat.dto.DocumentResponse;
import com.example.aichat.dto.DocumentUploadRequest;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class KnowledgeDocumentService {
    
    private final KnowledgeDocumentRepository documentRepository;
    private final EmbeddingService embeddingService;
    
    public KnowledgeDocumentService(KnowledgeDocumentRepository documentRepository,
                                   EmbeddingService embeddingService) {
        this.documentRepository = documentRepository;
        this.embeddingService = embeddingService;
    }
    
    /**
     * 上传纯文本文档
     * 预留接口：后续可扩展 PDF、Word 等其他格式
     */
    public DocumentResponse uploadPlainText(DocumentUploadRequest request) {
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        
        String content = request.getContent().trim();
        int wordCount = countWords(content);
        long fileSize = content.getBytes().length;
        
        KnowledgeDocument document = KnowledgeDocument.builder()
                .title(request.getTitle() != null ? request.getTitle() : "未命名文档")
                .description(request.getDescription())
                .content(content)
                .fileType(request.getFileType() != null ? request.getFileType() : "txt")
                .fileSize(fileSize)
                .wordCount(wordCount)
                .build();
        
        KnowledgeDocument saved = documentRepository.save(document);
        
        // 阶段 2: 将文档内容进行向量化处理
        try {
            embeddingService.embedAndStore(content, "doc_" + saved.getId());
        } catch (Exception e) {
            // 向量化失败不影响文档保存，记录日志即可
            System.err.println("文档向量化失败：" + e.getMessage());
        }
        
        return toDocumentResponse(saved);
    }
    
    /**
     * 获取文档列表（分页）
     */
    @Transactional(readOnly = true)
    public Page<DocumentResponse> getDocuments(Pageable pageable) {
        return documentRepository.findAll(pageable).map(this::toDocumentResponse);
    }
    
    /**
     * 根据 ID 获取文档详情
     */
    @Transactional(readOnly = true)
    public DocumentResponse getDocumentById(Long id) {
        KnowledgeDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + id));
        return toDocumentResponse(document);
    }
    
    /**
     * 搜索文档（关键词搜索）
     */
    @Transactional(readOnly = true)
    public Page<DocumentResponse> searchDocuments(String keyword, Pageable pageable) {
        return documentRepository.searchByKeyword(keyword, pageable).map(this::toDocumentResponse);
    }
    
    /**
     * 语义搜索文档（基于向量相似度）
     */
    @Transactional(readOnly = true)
    public List<EmbeddingService.SearchResult> semanticSearch(String query, int maxResults) {
        return embeddingService.searchSimilar(query, maxResults);
    }
    
    /**
     * 阶段 3: 混合搜索 - 结合关键词搜索和语义搜索（默认权重）
     * @param query 查询文本
     * @param maxResults 最大返回结果数
     * @return 混合搜索结果列表
     */
    @Transactional(readOnly = true)
    public List<HybridSearchResult> hybridSearch(String query, int maxResults) {
        return hybridSearch(query, 0.4, 0.6, maxResults);
    }
    
    /**
     * 阶段 3: 混合搜索 - 结合关键词搜索和语义搜索（自定义权重）
     * @param query 查询文本
     * @param keywordWeight 关键词搜索权重 (0-1)，默认 0.4
     * @param semanticWeight 语义搜索权重 (0-1)，默认 0.6
     * @param maxResults 最大返回结果数
     * @return 混合搜索结果列表
     */
    @Transactional(readOnly = true)
    public List<HybridSearchResult> hybridSearch(String query, double keywordWeight, 
                                                  double semanticWeight, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // 归一化权重
        double totalWeight = keywordWeight + semanticWeight;
        if (totalWeight <= 0) {
            keywordWeight = 0.4;
            semanticWeight = 0.6;
        } else {
            keywordWeight = keywordWeight / totalWeight;
            semanticWeight = semanticWeight / totalWeight;
        }
        
        // 1. 执行关键词搜索
        List<HybridSearchResult> results = new ArrayList<>();
        Page<KnowledgeDocument> keywordResults = documentRepository.searchByKeyword(
            query, 
            org.springframework.data.domain.PageRequest.of(0, maxResults * 2)
        );
        
        for (KnowledgeDocument doc : keywordResults.getContent()) {
            double score = calculateKeywordScoreFromEntity(doc, query);
            results.add(new HybridSearchResult(
                doc.getId(),
                doc.getTitle(),
                doc.getContent(),
                doc.getDescription(),
                score * keywordWeight,
                0,
                "keyword"
            ));
        }
        
        // 2. 执行语义搜索
        List<EmbeddingService.SearchResult> semanticResults = embeddingService.searchSimilar(query, maxResults * 2);
        
        for (EmbeddingService.SearchResult sr : semanticResults) {
            // 从 source 元数据中提取文档 ID
            Long docId = extractDocIdFromSource(sr.getSource());
            if (docId != null) {
                // 检查是否已存在该文档的结果
                HybridSearchResult existing = results.stream()
                    .filter(r -> r.getDocumentId().equals(docId))
                    .findFirst()
                    .orElse(null);
                
                if (existing != null) {
                    // 合并分数
                    existing.setSemanticScore(sr.getScore() * semanticWeight);
                    existing.setCombinedScore(existing.getKeywordScore() + existing.getSemanticScore());
                    existing.addMatchType("semantic");
                } else {
                    // 获取完整文档信息
                    try {
                        DocumentResponse fullDoc = getDocumentById(docId);
                        results.add(new HybridSearchResult(
                            docId,
                            fullDoc.getTitle(),
                            fullDoc.getContent(),
                            fullDoc.getDescription(),
                            0, // keywordScore
                            sr.getScore() * semanticWeight,
                            "semantic"
                        ));
                    } catch (Exception e) {
                        // 文档可能已被删除，跳过
                    }
                }
            }
        }
        
        // 3. 按综合分数排序
        results.sort(Comparator.comparingDouble(HybridSearchResult::getCombinedScore).reversed());
        
        // 4. 截取前 N 个结果
        return results.stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    /**
     * 计算关键词匹配分数（从 DTO）
     */
    private double calculateKeywordScore(DocumentResponse doc, String query) {
        String[] keywords = query.toLowerCase().split("\\s+");
        String title = doc.getTitle().toLowerCase();
        String content = doc.getContent().toLowerCase();
        String description = doc.getDescription() != null ? doc.getDescription().toLowerCase() : "";
        
        double score = 0;
        
        for (String keyword : keywords) {
            if (keyword.trim().isEmpty()) continue;
            
            // 标题匹配权重最高
            if (title.contains(keyword)) {
                score += 3.0;
            }
            // 描述匹配权重次之
            if (description.contains(keyword)) {
                score += 2.0;
            }
            // 内容匹配权重较低
            if (content.contains(keyword)) {
                score += 1.0;
            }
        }
        
        // 归一化到 0-1 范围
        return Math.min(score / (keywords.length * 3), 1.0);
    }
    
    /**
     * 计算关键词匹配分数（从 Entity）
     */
    private double calculateKeywordScoreFromEntity(KnowledgeDocument doc, String query) {
        String[] keywords = query.toLowerCase().split("\\s+");
        String title = doc.getTitle().toLowerCase();
        String content = doc.getContent().toLowerCase();
        String description = doc.getDescription() != null ? doc.getDescription().toLowerCase() : "";
        
        double score = 0;
        
        for (String keyword : keywords) {
            if (keyword.trim().isEmpty()) continue;
            
            // 标题匹配权重最高
            if (title.contains(keyword)) {
                score += 3.0;
            }
            // 描述匹配权重次之
            if (description.contains(keyword)) {
                score += 2.0;
            }
            // 内容匹配权重较低
            if (content.contains(keyword)) {
                score += 1.0;
            }
        }
        
        // 归一化到 0-1 范围
        return Math.min(score / (keywords.length * 3), 1.0);
    }
    
    /**
     * 从语义搜索的 source 元数据中提取文档 ID
     * source 格式：doc_1, doc_2, etc.
     */
    private Long extractDocIdFromSource(String source) {
        if (source == null || !source.startsWith("doc_")) {
            return null;
        }
        try {
            return Long.parseLong(source.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 阶段 3: 高亮显示搜索结果中的关键词
     * @param text 原始文本
     * @param keywords 要高亮的关键词
     * @param highlightTag 高亮标签，默认 <em>
     * @return 高亮后的文本
     */
    public String highlightKeywords(String text, String keywords, String highlightTag) {
        if (text == null || keywords == null || keywords.trim().isEmpty()) {
            return text;
        }
        
        String[] keywordArray = keywords.split("\\s+");
        String result = text;
        
        for (String keyword : keywordArray) {
            if (keyword.trim().isEmpty()) continue;
            // 使用不区分大小写的替换
            result = result.replaceAll("(?i)" + Pattern.quote(keyword.trim()), 
                highlightTag + "$0" + highlightTag.replace("<", "</"));
        }
        
        return result;
    }
    
    /**
     * 高亮显示搜索结果中的关键词（默认使用<em>标签）
     */
    public String highlightKeywords(String text, String keywords) {
        return highlightKeywords(text, keywords, "<em>");
    }
    
    /**
     * 阶段 3: 混合搜索结果 DTO
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class HybridSearchResult {
        private Long documentId;
        private String title;
        private String content;
        private String description;
        private double keywordScore;
        private double semanticScore;
        private double combinedScore;
        private Set<String> matchTypes = new HashSet<>();
        
        public HybridSearchResult(Long documentId, String title, String content, 
                                  String description, double keywordScore, 
                                  double semanticScore, String matchType) {
            this.documentId = documentId;
            this.title = title;
            this.content = content;
            this.description = description;
            this.keywordScore = keywordScore;
            this.semanticScore = semanticScore;
            this.combinedScore = keywordScore + semanticScore;
            this.matchTypes = new HashSet<>();
            if (matchType != null) {
                this.matchTypes.add(matchType);
            }
        }
        
        public void addMatchType(String type) {
            this.matchTypes.add(type);
        }
    }
    
    /**
     * 删除文档
     */
    public void deleteDocument(Long id) {
        if (!documentRepository.existsById(id)) {
            throw new RuntimeException("文档不存在，ID: " + id);
        }
        documentRepository.deleteById(id);
        // 注意：向量化数据在内存中，暂不处理清理逻辑
        // 切换到 Milvus 后需要在此处清理对应的向量数据
    }
    
    /**
     * 更新文档内容
     */
    public DocumentResponse updateDocument(Long id, DocumentUploadRequest request) {
        KnowledgeDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + id));
        
        if (request.getTitle() != null) {
            document.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            document.setDescription(request.getDescription());
        }
        if (request.getContent() != null) {
            String content = request.getContent().trim();
            document.setContent(content);
            document.setWordCount(countWords(content));
            document.setFileSize((long) content.getBytes().length);
        }
        
        KnowledgeDocument updated = documentRepository.save(document);
        return toDocumentResponse(updated);
    }
    
    /**
     * 简单的字数统计（中文按字符计，英文按单词计）
     */
    private int countWords(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        // 简单实现：统计非空白字符数
        return content.replaceAll("\\s+", "").length();
    }
    
    /**
     * 实体转 DTO
     */
    private DocumentResponse toDocumentResponse(KnowledgeDocument document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .title(document.getTitle())
                .description(document.getDescription())
                .fileType(document.getFileType())
                .fileSize(document.getFileSize())
                .wordCount(document.getWordCount())
                .content(document.getContent())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
