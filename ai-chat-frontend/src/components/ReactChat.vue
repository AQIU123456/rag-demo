<template>
  <div class="react-chat-container">
    <header class="react-header">
      <h2>🧠 ReAct 智能知识库问答</h2>
      <p class="subtitle">基于 ReAct (Reasoning + Acting) 模式的智能推理问答，展示完整思考过程</p>
    </header>

    <!-- 设置区域 -->
    <div class="settings-panel">
      <div class="setting-item">
        <label>
          <span>最大检索结果数：</span>
          <input 
            type="number" 
            v-model.number="maxResults" 
            min="1" 
            max="5" 
            class="setting-input"
          >
        </label>
        <span class="setting-hint">(1-5，控制每次工具调用返回的结果数量)</span>
      </div>
      <div class="setting-item">
        <label class="toggle-label">
          <input 
            type="checkbox" 
            v-model="showReasoning" 
            class="toggle-checkbox"
          >
          <span class="toggle-text">显示推理过程</span>
        </label>
        <span class="setting-hint">(查看 AI 的思考、行动和观察过程)</span>
      </div>
    </div>

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
            <div class="message-text">{{ message.text }}</div>
            
            <!-- ReAct 推理过程展示 -->
            <div v-if="message.reasoningSteps && showReasoning" class="reasoning-section">
              <div class="reasoning-toggle" @click="toggleReasoning(index)">
                <span class="toggle-icon">{{ expandedMessages.has(index) ? '▼' : '▶' }}</span>
                <span class="toggle-text">查看推理过程 ({{ message.reasoningSteps.length }} 步)</span>
              </div>
              
              <div v-show="expandedMessages.has(index)" class="reasoning-steps">
                <div 
                  v-for="(step, stepIndex) in message.reasoningSteps" 
                  :key="stepIndex"
                  :class="['step-item', 'step-' + step.type.toLowerCase()]"
                >
                  <div class="step-header">
                    <span class="step-icon">{{ getStepIcon(step.type) }}</span>
                    <span class="step-title">{{ getStepTitle(step.type) }}</span>
                  </div>
                  
                  <!-- 思考步骤 -->
                  <div v-if="step.type === 'THOUGHT'" class="step-content thought-content">
                    {{ step.content }}
                  </div>
                  
                  <!-- 行动步骤 -->
                  <div v-if="step.type === 'ACTION'" class="step-content action-content">
                    <div class="action-details">
                      <div class="action-name">
                        <strong>工具:</strong> {{ formatActionName(step.actionName) }}
                      </div>
                      <div v-if="step.arguments && Object.keys(step.arguments).length > 0" class="action-args">
                        <strong>参数:</strong>
                        <code>{{ JSON.stringify(step.arguments, null, 2) }}</code>
                      </div>
                    </div>
                  </div>
                  
                  <!-- 观察步骤（包含在行动步骤中） -->
                  <div v-if="step.result" class="step-content observation-content">
                    <div class="observation-label">👁️ 观察结果:</div>
                    <div class="observation-text">{{ truncateText(step.result, 300) }}</div>
                    <button 
                      v-if="step.result.length > 300"
                      @click="viewFullResult(index, stepIndex)"
                      class="view-more-btn"
                    >
                      查看全文
                    </button>
                  </div>
                  
                  <!-- 最终答案 -->
                  <div v-if="step.type === 'FINAL_ANSWER'" class="step-content final-answer-content">
                    {{ step.content }}
                  </div>
                </div>
                
                <!-- 格式化推理过程 -->
                <div v-if="message.formattedReasoning" class="formatted-reasoning">
                  <h4>📝 完整推理链</h4>
                  <pre>{{ message.formattedReasoning }}</pre>
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
            <div class="loading-text">{{ loadingText }}</div>
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
          placeholder="请输入您的问题，例如：公司的请假流程是什么？如果需要请病假有什么特殊要求？"
          rows="3"
          class="question-input"
        ></textarea>
        <button 
          @click="sendQuestion" 
          :disabled="!question.trim() || loading"
          class="send-btn"
        >
          {{ loading ? '推理中...' : '发送' }}
        </button>
      </div>
      
      <!-- 示例问题 -->
      <div class="example-questions">
        <div class="examples-title">💡 试试这些问题：</div>
        <div class="examples-list">
          <button 
            v-for="(example, index) in exampleQuestions" 
            :key="index"
            @click="useExample(example)"
            class="example-btn"
          >
            {{ example }}
          </button>
        </div>
      </div>
    </div>

    <!-- 查看全文弹窗 -->
    <div v-if="showFullResult" class="modal-overlay" @click="closeFullResult">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <h3>👁️ 完整观察结果</h3>
          <button @click="closeFullResult" class="close-btn">×</button>
        </div>
        <div class="modal-body">
          <pre>{{ currentFullResult }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ReactChat',
  
  data() {
    return {
      question: '',
      messages: [],
      loading: false,
      loadingText: '正在思考...',
      maxResults: 3,
      showReasoning: true,
      expandedMessages: new Set(),
      showFullResult: false,
      currentFullResult: '',
      exampleQuestions: [
        '公司的请假流程是什么？如果需要请病假有什么特殊要求？',
        '我想请一周病假，需要准备什么材料？会影响年终奖吗？',
        '查一下订单 ORD001 的状态，再看看北京的天气怎么样？',
        '公司的报销政策是怎样的？出差补贴标准是多少？',
        '年假有多少天？没休完的年假可以折现吗？'
      ]
    };
  },
  
  methods: {
    async sendQuestion() {
      if (!this.question.trim() || this.loading) return;
      
      const userQuestion = this.question.trim();
      
      // 添加用户消息
      this.messages.push({
        role: 'user',
        text: userQuestion
      });
      
      this.question = '';
      this.loading = true;
      this.loadingText = '正在思考...';
      
      try {
        const response = await fetch('http://localhost:8080/api/chat/react', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            question: userQuestion,
            maxResults: this.maxResults,
            showReasoning: this.showReasoning
          })
        });
        
        if (!response.ok) {
          throw new Error('请求失败');
        }
        
        const data = await response.json();
        
        if (data.success) {
          // 解析推理步骤
          let reasoningSteps = [];
          if (data.reasoningSteps && Array.isArray(data.reasoningSteps)) {
            reasoningSteps = data.reasoningSteps.map(step => ({
              type: step.type,
              content: step.content,
              actionName: step.actionName,
              arguments: step.arguments,
              result: step.result
            }));
          }
          
          // 添加 AI 回复消息
          this.messages.push({
            role: 'assistant',
            text: data.answer,
            reasoningSteps: reasoningSteps,
            formattedReasoning: data.formattedReasoning,
            originalQuestion: data.originalQuestion
          });
          
          // 自动展开第一条消息的推理过程
          this.expandedMessages.add(this.messages.length - 1);
        } else {
          this.messages.push({
            role: 'assistant',
            text: '抱歉，处理您的问题时出现错误：' + (data.message || '未知错误')
          });
        }
      } catch (error) {
        console.error('ReAct 问答错误:', error);
        this.messages.push({
          role: 'assistant',
          text: '抱歉，发生错误：' + error.message
        });
      } finally {
        this.loading = false;
        this.loadingText = '正在思考...';
        this.scrollToBottom();
      }
    },
    
    useExample(example) {
      this.question = example;
    },
    
    toggleReasoning(messageIndex) {
      if (this.expandedMessages.has(messageIndex)) {
        this.expandedMessages.delete(messageIndex);
      } else {
        this.expandedMessages.add(messageIndex);
      }
    },
    
    viewFullResult(messageIndex, stepIndex) {
      const step = this.messages[messageIndex].reasoningSteps[stepIndex];
      if (step && step.result) {
        this.currentFullResult = step.result;
        this.showFullResult = true;
      }
    },
    
    closeFullResult() {
      this.showFullResult = false;
      this.currentFullResult = '';
    },
    
    getStepIcon(type) {
      const icons = {
        'THOUGHT': '💭',
        'ACTION': '🔧',
        'OBSERVATION': '👁️',
        'FINAL_ANSWER': '✅'
      };
      return icons[type] || '📌';
    },
    
    getStepTitle(type) {
      const titles = {
        'THOUGHT': '思考',
        'ACTION': '行动',
        'OBSERVATION': '观察',
        'FINAL_ANSWER': '最终答案'
      };
      return titles[type] || type;
    },
    
    formatActionName(actionName) {
      const names = {
        'knowledge_search': '知识库检索',
        'weather_query': '天气查询',
        'order_query': '订单查询'
      };
      return names[actionName] || actionName;
    },
    
    truncateText(text, maxLength) {
      if (!text) return '';
      if (text.length <= maxLength) return text;
      return text.substring(0, maxLength) + '...';
    },
    
    scrollToBottom() {
      this.$nextTick(() => {
        const chatArea = document.querySelector('.chat-area');
        if (chatArea) {
          chatArea.scrollTop = chatArea.scrollHeight;
        }
      });
    }
  }
};
</script>

