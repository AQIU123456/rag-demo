# AI Chat Application

一个最小可执行的 AI 聊天应用，包含前后端。

## 项目结构

```
/workspace
├── ai-chat-backend/          # Spring Boot 后端
│   ├── src/main/java/
│   │   └── com/example/aichat/
│   │       ├── AiChatApplication.java      # 主启动类
│   │       ├── controller/
│   │       │   └── ChatController.java     # REST API 控制器
│   │       ├── dto/
│   │       │   ├── ChatRequest.java        # 请求 DTO
│   │       │   └── ChatResponse.java       # 响应 DTO
│   │       ├── entity/
│   │       │   └── ChatMessage.java        # 消息实体
│   │       ├── repository/
│   │       │   └── ChatMessageRepository.java  # 数据访问层
│   │       └── service/
│   │           └── ChatService.java        # 业务逻辑层
│   ├── src/main/resources/
│   │   └── application.yml                 # 应用配置
│   └── pom.xml                             # Maven 配置
│
└── ai-chat-frontend/         # Vue3 前端
    ├── index.html                          # HTML 入口
    ├── package.json                        # NPM 配置
    ├── vite.config.js                      # Vite 配置
    ├── src/
    │   ├── main.js                         # JS 入口
    │   └── App.vue                         # 主组件
    └── README.md
```

## 技术栈

### 后端
- JDK 17
- Spring Boot 3.2
- LangChain4j 0.29.1
- MySQL 8
- Spring Data JPA
- Lombok

### 前端
- Vue 3
- Vite 5
- Axios

## 快速开始

### 1. 数据库准备

```bash
# 创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ai_chat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

### 2. 配置 OpenAI API Key

```bash
export OPENAI_API_KEY=your-openai-api-key
```

或者在 `ai-chat-backend/src/main/resources/application.yml` 中直接配置。

### 3. 启动后端

```bash
cd ai-chat-backend
mvn spring-boot:run
```

后端将在 http://localhost:8080 运行

### 4. 启动前端

```bash
cd ai-chat-frontend
npm install
npm run dev
```

前端将在 http://localhost:3000 运行

## API 接口

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /api/chat | 发送消息并获取 AI 回复 |
| GET | /api/chat/{sessionId} | 获取指定会话的聊天记录 |
| DELETE | /api/chat/{sessionId} | 删除指定会话的聊天记录 |

## 注意事项

1. 确保 MySQL 服务已启动
2. 需要有效的 OpenAI API Key 才能使用 AI 聊天功能
3. 前端通过 Vite 代理将 API 请求转发到后端
