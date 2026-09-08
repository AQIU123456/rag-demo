<template>
  <div class="chat-container">
    <header class="chat-header">
      <h1>🛠️ AI Tool Assistant</h1>
      <p class="subtitle">支持天气查询、订单查询等工具调用</p>
    </header>
    
    <div class="messages-container" ref="messagesContainer">
      <div 
        v-for="(message, index) in messages" 
        :key="index"
        :class="['message', message.role]"
      >
        <div class="message-avatar">{{ message.role === 'user' ? '👤' : '🤖' }}</div>
        <div class="message-body">
          <div class="message-content">{{ message.content }}</div>
          
          <!-- 工具调用展示 -->
          <div v-if="message.toolCalls && message.toolCalls.length > 0" class="tool-calls-section">
            <div 
              v-for="(toolCall, tcIndex) in message.toolCalls" 
              :key="tcIndex"
              class="tool-call-card"
            >
              <div class="tool-call-header">
                <span class="tool-icon">🔧</span>
                <span class="tool-name">{{ toolCall.name }}</span>
              </div>
              <div class="tool-call-args">
                <strong>参数:</strong>
                <pre>{{ JSON.stringify(toolCall.arguments, null, 2) }}</pre>
              </div>
              <div v-if="toolCall.result" class="tool-call-result">
                <strong>结果:</strong>
                <pre>{{ formatToolResult(toolCall.result) }}</pre>
              </div>
            </div>
          </div>
          
          <div class="message-time">{{ formatTime(message.createdAt) }}</div>
        </div>
      </div>
      <div v-if="loading" class="message assistant">
        <div class="message-avatar">🤖</div>
        <div class="message-body">
          <div class="message-content thinking">
            <span class="thinking-dots">
              <span></span><span></span><span></span>
            </span>
            {{ toolCallingStatus }}
          </div>
        </div>
      </div>
    </div>
    
    <div class="input-container">
      <textarea 
        v-model="userInput" 
        @keyup.enter="sendMessage"
        placeholder="试试问：北京天气如何？或 查询订单 ORD001"
        rows="3"
      ></textarea>
      <button @click="sendMessage" :disabled="loading || !userInput.trim()">
        发送
      </button>
    </div>
    
    <div class="tools-hint">
      <strong>💡 可用工具：</strong>
      <span class="hint-tag">天气查询</span>
      <span class="hint-tag">订单查询</span>
    </div>
  </div>
</template>

<script>
import axios from 'axios'

export default {
  name: 'ToolChat',
  data() {
    return {
      userInput: '',
      messages: [],
      loading: false,
      sessionId: null,
      toolCallingStatus: '正在思考...'
    }
  },
  mounted() {
    this.sessionId = localStorage.getItem('toolChatSessionId')
    if (this.sessionId) {
      this.loadChatHistory()
    }
  },
  methods: {
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
      this.toolCallingStatus = '正在分析意图...'
      this.scrollToBottom()
      
      try {
        const response = await axios.post('/api/chat/tools', {
          sessionId: this.sessionId,
          message: message
        })
        
        this.sessionId = response.data.sessionId
        localStorage.setItem('toolChatSessionId', this.sessionId)
        
        // 处理响应，可能包含工具调用信息
        const assistantMessage = {
          role: 'assistant',
          content: response.data.message,
          createdAt: response.data.timestamp,
          toolCalls: response.data.toolCalls || []
        }
        
        this.messages.push(assistantMessage)
      } catch (error) {
        console.error('Error sending message:', error)
        this.messages.push({
          role: 'assistant',
          content: '抱歉，出错了。请检查后端服务是否正常运行。\n错误信息：' + (error.response?.data?.message || error.message),
          createdAt: new Date().toISOString()
        })
      } finally {
        this.loading = false
        this.toolCallingStatus = '正在思考...'
        this.scrollToBottom()
      }
    },
    
    async loadChatHistory() {
      try {
        const response = await axios.get(`/api/chat/tools/${this.sessionId}`)
        this.messages = response.data.map(msg => ({
          role: msg.role.toLowerCase(),
          content: msg.content,
          createdAt: msg.createdAt,
          toolCalls: msg.toolCalls || []
        }))
        this.scrollToBottom()
      } catch (error) {
        console.error('Error loading history:', error)
      }
    },
    
    formatToolResult(result) {
      if (typeof result === 'string') {
        return result
      }
      return JSON.stringify(result, null, 2)
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
  max-width: 900px;
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
  margin-bottom: 8px;
}

.subtitle {
  font-size: 14px;
  opacity: 0.9;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #f9f9f9;
}

.message {
  margin-bottom: 24px;
  display: flex;
  gap: 12px;
}

.message.user {
  flex-direction: row-reverse;
}

.message-avatar {
  font-size: 28px;
  flex-shrink: 0;
}

.message-body {
  max-width: 75%;
  display: flex;
  flex-direction: column;
}

.message-content {
  padding: 14px 18px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 15px;
}

.message.user .message-content {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-bottom-right-radius: 4px;
}

.message.assistant .message-content {
  background: white;
  color: #333;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}

.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #666;
}

.thinking-dots {
  display: flex;
  gap: 4px;
}

.thinking-dots span {
  width: 8px;
  height: 8px;
  background: #667eea;
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out both;
}

.thinking-dots span:nth-child(1) { animation-delay: -0.32s; }
.thinking-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* 工具调用卡片样式 */
.tool-calls-section {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tool-call-card {
  background: #f8f9fa;
  border: 1px solid #e9ecef;
  border-radius: 8px;
  padding: 12px;
  font-size: 13px;
}

.tool-call-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e9ecef;
}

.tool-icon {
  font-size: 16px;
}

.tool-name {
  font-weight: 600;
  color: #667eea;
}

.tool-call-args,
.tool-call-result {
  margin-top: 6px;
}

.tool-call-args pre,
.tool-call-result pre {
  background: white;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
  margin-top: 4px;
  border: 1px solid #e9ecef;
}

.tool-call-args strong,
.tool-call-result strong {
  color: #666;
  font-size: 12px;
}

.message-time {
  font-size: 11px;
  color: #999;
  margin-top: 6px;
  padding: 0 4px;
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
  padding: 14px;
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
  padding: 14px 28px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s, transform 0.1s;
}

button:hover:not(:disabled) {
  opacity: 0.9;
  transform: translateY(-1px);
}

button:active:not(:disabled) {
  transform: translateY(0);
}

button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.tools-hint {
  padding: 12px 20px;
  background: #f8f9fa;
  border-top: 1px solid #e9ecef;
  font-size: 13px;
  color: #666;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.hint-tag {
  background: white;
  padding: 4px 10px;
  border-radius: 12px;
  border: 1px solid #e0e0e0;
  color: #667eea;
  font-size: 12px;
}
</style>
