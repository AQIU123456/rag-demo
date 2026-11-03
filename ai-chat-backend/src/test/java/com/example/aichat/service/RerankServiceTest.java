package com.example.aichat.service;

import com.example.aichat.config.RerankConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RerankService 单元测试
 * 验证本地兜底重排的排序正确性与降级行为（不依赖外部 API）
 */
class RerankServiceTest {

    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        RerankConfig config = new RerankConfig();
        config.setEnabled(true);
        // 不配置 apiUrl，走本地轻量重排分支
        rerankService = new RerankService(config, new ObjectMapper());
    }

    @Test
    void localRerankShouldPutRelevantDocumentFirst() {
        String query = "公司的年假政策是什么";
        List<String> documents = List.of(
            "今天食堂提供了红烧肉和青菜。",           // 无关内容（向量粗排可能误召回）
            "员工入职满一年后享有 10 天带薪年假，年假需提前一周申请。", // 真正相关
            "会议室预约系统使用说明。"               // 无关内容
        );
        double[] vectorScores = new double[]{0.92, 0.85, 0.80};

        List<RerankService.RerankResult> results =
            rerankService.localRerank(query, documents, vectorScores, 3);

        assertEquals(3, results.size());
        // 尽管文档 0 的向量分数最高，重排后最相关的文档 1 应排在第一位
        assertEquals(1, results.get(0).getIndex(), "年假政策片段应被重排到首位");
        // 结果应按相关性分数降序排列
        assertTrue(results.get(0).getRelevanceScore() >= results.get(1).getRelevanceScore());
        assertTrue(results.get(1).getRelevanceScore() >= results.get(2).getRelevanceScore());
    }

    @Test
    void localRerankShouldTruncateToTopN() {
        String query = "如何报销差旅费用";
        List<String> documents = List.of(
            "差旅费报销需在 OA 系统提交发票。",
            "公司年度旅游计划公布。",
            "加班打车费用可以报销。",
            "健身房使用规定。"
        );
        double[] vectorScores = new double[]{0.9, 0.7, 0.6, 0.5};

        List<RerankService.RerankResult> results =
            rerankService.localRerank(query, documents, vectorScores, 2);

        assertEquals(2, results.size());
        assertEquals(0, results.get(0).getIndex(), "报销相关片段应排第一");
    }

    @Test
    void rerankWithEmptyInputShouldReturnEmptyList() {
        assertTrue(rerankService.rerank("q", List.of(), 3).isEmpty());
        assertTrue(rerankService.rerank("q", List.of("a"), 0).isEmpty());
    }

    @Test
    void disabledSwitchShouldReportNotEnabled() {
        RerankConfig config = new RerankConfig();
        config.setEnabled(false);
        RerankService disabledService = new RerankService(config, new ObjectMapper());
        assertFalse(disabledService.isEnabled());
        assertFalse(disabledService.isRemoteEnabled());
    }

    @Test
    void candidateMultiplierShouldHaveMinimumOne() {
        RerankConfig config = new RerankConfig();
        config.setCandidateMultiplier(0);
        RerankService service = new RerankService(config, new ObjectMapper());
        assertEquals(1, service.getCandidateMultiplier());
    }
}
