package com.example.aichat.enums;

/**
 * 文档分块策略枚举
 */
public enum ChunkStrategy {
    /**
     * 方案2：递归字符切分 (默认)
     * 按段落->行->句子->字符优先级切分，保持语义完整
     */
    RECURSIVE("RECURSIVE", "递归字符切分"),

    /**
     * 方案4：语义切分
     * 基于向量相似度识别语义边界，精度最高
     */
    SEMANTIC("SEMANTIC", "语义智能切分"),

    /**
     * 方案5：父子索引切分
     * 小块检索 + 大块上下文，兼顾召回与生成质量
     */
    PARENT_CHILD("PARENT_CHILD", "父子索引切分");

    private final String code;
    private final String description;

    ChunkStrategy(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ChunkStrategy fromCode(String code) {
        for (ChunkStrategy strategy : values()) {
            if (strategy.code.equalsIgnoreCase(code)) {
                return strategy;
            }
        }
        // 默认返回递归切分
        return RECURSIVE;
    }
}
