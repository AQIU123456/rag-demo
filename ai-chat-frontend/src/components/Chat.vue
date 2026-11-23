<template>
  <div class="chat-container">
    <header class="chat-header">
      <h1>AI Chat Assistant</h1>
    </header>
    
    <div class="messages-container" ref="messagesContainer">
      <div 
        v-for="(message, index) in messages" 
        :key="index"
        :class="['message', message.role]"
      >
        <!-- 用户消息保持纯文本；AI 回答按 Markdown 渲染 -->
        <div v-if="message.role === 'user'" class="message-content">{{ message.content }}</div>
        <div v-else class="message-content markdown-body" v-html="renderMarkdown(message.content)"></div>
        <!-- 重试按钮：仅最后一条 AI 回答、非加载中时显示 -->
        <button
          v-if="index === messages.length - 1 && message.role === 'assistant' && !loading"
          class="retry-btn"
          :disabled="message.retrying"
          @click="retryMessage(index)"
        >
          {{ message.retrying ? '重试中…' : '🔄 重试' }}
        </button>
        <div class="message-time">{{ formatTime(message.createdAt) }}</div>
      </div>
      <div v-if="loading" class="message assistant">
        <div class="message-content">Thinking...</div>
      </div>
    </div>
    
    <div class="input-container">
      <textarea 
        v-model="userInput" 
        @keyup.enter="sendMessage"
        placeholder="Type your message..."
        rows="3"
      ></textarea>
      <button @click="sendMessage" :disabled="loading || !userInput.trim()">
        Send
      </button>
    </div>
  </div>
</template>

<script>
import axios from 'axios'
import { renderMarkdown } from '../utils/markdown'

export default {
  name: 'Chat',
  data() {
    return {
      userInput: '',
      messages: [],
      loading: false,
      sessionId: null
    }
  },
  mounted() {
    this.sessionId = localStorage.getItem('chatSessionId')
    if (this.sessionId) {
      this.loadChatHistory()
    }
  },
  methods: {
    renderMarkdown,
    async sendMessage() {
      if (!this.userInput.trim() || this.loading) return
      
      const message = this.userInput.trim()
      this.userInput = ''
      
      this.messages.push({
        role: 'user',
        content: message,
        createdAt: new Date().toISOString()
      })
      
      this.loading = true
      this.scrollToBottom()
      
      try {
        const response = await axios.post('/api/chat', {
          sessionId: this.sessionId,
          message: message
        })
        
        this.sessionId = response.data.sessionId
        localStorage.setItem('chatSessionId', this.sessionId)
        
        this.messages.push({
          role: 'assistant',
          content: response.data.message,
          createdAt: response.data.timestamp,
          question: message,
          retrying: false
        })
      } catch (error) {
        console.error('Error sending message:', error)
        this.messages.push({
          role: 'assistant',
          content: 'Sorry, something went wrong. Please try again.',
          createdAt: new Date().toISOString()
        })
      } finally {
        this.loading = false
        this.scrollToBottom()
      }
    },
    
    /**
     * 重试最后一条回答：调用 regenerate 端点原地覆盖，不新增用户消息/对话气泡。
     */
    async retryMessage(index) {
      const msg = this.messages[index]
      if (!msg || !msg.question || msg.retrying || this.loading) return

      msg.retrying = true
      this.loading = true
      try {
        const response = await axios.post('/api/chat/regenerate', {
          sessionId: this.sessionId,
          question: msg.question
        })
        msg.content = response.data.message
        msg.createdAt = response.data.timestamp
      } catch (error) {
        console.error('重试失败:', error)
        msg.content = '重试失败，请稍后再试。' + (error.response?.data?.message || '')
      } finally {
        msg.retrying = false
        this.loading = false
        this.scrollToBottom()
      }
    },

    async loadChatHistory() {
      try {
        const response = await axios.get(`/api/chat/${this.sessionId}`)
        let lastQuestion = ''
        this.messages = response.data.map(msg => {
          const role = msg.role.toLowerCase()
          if (role === 'user') lastQuestion = msg.content
          return {
            role,
            content: msg.content,
            createdAt: msg.createdAt,
            // AI 消息按其前一条用户消息补全 question，保证刷新后仍可重试
            question: role === 'assistant' ? lastQuestion : undefined,
            retrying: false
          }
        })
        this.scrollToBottom()
      } catch (error) {
        console.error('Error loading history:', error)
      }
    },
    
    formatTime(timestamp) {
      if (!timestamp) return ''
      const date = new Date(timestamp)
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    },
    
    scrollToBottom() {
      this.$nextTick(() => {
        const container = this.$refs.messagesContainer
        if (container) {
          container.scrollTop = container.scrollHeight
        }
      })
    }
  }
}
</script>

<style scoped>
.chat-container {
  max-width: 800px;
  margin: 0 auto;
  height: calc(100vh - 80px);
  display: flex;
  flex-direction: column;
  background: white;
  box-shadow: 0 0 20px rgba(0,0,0,0.1);
}

.chat-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 20px;
  text-align: center;
}

.chat-header h1 {
  font-size: 24px;
  font-weight: 600;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.message {
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
}

.message.user {
  align-items: flex-end;
}

.message.assistant {
  align-items: flex-start;
}

.message-content {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.5;
}

.message.user .message-content {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-bottom-right-radius: 4px;
}

.message.assistant .message-content {
  background: #f0f0f0;
  color: #333;
  border-bottom-left-radius: 4px;
}

.message-time {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
  padding: 0 8px;
}

.retry-btn {
  align-self: flex-start;
  margin-top: 6px;
  padding: 5px 12px;
  background: #f0f0f5;
  color: #667eea;
  border: 1px solid #e0e0ea;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 400;
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

.input-container {
  padding: 20px;
  border-top: 1px solid #e0e0e0;
  display: flex;
  gap: 10px;
  background: white;
}

textarea {
  flex: 1;
  padding: 12px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  resize: none;
  font-family: inherit;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

textarea:focus {
  border-color: #667eea;
}

button {
  padding: 12px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;
}

button:hover:not(:disabled) {
  opacity: 0.9;
}

button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
