<template>
  <div id="app">
    <nav class="app-nav">
      <button 
        :class="{ active: currentView === 'chat' }" 
        @click="currentView = 'chat'"
      >
        AI 聊天
      </button>
      <button 
        :class="{ active: currentView === 'knowledge' }" 
        @click="currentView = 'knowledge'"
      >
        知识库
      </button>
    </nav>
    
    <Chat v-if="currentView === 'chat'" />
    <KnowledgeBase v-else />
  </div>
</template>

<script>
import Chat from './components/Chat.vue'
import KnowledgeBase from './components/KnowledgeBase.vue'

export default {
  name: 'AppRoot',
  components: {
    Chat,
    KnowledgeBase
  },
  data() {
    return {
      currentView: 'chat'
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

#app {
  min-height: 100vh;
}

.app-nav {
  display: flex;
  gap: 10px;
  padding: 15px 20px;
  background: white;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.app-nav button {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  background: #f0f0f0;
  color: #666;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.app-nav button.active {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.app-nav button:hover:not(.active) {
  background: #e0e0e0;
}
</style>
