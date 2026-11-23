-- =============================================================================
-- 回滚：删除上传元数据列与上传任务表
-- 执行前请确认不再依赖相关字段；知识文档正文与原表结构不受影响
-- =============================================================================

DROP TABLE IF EXISTS upload_task;

ALTER TABLE knowledge_documents
  DROP INDEX idx_doc_file_hash,
  DROP INDEX idx_doc_tenant,
  DROP INDEX idx_doc_original_name,
  DROP COLUMN parse_status,
  DROP COLUMN tenant_id,
  DROP COLUMN uploaded_by,
  DROP COLUMN source,
  DROP COLUMN version,
  DROP COLUMN storage_path,
  DROP COLUMN file_hash,
  DROP COLUMN mime_type,
  DROP COLUMN original_filename;
