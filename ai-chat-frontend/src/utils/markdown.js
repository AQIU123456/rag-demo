import MarkdownIt from 'markdown-it'

/**
 * 统一的 Markdown 渲染器（供各聊天界面复用）。
 *
 * 安全说明：
 * - html: false 会把回答里的原始 HTML 转义成文本，防止 LLM / 知识库内容
 *   通过 <script>、onerror 等注入脚本（RAG 场景下回答来自外部数据，必须防 XSS）。
 * - linkify: true 让裸链接可点击；markdown-it 默认拦截 javascript: 等危险协议。
 * - breaks: true 让聊天里常见的单换行生效，不必强行写两个空格。
 *
 * 注意：这里只负责“文本 -> HTML 字符串”，模板需通过 v-html 渲染并挂上
 * .markdown-body 类以套用全局样式（scoped 样式无法穿透 v-html 生成的节点）。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

/**
 * 把 Markdown 文本渲染为安全的 HTML 字符串。
 * @param {string|null|undefined} text
 * @returns {string}
 */
export function renderMarkdown(text) {
  if (text == null) return ''
  return md.render(String(text))
}

export default md
