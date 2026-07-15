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
        <div class="message-content">{{ message.content }}</div>
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

export default {
  name: 'App',
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
          createdAt: response.data.timestamp
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
    
    async loadChatHistory() {
      try {
        const response = await axios.get(`/api/chat/${this.sessionId}`)
        this.messages = response.data.map(msg => ({
          role: msg.role.toLowerCase(),
          content: msg.content,
          createdAt: msg.createdAt
        }))
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

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
  background: #f5f5f5;
}

.chat-container {
  max-width: 800px;
  margin: 0 auto;
  height: 100vh;
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
