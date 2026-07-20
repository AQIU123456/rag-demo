package com.example.aichat.repository;

import com.example.aichat.entity.DocumentChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * 根据文档 ID 查询所有分块
     */
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(Long documentId);

    /**
     * 根据文档 ID 删除所有分块
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 搜索包含关键词的分块（用于关键词搜索）
     */
    @Query("SELECT c FROM DocumentChunk c WHERE c.content LIKE %:keyword%")
    Page<DocumentChunk> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
