package com.example.aichat.service;

import com.example.aichat.dto.DocumentResponse;
import com.example.aichat.dto.DocumentUploadRequest;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class KnowledgeDocumentService {
    
    private final KnowledgeDocumentRepository documentRepository;
    
    public KnowledgeDocumentService(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
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
     * 搜索文档
     */
    @Transactional(readOnly = true)
    public Page<DocumentResponse> searchDocuments(String keyword, Pageable pageable) {
        return documentRepository.searchByKeyword(keyword, pageable).map(this::toDocumentResponse);
    }
    
    /**
     * 删除文档
     */
    public void deleteDocument(Long id) {
        if (!documentRepository.existsById(id)) {
            throw new RuntimeException("文档不存在，ID: " + id);
        }
        documentRepository.deleteById(id);
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
