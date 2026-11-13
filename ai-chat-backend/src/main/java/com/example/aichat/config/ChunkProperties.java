package com.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 企业级分块流水线的统一配置。
 *
 * <p>所有原先硬编码在 Service 里的分块参数都收敛到这里，改配置即可 A/B 或回滚，
 * 无需改代码。S1 阶段可把 structure-aware / context-prefix / bm25-enabled 置 false
 * 作为对照组；S2/S3 逐个打开即可，不必重新编译。</p>
 */
@Data
@ConfigurationProperties(prefix = "chunk")
public class ChunkProperties {

    /** 子块最大 token 数（按 HeuristicTokenizer 估算） */
    private int maxTokens = 300;
    /** 相邻子块重叠 token 数 */
    private int overlapTokens = 40;
    /** 父块最大 token 数（父子索引时用于聚合相邻子块） */
    private int parentMaxTokens = 900;
    /** 单块硬上限（字符），防止极端长行绕过 token 估算 */
    private int hardMaxChars = 4000;

    /** S2：标题/条款结构感知切分，产出 section_path */
    private boolean structureAware = false;
    /** S2：送嵌文本前置 [文档标题 > 章节路径] 上下文前缀 */
    private boolean contextPrefix = false;
    /** S2：父子索引，检索命中子块后回溯父块注入 LLM */
    private boolean parentChild = false;
    /** S2：命中块前后各扩展多少相邻块 */
    private int neighborWindow = 0;
    /** S2：单个文档在 topK 中最多占几条（多样性控制） */
    private int maxPerDocument = 3;

    /** 当前索引版本，灰度重建时切换 */
    private String activeVersion = "v1";
    /** 粗排召回倍数：墓碑/过滤会吃掉部分候选，需多召回 */
    private int recallMultiplier = 6;
    /** 向量相似度最低分 */
    private double minScore = 0.0;

    /** S3：启用 BM25 关键词通道并与向量结果做 RRF 融合 */
    private boolean bm25Enabled = false;
    /** RRF 平滑常数 */
    private int rrfK = 60;
    /** BM25 通道参与融合的候选条数 */
    private int keywordCandidates = 30;

    /** S3：启用检索期租户 / ACL 过滤（默认关闭，避免无鉴权场景下误伤） */
    private boolean tenantFilterEnabled = false;
    /** 当前租户标识；接入真实鉴权后由请求上下文覆盖 */
    private String currentTenant = "default";
    /** 当前请求允许访问的 ACL 标签 */
    private String currentAclTag = "public";

    /** S3：块质量观测日志 */
    private boolean observability = true;
}
