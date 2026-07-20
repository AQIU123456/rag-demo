<template>
  <div class="knowledge-container">
    <header class="knowledge-header">
      <h2>知识库管理</h2>
      <button @click="showUploadModal = true" class="upload-btn">
        + 上传文档
      </button>
    </header>

    <!-- 搜索栏 -->
    <div class="search-bar">
      <input 
        v-model="searchKeyword" 
        @keyup.enter="searchDocuments"
        placeholder="搜索文档标题或内容..."
        class="search-input"
      />
      <button @click="searchDocuments" class="search-btn">搜索</button>
      <button @click="loadDocuments" class="reset-btn">重置</button>
    </div>

    <!-- 文档列表 -->
    <div class="documents-list">
      <div v-if="loading" class="loading">加载中...</div>
      
      <div v-else-if="documents.length === 0" class="empty-state">
        暂无文档，请点击右上角上传
      </div>

      <div 
        v-else
        v-for="doc in documents" 
        :key="doc.id"
        class="document-item"
        @click="viewDocument(doc)"
      >
        <div class="doc-header">
          <h3 class="doc-title">{{ doc.title }}</h3>
          <span class="doc-type">{{ doc.fileType }}</span>
        </div>
        <p class="doc-description">{{ doc.description || '无描述' }}</p>
        <div class="doc-meta">
          <span>字数：{{ doc.wordCount }}</span>
          <span>大小：{{ formatFileSize(doc.fileSize) }}</span>
          <span>创建：{{ formatDate(doc.createdAt) }}</span>
        </div>
        <div class="doc-actions">
          <button @click.stop="editDocument(doc)" class="action-btn edit">编辑</button>
          <button @click.stop="deleteDocument(doc.id)" class="action-btn delete">删除</button>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="totalPages > 1" class="pagination">
      <button 
        @click="changePage(currentPage - 1)" 
        :disabled="currentPage === 0"
        class="page-btn"
      >
        上一页
      </button>
      <span class="page-info">第 {{ currentPage + 1 }} / {{ totalPages }} 页</span>
      <button 
        @click="changePage(currentPage + 1)" 
        :disabled="currentPage >= totalPages - 1"
        class="page-btn"
      >
        下一页
      </button>
    </div>

    <!-- 上传/编辑弹窗 -->
    <div v-if="showUploadModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal">
        <h3>{{ editingDoc ? '编辑文档' : '上传纯文本文档' }}</h3>
        
        <div class="form-group">
          <label>标题 *</label>
          <input v-model="formData.title" type="text" placeholder="请输入文档标题" />
        </div>

        <div class="form-group">
          <label>描述</label>
          <input v-model="formData.description" type="text" placeholder="简要描述文档内容" />
        </div>

        <div class="form-group">
          <label>文件类型</label>
          <select v-model="formData.fileType">
            <option value="txt">TXT (纯文本)</option>
            <option value="md">Markdown</option>
            <option value="json">JSON</option>
            <option value="xml">XML</option>
            <option value="other">其他</option>
          </select>
        </div>

        <div class="form-group">
          <label>内容 *</label>
          <textarea 
            v-model="formData.content" 
            rows="15" 
            placeholder="请输入或粘贴纯文本内容..."
          ></textarea>
        </div>

        <div class="modal-actions">
          <button @click="closeModal" class="cancel-btn">取消</button>
          <button @click="submitForm" :disabled="submitting" class="submit-btn">
            {{ submitting ? '提交中...' : (editingDoc ? '保存修改' : '上传') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 文档详情弹窗 -->
    <div v-if="viewingDoc" class="modal-overlay large" @click.self="viewingDoc = null">
      <div class="modal large-modal">
        <div class="modal-header">
          <h3>{{ viewingDoc.title }}</h3>
          <button @click="viewingDoc = null" class="close-btn">×</button>
        </div>
        <div class="modal-body">
          <p v-if="viewingDoc.description" class="description">{{ viewingDoc.description }}</p>
          <div class="content-preview">{{ viewingDoc.content }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios'

export default {
  name: 'KnowledgeBase',
  data() {
    return {
      documents: [],
      loading: false,
      currentPage: 0,
      totalPages: 0,
      pageSize: 10,
      searchKeyword: '',
      isSearching: false,
      showUploadModal: false,
      viewingDoc: null,
      editingDoc: null,
      submitting: false,
      formData: {
        title: '',
        description: '',
        fileType: 'txt',
        content: ''
      }
    }
  },
  mounted() {
    this.loadDocuments()
  },
  methods: {
    async loadDocuments() {
      this.loading = true
      try {
        const params = {
          page: this.currentPage,
          size: this.pageSize
        }
        const response = await axios.get('/api/knowledge/documents', { params })
        this.documents = response.data.content
        this.totalPages = response.data.totalPages
        this.isSearching = false
      } catch (error) {
        console.error('加载文档失败:', error)
        alert('加载文档失败，请重试')
      } finally {
        this.loading = false
      }
    },

    async searchDocuments() {
      if (!this.searchKeyword.trim()) {
        this.loadDocuments()
        return
      }
      
      this.loading = true
      try {
        const params = {
          keyword: this.searchKeyword.trim(),
          page: this.currentPage,
          size: this.pageSize
        }
        const response = await axios.get('/api/knowledge/search', { params })
        this.documents = response.data.content
        this.totalPages = response.data.totalPages
        this.isSearching = true
      } catch (error) {
        console.error('搜索失败:', error)
        alert('搜索失败，请重试')
      } finally {
        this.loading = false
      }
    },

    changePage(page) {
      if (page < 0 || page >= this.totalPages) return
      this.currentPage = page
      if (this.isSearching) {
        this.searchDocuments()
      } else {
        this.loadDocuments()
      }
    },

    async viewDocument(doc) {
      // 如果当前 doc 没有 content 字段，调用详情接口获取完整内容
      if (!doc.content) {
        try {
          const response = await axios.get(`/api/knowledge/documents/${doc.id}`)
          this.viewingDoc = response.data
        } catch (error) {
          console.error('加载文档详情失败:', error)
          alert('加载文档详情失败，请重试')
          return
        }
      } else {
        this.viewingDoc = doc
      }
    },

    async editDocument(doc) {
      // 如果当前 doc 没有 content 字段，调用详情接口获取完整内容
      if (!doc.content) {
        try {
          const response = await axios.get(`/api/knowledge/documents/${doc.id}`)
          const fullDoc = response.data
          this.editingDoc = fullDoc
          this.formData = {
            title: fullDoc.title,
            description: fullDoc.description || '',
            fileType: fullDoc.fileType,
            content: fullDoc.content || ''
          }
        } catch (error) {
          console.error('加载文档内容失败:', error)
          alert('加载文档内容失败，请重试')
          return
        }
      } else {
        this.editingDoc = doc
        this.formData = {
          title: doc.title,
          description: doc.description || '',
          fileType: doc.fileType,
          content: doc.content || ''
        }
      }
      this.showUploadModal = true
    },

    async deleteDocument(id) {
      if (!confirm('确定要删除这个文档吗？')) return
      
      try {
        await axios.delete(`/api/knowledge/documents/${id}`)
        alert('删除成功')
        this.loadDocuments()
      } catch (error) {
        console.error('删除失败:', error)
        alert('删除失败，请重试')
      }
    },

    closeModal() {
      this.showUploadModal = false
      this.editingDoc = null
      this.formData = {
        title: '',
        description: '',
        fileType: 'txt',
        content: ''
      }
    },

    async submitForm() {
      if (!this.formData.title.trim() || !this.formData.content.trim()) {
        alert('请填写标题和内容')
        return
      }

      this.submitting = true
      try {
        if (this.editingDoc) {
          // 更新文档
          await axios.put(`/api/knowledge/documents/${this.editingDoc.id}`, this.formData)
          alert('更新成功')
        } else {
          // 上传新文档
          await axios.post('/api/knowledge/upload', this.formData)
          alert('上传成功')
        }
        
        this.closeModal()
        this.loadDocuments()
      } catch (error) {
        console.error('提交失败:', error)
        alert(error.response?.data?.message || '操作失败，请重试')
      } finally {
        this.submitting = false
      }
    },

    formatFileSize(bytes) {
      if (bytes < 1024) return bytes + ' B'
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
      return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
    },

    formatDate(timestamp) {
      if (!timestamp) return ''
      const date = new Date(timestamp)
      return date.toLocaleDateString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })
    }
  }
}
</script>

<style scoped>
.knowledge-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}

