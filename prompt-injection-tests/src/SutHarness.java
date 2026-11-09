import com.example.aichat.service.ChatService;
import com.example.aichat.service.RagService;
import com.example.aichat.tool.OrderTool;
import com.example.aichat.tool.WeatherTool;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 被测系统（SUT）桥接层：直接复用 ai-chat-backend 中的真实生产代码，
 * 仅通过反射把真实的 OpenAI 模型替换成 MockChatModel（避免网络依赖）。
 */
public class SutHarness {

    /** 复制自 RagService.buildSystemPrompt()（private），保证测试与生产提示词结构一致 */
    public static String buildSystemPrompt(String context) {
        return "你是一个专业的企业知识库助手。请根据以下提供的知识库内容来回答用户的问题。\n" +
               "要求：\n" +
               "1. 只基于提供的知识库内容进行回答，不要编造信息\n" +
               "2. 如果知识库中没有相关信息，请明确告知用户\n" +
               "3. 回答要准确、简洁、专业\n" +
               "4. 在回答中引用相关内容的编号，例如 [1]、[2]\n" +
               "5. 如果有多条相关信息，请综合整理后回答\n" +
               "\n" +
               context +
               "\n" +
               "请根据以上知识库内容回答用户的问题。";
    }

    /** 复制自 EmbeddingService.SearchResult 的语义（该内部类无公开构造器，无法直接实例化） */
    public record FakeSearchResult(String content, double score, String source) {}

    /** 复制自 RagService.buildContext()（private），逻辑与生产代码逐行对应 */
    public static String buildContext(List<FakeSearchResult> results) {
        if (results.isEmpty()) return "未找到相关知识库内容。";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从知识库中检索到的相关信息：\n\n");
        for (int i = 0; i < results.size(); i++) {
            FakeSearchResult r = results.get(i);
            sb.append(String.format("[%d] ", i + 1));
            sb.append(r.content());
            sb.append(String.format(" (相关度：%.2f)\n\n", r.score()));
        }
        return sb.toString();
    }

    /** 用 Mock 模型实例化真实的生产 ChatService */
    public static ChatService newChatService(MockChatModel model) {
        return new ChatService(model);
    }

    /** 用 Mock 模型实例化真实的 RagService（embeddingService 传 null，RAG 主流程中不使用它） */
    public static RagService newRagService(MockChatModel model) {
        return new RagService(model, null);
    }

    /** 反射调用 RagService.generateAnswer(SystemMessage文本, question)——真实生产私有方法 */
    public static String ragGenerateAnswer(RagService svc, String systemPrompt, String question)
            throws Exception {
        Field f = RagService.class.getDeclaredField("chatLanguageModel");
        f.setAccessible(true);
        Object model = f.get(svc);
        java.lang.reflect.Method m = RagService.class.getDeclaredMethod(
                "generateAnswer", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, systemPrompt, question);
    }

    /** 复制自 ToolCallingChatService.executeTools() 的核心分发逻辑（生产代码为 private） */
    public static String dispatchToolCall(String toolName, String argumentsJson,
                                          WeatherTool weatherTool, OrderTool orderTool) {
        String orderId = extractArg(argumentsJson, "orderId");
        switch (toolName) {
            case "getWeather":
                String city = extractArg(argumentsJson, "city");
                return weatherTool.getWeather(city != null ? city : "北京");
            case "getOrderInfo":
                return orderTool.getOrderInfo(orderId != null ? orderId : "");
            default:
                return "未知的工具：" + toolName;
        }
    }

    /** 复制自 ToolCallingChatService.extractArgument()（生产代码为 private） */
    public static String extractArg(String arguments, String paramName) {
        if (arguments == null || arguments.isEmpty()) return null;
        String searchKey = "\"" + paramName + "\"";
        int keyIndex = arguments.indexOf(searchKey);
        if (keyIndex == -1) return null;
        int colonIndex = arguments.indexOf(":", keyIndex);
        if (colonIndex == -1) return null;
        int startIndex = colonIndex + 1;
        while (startIndex < arguments.length() &&
               (arguments.charAt(startIndex) == ' ' || arguments.charAt(startIndex) == '"')) {
            startIndex++;
        }
        int endIndex = arguments.indexOf('"', startIndex);
        if (endIndex == -1) endIndex = arguments.indexOf('}', startIndex);
        if (endIndex == -1 || endIndex <= startIndex) return null;
        return arguments.substring(startIndex, endIndex).trim();
    }
}
