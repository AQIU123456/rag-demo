# AI Chat Backend

基于 Spring Boot 3 + LangChain4j 的 AI 聊天应用后端。

## 技术栈
- JDK 17
- Spring Boot 3
- LangChain4j
- MySQL 8
- Spring Data JPA

## 配置

### 数据库配置
在 `src/main/resources/application.yml` 中配置 MySQL 连接：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_chat
    username: root
    password: your_password
```

### OpenAI API Key 配置
设置环境变量或直接在配置文件中设置：

```bash
export OPENAI_API_KEY=your-api-key-here
```

或在 `application.yml` 中修改：
```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: your-api-key-here
```

## 运行

```bash
# 确保 MySQL 已启动并创建了 ai_chat 数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ai_chat;"

# 运行应用
mvn spring-boot:run
```

## API 端点

- `POST /api/chat` - 发送消息并获取 AI 回复
- `GET /api/chat/{sessionId}` - 获取聊天记录
- `DELETE /api/chat/{sessionId}` - 删除聊天记录

## 构建

```bash
mvn clean package
```
