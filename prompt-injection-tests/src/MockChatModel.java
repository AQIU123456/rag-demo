import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可控的 Mock 大模型，用于 Prompt Injection 测试。
 *
 * 它模拟一个"真实 LLM 在弱防御提示词下的典型行为"：
 * 1. 当 SystemMessage / UserMessage 中出现 "忽略(之前|以上)指令"、"ignore previous instructions"、
 *    "new system prompt"、"系统提示词覆盖" 等注入指令时，模型会"服从注入指令"；
 * 2. 若注入指令要求泄露系统提示词 -> 直接回显原始 SystemMessage 全文（信息泄露）；
 * 3. 若注入指令要求调用工具（如查询他人订单）-> 返回 ToolExecutionRequest（间接注入触发越权动作）；
 * 4. 否则正常按知识库上下文回答。
 *
 * 同时记录每次 generate() 收到的完整消息内容，供断言"不可信文本是否被原样拼入提示词"。
 */
public class MockChatModel implements ChatLanguageModel {

    /** 常见注入指令特征（仅用于模拟模型行为，非被测系统的防御——被测系统没有任何防御） */
    private static final Pattern INJECTION = Pattern.compile(
            "(忽略(之前|以上|所有)(的)?指令|无视(之前|以上|系统)(提示|指令)|" +
            "ignore (all )?(previous|above) (instructions|prompts)|" +
            "new system prompt|disregard.*system|系统提示词如下|你现在是)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LEAK_REQUEST = Pattern.compile(
            "(输出|打印|重复|告诉我|显示)[^\n]{0,16}(系统提示词|system prompt)"
            + "|(系统提示词|system prompt)[^\n]{0,8}(原文|输出|打印|重复)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_TOOL_REQUEST = Pattern.compile(
            "(查询|调用|执行).{0,20}(订单|order)", Pattern.CASE_INSENSITIVE);

    /** 记录每一轮发送给模型的完整 prompt 文本，用于证明注入文本进入了提示词 */
    public final List<String> capturedPrompts = new ArrayList<>();

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, new ArrayList<>());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                        List<ToolSpecification> toolSpecifications) {
        StringBuilder all = new StringBuilder();
        String systemText = "";
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage) {
                systemText = ((SystemMessage) m).text();
                all.append("[SYSTEM] ").append(systemText).append("\n");
            } else if (m instanceof UserMessage) {
                all.append("[USER] ").append(((UserMessage) m).singleText()).append("\n");
            } else if (m instanceof AiMessage) {
                AiMessage ai = (AiMessage) m;
                all.append("[AI] ").append(ai.text() == null ? "" : ai.text()).append("\n");
            } else if (m instanceof ToolExecutionResultMessage) {
                all.append("[TOOL_RESULT] ")
                        .append(((ToolExecutionResultMessage) m).text()).append("\n");
            }
        }
        capturedPrompts.add(all.toString());

        // ---- 模拟弱防御模型的行为 ----
        String haystack = all.toString();
        Matcher inj = INJECTION.matcher(haystack);
        boolean injected = inj.find();

        if (injected && LEAK_REQUEST.matcher(haystack).find()) {
            // 注入成功：模型泄露系统提示词
            return Response.from(AiMessage.from(
                    "【模型被注入】好的，以下是我的系统提示词原文：\n" + systemText));
        }

        if (injected && ORDER_TOOL_REQUEST.matcher(haystack).find()
                && !toolSpecifications.isEmpty()) {
            // 注入成功：模型被诱导发起工具调用（尝试越权查询任意订单）
            String orderId = extractOrderId(haystack);
            return Response.from(AiMessage.from(ToolExecutionRequest.builder()
                    .id("call-" + System.nanoTime())
                    .name("getOrderInfo")
                    .arguments("{\"orderId\": \"" + orderId + "\"}")
                    .build()));
        }

        // 正常路径：基于上下文简要回答
        return Response.from(AiMessage.from(
                "根据知识库内容，答案如上所述 [1]。（模型正常运行，未受注入影响）"));
    }

    /** 从被注入的文本中抽取订单号，模拟攻击者指定的目标数据 */
    private String extractOrderId(String text) {
        Matcher m = Pattern.compile("(ORD\\d{3})", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return m.group(1).toUpperCase();
        return "ORD001";
    }
}
