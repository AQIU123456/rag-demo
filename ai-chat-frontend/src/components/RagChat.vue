<template>
  <div class="rag-chat-container">
    <header class="rag-header">
      <h2>📚 AI 知识库问答</h2>
      <p class="subtitle">基于企业知识库的智能问答，支持引用来源标注</p>
    </header>

    <!-- 聊天对话区域 -->
    <div class="chat-area">
      <div class="messages-container">
        <div 
          v-for="(message, index) in messages" 
          :key="index"
          :class="['message', message.role]"
        >
          <div class="message-avatar">
            {{ message.role === 'user' ? '👤' : '🤖' }}
          </div>
          <div class="message-content">
            <!-- 用户消息保持纯文本；AI 回答按 Markdown 渲染 -->
            <div v-if="message.role === 'user'" class="message-text">{{ message.text }}</div>
            <div v-else class="message-text markdown-body" v-html="renderMarkdown(message.text)"></div>

            <!-- 重试按钮：仅最后一条 AI 回答、非加载中时显示 -->
            <button
              v-if="index === messages.length - 1 && message.role === 'assistant' && !loading"
              class="retry-btn"
              :disabled="message.retrying"
              @click="retryMessage(index)"
            >
              {{ message.retrying ? '重试中…' : '🔄 重试' }}
            </button>
            
            <!-- RAG 引用来源展示 -->
            <div v-if="message.citations && message.citations.length > 0" class="citations-section">
              <h4>📖 引用来源</h4>
              <div class="citations-list">
                <div 
                  v-for="(citation, idx) in message.citations" 
                  :key="idx"
                  class="citation-item"
                >
                  <div class="citation-header">
                    <span class="citation-index">[{{ idx + 1 }}]</span>
                    <span class="citation-score">相似度：{{ (citation.score * 100).toFixed(1) }}%</span>
                  </div>
                  <!-- 引用内容是知识库原文片段，同样按 Markdown 渲染（复用同一安全渲染器） -->
                  <div class="citation-content markdown-body" v-html="renderMarkdown(citation.content)"></div>
                  <div class="citation-source" v-if="citation.source">
                    来源：{{ formatSource(citation.source) }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <div v-if="loading" class="message assistant loading">
          <div class="message-avatar">🤖</div>
          <div class="message-content">
            <div class="typing-indicator">
              <span></span><span></span><span></span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <div class="input-container">
        <textarea 
          v-model="question"
          @keyup.enter.exact.prevent="sendQuestion"
          placeholder="请输入您的问题，例如：公司的请假流程是什么？"
          rows="3"
          class="question-input"
        ></textarea>
        <button 
          @click="sendQuestion" 
          :disabled="!question.trim() || loading"
          class="send-btn"
        >
          {{ loading ? '思考中...' : '发送' }}
        </button>
      </div>
      <div class="settings-row">
        <label>
          <span>最大检索结果数：</span>
          <input 
            type="number" 
            v-model.number="maxResults" 
            min="1" 
            max="10"
            class="setting-input"
          />
        </label>
      </div>
    </div>

    <!-- 空状态提示 -->
    <div v-if="messages.length === 0" class="empty-state">
      <div class="empty-icon">📚</div>
      <h3>开始知识库问答</h3>
      <p>在上方输入框中输入问题，AI 将基于知识库内容为您解答</p>
      <div class="example-questions">
        <p>示例问题：</p>
        <button @click="askExample('公司的请假流程是什么？')">公司的请假流程是什么？</button>
        <button @click="askExample('如何申请报销？')">如何申请报销？</button>
        <button @click="askExample('员工福利有哪些？')">员工福利有哪些？</button>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios'
import { renderMarkdown } from '../utils/markdown'

export default {
  name: 'RagChat',
  data() {
    return {
      question: '',
      messages: [],
      loading: false,
      maxResults: 5
    }
  },
  methods: {
    renderMarkdown,
    async sendQuestion() {
      if (!this.question.trim() || this.loading) return
      
      const userQuestion = this.question.trim()
      
      // 添加用户消息
      this.messages.push({
        role: 'user',
        text: userQuestion
      })
      
      this.question = ''
      this.loading = true
      
      try {
        const response = await axios.post('/api/chat/rag', {
          question: userQuestion,
          maxResults: this.maxResults
        })
        
        if (response.data.success) {
          // 添加 AI 回复（记录原问题，供重试复用）
          this.messages.push({
            role: 'assistant',
            text: response.data.answer,
            citations: response.data.citations || [],
            question: userQuestion,
            retrying: false
          })
        } else {
          // 错误处理
          this.messages.push({
            role: 'assistant',
            text: '抱歉，回答失败：' + (response.data.message || '未知错误')
          })
        }
      } catch (error) {
        console.error('RAG 问答失败:', error)
        this.messages.push({
          role: 'assistant',
          text: '抱歉，发生错误：' + (error.response?.data?.message || error.message)
        })
      } finally {
        this.loading = false
      }
    },
    
    /**
     * 重试最后一条回答：复用其原问题重新请求，原地覆盖回答内容，不新增对话气泡。
     */
    async retryMessage(index) {
      const msg = this.messages[index]
      if (!msg || !msg.question || msg.retrying || this.loading) return

      msg.retrying = true
      try {
        const response = await axios.post('/api/chat/rag', {
          question: msg.question,
          maxResults: this.maxResults
        })
        if (response.data.success) {
          msg.text = response.data.answer
          msg.citations = response.data.citations || []
        } else {
          msg.text = '抱歉，重试失败：' + (response.data.message || '未知错误')
        }
      } catch (error) {
        console.error('重试失败:', error)
        msg.text = '抱歉，重试时发生错误：' + (error.response?.data?.message || error.message)
      } finally {
        msg.retrying = false
      }
    },

    askExample(question) {
      this.question = question
      this.sendQuestion()
    },
    
    formatSource(source) {
      if (!source) return '未知来源'
      if (source.startsWith('doc_')) {
        return '文档 ID: ' + source.substring(4)
      }
      return source
    }
  }
}
</script>

<style scoped>
.rag-chat-container {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px;
  height: calc(100vh - 80px);
  display: flex;
  flex-direction: column;
}

.rag-header {
  text-align: center;
  margin-bottom: 20px;
}

.rag-header h2 {
  font-size: 24px;
  color: #333;
  margin-bottom: 8px;
}

.subtitle {
  color: #666;
  font-size: 14px;
}

.chat-area {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 20px;
  padding: 10px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.messages-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.message {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.message.user {
  flex-direction: row-reverse;
}

.message-avatar {
  font-size: 24px;
  flex-shrink: 0;
}

.message-content {
  max-width: 70%;
  background: #f5f5f5;
  padding: 15px;
  border-radius: 12px;
}

.message.user .message-content {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.message-text {
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 引用来源样式 */
.citations-section {
  margin-top: 15px;
  padding-top: 15px;
  border-top: 1px solid rgba(0,0,0,0.1);
}

.citations-section h4 {
  font-size: 14px;
  color: inherit;
  opacity: 0.8;
  margin-bottom: 10px;
}

.citations-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.citation-item {
  background: rgba(0,0,0,0.05);
  padding: 10px;
  border-radius: 8px;
  font-size: 13px;
}

.message.user .citation-item {
  background: rgba(255,255,255,0.2);
}

.citation-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 5px;
  font-weight: 500;
}

.citation-index {
  color: #667eea;
  font-weight: bold;
}

.message.user .citation-index {
  color: white;
}

.citation-score {
  font-size: 12px;
  opacity: 0.7;
}

.citation-content {
  line-height: 1.5;
  margin-bottom: 5px;
  color: inherit;
  opacity: 0.9;
}

.citation-source {
  font-size: 11px;
  opacity: 0.6;
  font-style: italic;
}

/* 重试按钮 */
.retry-btn {
  margin-top: 10px;
  padding: 5px 12px;
  background: #f0f0f5;
  color: #667eea;
  border: 1px solid #e0e0ea;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.retry-btn:hover:not(:disabled) {
  background: #667eea;
  color: white;
  border-color: #667eea;
}

.retry-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 打字动画 */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 10px 0;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  background: #999;
  border-radius: 50%;
  animation: typing 1.4s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 60%, 100% {
    transform: translateY(0);
  }
  30% {
    transform: translateY(-10px);
  }
}

/* 输入区域 */
.input-area {
  background: white;
  padding: 20px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.input-container {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}

.question-input {
  flex: 1;
  padding: 12px 15px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  font-family: inherit;
  resize: none;
  outline: none;
}

.question-input:focus {
  border-color: #667eea;
}

.send-btn {
  padding: 12px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.send-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.settings-row {
  display: flex;
  gap: 15px;
  font-size: 13px;
  color: #666;
}

.setting-input {
  width: 60px;
  padding: 4px 8px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  margin-left: 8px;
}

/* 空状态 */
.empty-state {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
  color: #999;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.empty-state h3 {
  font-size: 20px;
  color: #666;
  margin-bottom: 10px;
}

.example-questions {
  margin-top: 20px;
}

.example-questions p {
  margin-bottom: 10px;
  color: #666;
}

.example-questions button {
  margin: 5px;
  padding: 8px 16px;
  background: #f5f5f5;
  border: 1px solid #e0e0e0;
  border-radius: 20px;
  color: #666;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.example-questions button:hover {
  background: #667eea;
  color: white;
  border-color: #667eea;
}
</style>
