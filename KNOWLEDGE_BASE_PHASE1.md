# 企业级知识库 - 阶段 1：纯文本文档管理

## 实现概述

本阶段实现了知识库管理的基础功能，支持纯文本文档的上传、管理和搜索。

## 已实现功能

### 后端 (Spring Boot)

#### 数据模型
- `KnowledgeDocument` 实体：存储文档标题、内容、描述、文件类型、大小、字数等元数据

#### API 接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/knowledge/upload` | 上传纯文本文档 |
| GET | `/api/knowledge/documents` | 获取文档列表（分页） |
| GET | `/api/knowledge/documents/{id}` | 获取文档详情 |
| GET | `/api/knowledge/search` | 搜索文档 |
| PUT | `/api/knowledge/documents/{id}` | 更新文档 |
| DELETE | `/api/knowledge/documents/{id}` | 删除文档 |

#### 核心类
- `KnowledgeDocument` - 文档实体
- `KnowledgeDocumentRepository` - 数据访问层
- `KnowledgeDocumentService` - 业务逻辑层
- `KnowledgeDocumentController` - REST API 控制器
- `DocumentUploadRequest` / `DocumentResponse` - DTO 数据传输对象

### 前端 (Vue 3)

#### 组件结构
```
src/
├── App.vue                    # 主应用（导航切换）
└── components/
    ├── Chat.vue              # 原有聊天功能
    └── KnowledgeBase.vue     # 知识库管理界面
```

#### 功能特性
- 📄 文档上传（纯文本输入）
- 📋 文档列表展示（分页）
- 🔍 全文搜索（标题 + 内容）
- ✏️ 文档编辑
- 🗑️ 文档删除
- 👁️ 文档详情查看
- 📊 元数据显示（字数、大小、创建时间）

## 技术栈

- **后端**: Spring Boot 3.2 + JPA + MySQL
- **前端**: Vue 3 + Vite + Axios
- **数据库**: MySQL (自动创建 `knowledge_documents` 表)

## 快速开始

### 1. 数据库准备
确保 MySQL 运行，并创建数据库：
```sql
CREATE DATABASE ai_chat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置数据库连接
编辑 `ai-chat-backend/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_chat?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root
    password: your_password
```

### 3. 启动后端
```bash
cd ai-chat-backend
mvn spring-boot:run
```

### 4. 启动前端
```bash
cd ai-chat-frontend
npm install
npm run dev
```

### 5. 访问应用
打开浏览器访问 `http://localhost:5173`，点击"知识库"标签页。

## API 使用示例

### 上传文档
```bash
curl -X POST http://localhost:8080/api/knowledge/upload \
  -H "Content-Type: application/json" \
  -d '{
    "title": "测试文档",
    "description": "这是一个测试文档",
    "content": "这是文档的正文内容...",
    "fileType": "txt"
  }'
```

### 搜索文档
```bash
curl "http://localhost:8080/api/knowledge/search?keyword=测试&page=0&size=10"
```

## 后续扩展点

代码已预留扩展接口，后续阶段可实现：

1. **阶段 2**: PDF/Word 等多格式文件上传解析
   - 扩展 `uploadPlainText` 方法或新增 `uploadFile` 方法
   - 集成 Apache POI、PDFBox 等解析库

2. **阶段 3**: 向量化处理
   - 添加文本分块逻辑
   - 集成向量嵌入模型

3. **阶段 4**: 向量数据库
   - 集成 Milvus/Pinecone/Weaviate 等

4. **阶段 5**: RAG 检索增强
   - 结合向量搜索和 LLM 生成

## 注意事项

- 当前仅支持纯文本输入，不支持文件上传
- 文档内容存储在数据库 TEXT 字段中
- 搜索使用 SQL LIKE 模糊匹配（后续会升级为向量搜索）
- 暂未实现用户权限控制
