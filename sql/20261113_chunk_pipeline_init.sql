-- =============================================================================
-- init data：存量分块标记为 legacy，交由启动重建任务按新链路重新切分与索引
-- 前提：已执行 20261113_chunk_pipeline.sql
-- 说明：重建依据始终是 knowledge_documents.content（单一事实源），
--       表内 legacy 行会在首次 indexNow 时被 hash 比对判定为不一致并全量替换。
-- =============================================================================

UPDATE document_chunks
   SET chunk_version = 'legacy',
       active = 0
 WHERE content_hash IS NULL;

-- 核验（预期 0 行表示全部存量块已正确标记）
-- SELECT COUNT(*) FROM document_chunks WHERE chunk_version = 'legacy' AND active = 1;
