package com.example.aichat.repository;

import com.example.aichat.entity.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    
    Page<KnowledgeDocument> findByTitleContainingIgnoreCase(String title, Pageable pageable);
    
    @Query("SELECT k FROM KnowledgeDocument k WHERE k.content LIKE %:keyword% OR k.title LIKE %:keyword%")
    Page<KnowledgeDocument> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
    
    List<KnowledgeDocument> findByFileType(String fileType);
    
    List<KnowledgeDocument> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** 秒传去重：按文件 sha256 查已有文档（取最早一条，作为秒传命中目标） */
    List<KnowledgeDocument> findByFileHashOrderByIdAsc(String fileHash);

    /** 同 hash 多版本回溯 */
    List<KnowledgeDocument> findByFileHashOrderByVersionDesc(String fileHash);

    /** 版本链：按原始文件名查同一文档的各版本 */
    List<KnowledgeDocument> findByOriginalFilenameOrderByVersionDesc(String originalFilename);
}