<style scoped>
.react-chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.react-header {
  padding: 20px;
  background: rgba(255, 255, 255, 0.95);
  border-bottom: 1px solid #e0e0e0;
}

.react-header h2 {
  margin: 0 0 8px 0;
  color: #333;
  font-size: 24px;
}

.subtitle {
  margin: 0;
  color: #666;
  font-size: 14px;
}

/* 设置面板 */
.settings-panel {
  padding: 15px 20px;
  background: rgba(255, 255, 255, 0.9);
  border-bottom: 1px solid #e0e0e0;
}

.setting-item {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.setting-item:last-child {
  margin-bottom: 0;
}

.setting-input {
  width: 60px;
  padding: 5px;
  border: 1px solid #ddd;
  border-radius: 4px;
  margin-left: 5px;
}

.toggle-label {
  display: flex;
  align-items: center;
  cursor: pointer;
}

.toggle-checkbox {
  margin-right: 8px;
}

.setting-hint {
  font-size: 12px;
  color: #888;
  margin-left: auto;
}

/* 聊天区域 */
.chat-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.messages-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.message {
  display: flex;
  gap: 12px;
  max-width: 85%;
}

.message.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message-avatar {
  font-size: 24px;
  flex-shrink: 0;
}

.message-content {
  background: white;
  padding: 15px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.message.user .message-content {
  background: #667eea;
  color: white;
}

.message-text {
  line-height: 1.6;
  margin-bottom: 15px;
}

/* 推理过程区域 */
.reasoning-section {
  border-top: 1px solid #e0e0e0;
  padding-top: 12px;
  margin-top: 12px;
}

.reasoning-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #667eea;
  font-size: 14px;
  padding: 8px;
  border-radius: 6px;
  background: #f5f7ff;
  transition: all 0.2s;
}

.reasoning-toggle:hover {
  background: #e8ecff;
}

.toggle-icon {
  font-size: 12px;
}

.reasoning-steps {
  margin-top: 12px;
  padding: 12px;
  background: #f8f9fa;
  border-radius: 8px;
}

.step-item {
  margin-bottom: 15px;
  padding: 12px;
  border-radius: 8px;
  border-left: 4px solid #667eea;
}

.step-item.step-thought {
  background: #fff3cd;
  border-left-color: #ffc107;
}

.step-item.step-action {
  background: #d1ecf1;
  border-left-color: #17a2b8;
}

.step-item.step-observation {
  background: #d4edda;
  border-left-color: #28a745;
}

.step-item.step-final_answer {
  background: #f8d7da;
  border-left-color: #dc3545;
}

.step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: bold;
}

