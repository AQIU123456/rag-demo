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
}