.knowledge-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.knowledge-header h2 {
  font-size: 24px;
  color: #333;
}

.upload-btn {
  padding: 10px 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.upload-btn:hover {
  opacity: 0.9;
}

.search-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.search-input {
  flex: 1;
  padding: 10px 15px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}

.search-input:focus {
  border-color: #667eea;
}

.search-btn, .reset-btn {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
}

.search-btn {
  background: #667eea;
  color: white;
}

.reset-btn {
  background: #f0f0f0;
  color: #666;
}

.documents-list {
  display: grid;
  gap: 15px;
}

.document-item {
  background: white;
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.document-item:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.doc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.doc-title {
  font-size: 18px;
  color: #333;
  margin: 0;
}

.doc-type {
  background: #f0f0f0;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
}

.doc-description {
  color: #666;
  font-size: 14px;
  margin: 10px 0;
}

.doc-meta {
  display: flex;
  gap: 15px;
  font-size: 12px;
  color: #999;
}

.doc-actions {
  display: flex;
  gap: 10px;
  margin-top: 15px;
}

.action-btn {
  padding: 6px 12px;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}

.action-btn.edit {
  background: #667eea;
  color: white;
}

.action-btn.delete {
  background: #ff4d4f;
  color: white;
}

.loading, .empty-state {
  text-align: center;
  padding: 40px;
  color: #999;
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 15px;
  margin-top: 20px;
  padding: 20px;
}

.page-btn {
  padding: 8px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.page-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.page-info {
  color: #666;
  font-size: 14px;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-overlay.large {
  align-items: flex-start;
  padding-top: 50px;
}

.modal {
  background: white;
  border-radius: 12px;
  padding: 30px;
  width: 90%;
  max-width: 600px;
  max-height: 90vh;
  overflow-y: auto;
}

.large-modal {
  max-width: 900px;
}

.modal h3 {
  margin-bottom: 20px;
  color: #333;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.close-btn {
  background: none;
  border: none;
  font-size: 28px;
  cursor: pointer;
  color: #999;
}

.form-group {
  margin-bottom: 15px;
}

.form-group label {
  display: block;
  margin-bottom: 5px;
  color: #333;
  font-weight: 500;
}

.form-group input,
.form-group select,
.form-group textarea {
  width: 100%;
  padding: 10px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  font-family: inherit;
}

.form-group textarea {
  resize: vertical;
  font-family: monospace;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}

.cancel-btn {
  padding: 10px 20px;
  background: #f0f0f0;
  color: #666;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

.submit-btn {
  padding: 10px 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

.submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal-body {
  max-height: 60vh;
  overflow-y: auto;
}

.description {
  color: #666;
  margin-bottom: 20px;
  padding: 10px;
  background: #f9f9f9;
  border-radius: 8px;
}

.content-preview {
  white-space: pre-wrap;
  font-family: monospace;
  background: #f9f9f9;
  padding: 15px;
  border-radius: 8px;
  line-height: 1.6;
}
</style>