.step-icon {
  font-size: 18px;
}

.step-title {
  font-size: 14px;
  color: #333;
}

.step-content {
  font-size: 13px;
  line-height: 1.6;
  color: #555;
}

.thought-content {
  font-style: italic;
}

.action-details {
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.action-args {
  margin-top: 6px;
}

.action-args code {
  display: block;
  background: #fff;
  padding: 8px;
  border-radius: 4px;
  margin-top: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}

.observation-label {
  font-weight: bold;
  margin-bottom: 6px;
}

.observation-text {
  font-family: 'Courier New', monospace;
  font-size: 12px;
  background: #fff;
  padding: 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}

.view-more-btn {
  margin-top: 6px;
  padding: 4px 12px;
  font-size: 12px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.view-more-btn:hover {
  background: #5568d3;
}

.formatted-reasoning {
  margin-top: 15px;
  padding-top: 15px;
  border-top: 1px dashed #ccc;
}

.formatted-reasoning h4 {
  margin: 0 0 10px 0;
  font-size: 14px;
  color: #333;
}

.formatted-reasoning pre {
  background: #fff;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow-y: auto;
}

/* 加载指示器 */
.loading .message-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.typing-indicator {
  display: flex;
  gap: 4px;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  background: #667eea;
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
  0%, 100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-4px);
  }
}

.loading-text {
  font-size: 13px;
  color: #666;
}

/* 输入区域 */
.input-area {
  padding: 20px;
  background: rgba(255, 255, 255, 0.95);
  border-top: 1px solid #e0e0e0;
}

.input-container {
  display: flex;
  gap: 12px;
  margin-bottom: 15px;
}

.question-input {
  flex: 1;
  padding: 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  resize: none;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
}

.question-input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.send-btn {
  padding: 12px 24px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.2s;
}

.send-btn:hover:not(:disabled) {
  background: #5568d3;
  transform: translateY(-1px);
}

.send-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* 示例问题 */
.example-questions {
  margin-top: 10px;
}

.examples-title {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.examples-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.example-btn {
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid #667eea;
  border-radius: 20px;
  font-size: 13px;
  color: #667eea;
  cursor: pointer;
  transition: all 0.2s;
}

.example-btn:hover {
  background: #667eea;
  color: white;
}

/* 弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 12px;
  max-width: 800px;
  max-height: 80vh;
  width: 90%;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px 20px;
  border-bottom: 1px solid #e0e0e0;
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  color: #333;
}

.close-btn {
  background: none;
  border: none;
  font-size: 28px;
  cursor: pointer;
  color: #999;
  line-height: 1;
}

.close-btn:hover {
  color: #333;
}

.modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.modal-body pre {
  background: #f8f9fa;
  padding: 15px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
