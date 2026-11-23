package com.example.aichat.enums;

import com.example.aichat.domain.chunk.StructureAwareSplitter;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.Tokenizer;

/**
 * 文档分块策略枚举。
 *
 * <p>每个枚举值是一个真正的策略：自带「切分器工厂」与「增强配置」，因此不同策略
 * 底层使用不同的 {@link DocumentSplitter}，而不是同一个切分器加不同开关。
 * 前端通过 {@code GET /api/knowledge/chunks/strategies} 动态拉取本枚举展示下拉框，
 * 用户选择后由 {@code POST .../regenerate?strategy=<code>} 传回，驱动本次切分。</p>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>保留原有三个 code（RECURSIVE / SEMANTIC / PARENT_CHILD），前后端完全兼容</li>
 *   <li>新增 STRUCTURE_AWARE：项目早已实现 {@link StructureAwareSplitter}，
 *       但此前只藏在全局开关里，用户无法单独选择，现暴露为独立策略</li>
 *   <li>策略自带完整增强配置，不依赖 application.yml 全局开关，选哪个就是哪个结果</li>
 * </ul>
 */
public enum ChunkStrategy {

    /**
     * 递归字符切分（通用默认）：按段落 → 行 → 句子 → 词递归切分，
     * 不解析标题、不加上下文前缀、不建父子索引。
     */
    RECURSIVE("RECURSIVE", "递归字符切分",
            "按段落→行→句子→词递归切分，通用文本默认策略；不解析标题、不加上下文前缀。",
            false, false, false) {
        @Override
        public DocumentSplitter createSplitter(int maxTokens, int overlap,
                                               int hardMaxChars, Tokenizer tokenizer) {
            return DocumentSplitters.recursive(maxTokens, overlap, tokenizer);
        }
    },

    /**
     * 结构感知切分：按 Markdown 标题 / 第X章 / 编号条款边界切分，
     * 产出 section_path 并保护 Markdown 表格整块，但不加上下文前缀、不建父子索引。
     */
    STRUCTURE_AWARE("STRUCTURE_AWARE", "结构感知切分",
            "按标题、条款编号边界切分，保留章节路径并保护表格整块；不加上下文前缀。",
            true, false, false) {
        @Override
        public DocumentSplitter createSplitter(int maxTokens, int overlap,
                                               int hardMaxChars, Tokenizer tokenizer) {
            return structureAwareSplitter(maxTokens, overlap, hardMaxChars, tokenizer);
        }
    },

    /**
     * 语义智能切分：在结构感知切分基础上，为每个块前置 [文档标题 > 章节] 上下文前缀，
     * 让向量带上语义定位信息（替代早期对中文失效的空格分词 Jaccard 伪语义实现）。
     */
    SEMANTIC("SEMANTIC", "语义智能切分",
            "结构感知切分 + [文档标题 > 章节] 上下文前缀，提升语义检索命中率。",
            true, true, false) {
        @Override
        public DocumentSplitter createSplitter(int maxTokens, int overlap,
                                               int hardMaxChars, Tokenizer tokenizer) {
            return structureAwareSplitter(maxTokens, overlap, hardMaxChars, tokenizer);
        }
    },

    /**
     * 父子索引切分：结构感知 + 上下文前缀 + 父子块聚合，
     * 检索命中子块后回溯父块补全上下文，兼顾召回精度与生成完整度。
     */
    PARENT_CHILD("PARENT_CHILD", "父子索引切分",
            "结构感知 + 上下文前缀 + 父子块聚合，命中子块后回溯父块补全上下文。",
            true, true, true) {
        @Override
        public DocumentSplitter createSplitter(int maxTokens, int overlap,
                                               int hardMaxChars, Tokenizer tokenizer) {
            return structureAwareSplitter(maxTokens, overlap, hardMaxChars, tokenizer);
        }
    };

    /** 策略编码（前后端传参识别） */
    private final String code;
    /** 策略中文简称（下拉框展示） */
    private final String description;
    /** 策略详细说明（选中后展示，帮助用户理解差异） */
    private final String detail;
    /** 是否按标题/条款结构切分 */
    private final boolean structureAware;
    /** 是否给送嵌文本加 [标题 > 章节] 前缀 */
    private final boolean contextPrefix;
    /** 是否建立父子块索引 */
    private final boolean parentChild;

    ChunkStrategy(String code, String description, String detail,
                  boolean structureAware, boolean contextPrefix, boolean parentChild) {
        this.code = code;
        this.description = description;
        this.detail = detail;
        this.structureAware = structureAware;
        this.contextPrefix = contextPrefix;
        this.parentChild = parentChild;
    }

    /**
     * 构建本策略专属的切分器。由 {@code ChunkPipeline} 在切分时调用，
     * 不同策略在此返回真正不同的 {@link DocumentSplitter}。
     */
    public abstract DocumentSplitter createSplitter(int maxTokens, int overlap,
                                                    int hardMaxChars, Tokenizer tokenizer);

    /** 结构感知切分器的公共构造：token 递归切分器做底座，外层按标题/条款先分段 */
    protected static DocumentSplitter structureAwareSplitter(int maxTokens, int overlap,
                                                             int hardMaxChars, Tokenizer tokenizer) {
        DocumentSplitter base = DocumentSplitters.recursive(maxTokens, overlap, tokenizer);
        return new StructureAwareSplitter(base, hardMaxChars);
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isStructureAware() {
        return structureAware;
    }

    public boolean isContextPrefix() {
        return contextPrefix;
    }

    public boolean isParentChild() {
        return parentChild;
    }

    /** 按编码查策略，大小写不敏感；未知编码回落到默认递归切分 */
    public static ChunkStrategy fromCode(String code) {
        for (ChunkStrategy strategy : values()) {
            if (strategy.code.equalsIgnoreCase(code)) {
                return strategy;
            }
        }
        return RECURSIVE;
    }
}
