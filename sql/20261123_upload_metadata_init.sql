-- =============================================================================
-- init data：存量数据回填 + 上传元数据初始化
-- 依赖：先执行 20261123_upload_metadata.sql
-- =============================================================================

-- 存量文档回填默认值（DDL 已带 DEFAULT，这里显式初始化便于审计与回滚对照）
UPDATE knowledge_documents
   SET source       = 'quick-entry',
       version      = 'v1',
       uploaded_by  = 'anonymous',
       tenant_id    = 'default',
       parse_status = 'TEXT_ONLY'
 WHERE source IS NULL OR source = '';
