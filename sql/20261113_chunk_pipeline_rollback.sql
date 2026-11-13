-- =============================================================================
-- 回滚：撤销 20261113_chunk_pipeline.sql 的列与索引变更
-- 注意：仅回滚 schema，不恢复被清洗的 metadata 内容（原值非 JSON，无保留价值）
-- =============================================================================

ALTER TABLE document_chunks
  DROP INDEX idx_chunk_hash,
  DROP INDEX idx_chunk_doc_active,
  DROP COLUMN embedding_model,
  DROP COLUMN acl_tag,
  DROP COLUMN tenant_id,
  DROP COLUMN active,
  DROP COLUMN token_count,
  DROP COLUMN chunk_version,
  DROP COLUMN content_hash,
  DROP COLUMN section_path,
  DROP COLUMN parent_id,
  DROP COLUMN vector_id;

ALTER TABLE document_chunks MODIFY COLUMN metadata VARCHAR(1000) NULL;
