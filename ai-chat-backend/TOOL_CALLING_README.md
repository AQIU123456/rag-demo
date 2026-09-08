# Tool Calling 功能说明

## 功能概述

Tool Calling（工具调用）是大语言模型的核心能力之一，允许 AI 主动调用外部工具或函数来获取信息、执行操作，而不是仅依赖训练数据。

本项目实现了基于 LangChain4j 的 Tool Calling 功能，支持以下工具：

### 已实现的工具

#### 1. 天气查询工具 (WeatherTool)
- **方法**: `getWeather(String city)`
- **功能**: 查询指定城市的天气信息（Mock 数据）
- **返回**: 包含天气状况、温度、湿度、风力等信息
- **示例**: "北京今天天气怎么样？" → AI 自动调用天气查询工具

#### 2. 订单查询工具 (OrderTool)
- **方法 1**: `getOrderInfo(String orderId)` - 根据订单号查询订单详情
- **方法 2**: `getOrdersByCustomer(String customerName)` - 根据客户姓名查询订单列表
- **功能**: Mock 订单数据查询
- **预置订单**:
  - ORD001: 张三，已发货，¥299.00
  - ORD002: 李四，配送中，¥599.00
  - ORD003: 王五，已完成，¥1299.00
  - ORD004: 赵六，待付款，¥89.00
  - ORD005: 钱七，已取消，¥459.00

## 技术实现

### 核心组件

```
src/main/java/com/example/aichat/
├── tool/
│   ├── WeatherTool.java      # 天气查询工具
│   └── OrderTool.java        # 订单查询工具
├── service/
│   └── ToolCallingChatService.java  # Tool Calling 聊天服务
└── controller/
    └── ToolCallingController.java   # Tool Calling REST API
```

### 工作流程

1. **用户提问** → 发送请求到 `/api/chat/tools`
2. **AI 分析** → 判断是否需要调用工具
3. **工具执行** → 如果需要，调用相应的 @Tool 方法
4. **结果整合** → AI 根据工具执行结果生成最终回复
5. **返回响应** → 将完整的回答返回给用户

## API 接口

### 1. Tool Calling 聊天接口

**请求**: `POST /api/chat/tools`

**请求体**:
```json
{
  "message": "北京今天天气怎么样？",
  "sessionId": "optional-session-id"
}
```

**响应**:
```json
{
  "sessionId": "xxx-xxx-xxx",
  "message": "北京今天天气晴朗，温度 25°C...",
  "timestamp": "2024-01-17T10:30:00"
}
```

### 2. 获取可用工具列表

**请求**: `GET /api/chat/tools/list`

**响应**:
```json
{
  "success": true,
  "tools": [
    {
      "name": "getWeather",
      "title": "天气查询",
      "description": "查询指定城市的天气信息",
      "parameters": "city: 城市名称"
    },
    {
      "name": "getOrderInfo",
      "title": "订单查询",
      "description": "根据订单号查询订单详细信息",
      "parameters": "orderId: 订单号"
    },
    {
      "name": "getOrdersByCustomer",
      "title": "客户订单查询",
      "description": "根据客户姓名查询其所有订单",
      "parameters": "customerName: 客户姓名"
    }
  ]
}
```

## 使用示例

### 示例 1: 天气查询

**请求**:
```bash
curl -X POST http://localhost:8080/api/chat/tools \
  -H "Content-Type: application/json" \
  -d '{"message": "上海今天天气如何？"}'
```

**可能的响应**:
```
上海今天天气多云，温度 22°C，湿度 65%，风力 3 级。建议您外出时携带雨具，以防万一。
```

### 示例 2: 订单查询

**请求**:
```bash
curl -X POST http://localhost:8080/api/chat/tools \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我查一下订单 ORD001 的状态"}'
```

**可能的响应**:
```
您的订单 ORD001 详情如下：
- 收货人：张三
- 订单状态：已发货
- 订单金额：¥299.00
- 下单日期：2024-01-15

该订单已经发货，预计 2-3 天内送达。
```

### 示例 3: 客户订单查询

**请求**:
```bash
curl -X POST http://localhost:8080/api/chat/tools \
  -H "Content-Type: application/json" \
  -d '{"message": "查看张三的所有订单"}'
```

### 示例 4: 普通对话（不使用工具）

**请求**:
```bash
curl -X POST http://localhost:8080/api/chat/tools \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，介绍一下你自己"}'
```

AI 会识别此问题不需要调用工具，直接进行对话回复。

## 扩展开发

### 添加新工具

1. 在 `tool` 包下创建新的工具类
2. 使用 `@Component` 注解标记为 Spring Bean
3. 使用方法级别的 `@Tool` 注解标记可调用方法
4. 在 `ToolCallingChatService` 中注册新工具

**示例**:
```java
@Component
public class CalculatorTool {
    
    @Tool("执行数学计算，支持加减乘除")
    public String calculate(String expression) {
        // 实现计算逻辑
        return result;
    }
}
```

然后在 `ToolCallingChatService` 中添加：
```java
toolSpecifications.addAll(toolSpecificationsFrom(calculatorTool));
```

## 注意事项

1. **Mock 数据**: 当前天气和订单数据均为 Mock 数据，实际使用时需要替换为真实 API
2. **参数解析**: 当前使用简单的字符串解析，建议在生产环境使用 JSON 解析库
3. **错误处理**: 需要增强异常处理和边界情况处理
4. **安全性**: 工具调用应进行权限验证和输入校验

## 依赖要求

- Java 17+
- Spring Boot 3.2.0
- LangChain4j 0.29.1
- OpenAI API Key（或其他支持的 LLM）

## 配置

在 `application.yml` 或 `application.properties` 中配置：

```yaml
langchain4j:
  open-ai:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-3.5-turbo
```
