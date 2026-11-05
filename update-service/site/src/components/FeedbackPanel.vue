<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId } from 'vue';

interface FeedbackApp {
  versionName?: string;
  versionCode?: number;
  channel?: string;
}

interface FeedbackDevice {
  manufacturer?: string;
  model?: string;
  board?: string;
  androidRelease?: string;
}

interface FeedbackSession {
  mode?: string;
  state?: string;
}

interface FeedbackIndex {
  schemaVersion?: number;
  id: string;
  receivedAt: number;
  category?: string;
  description?: string;
  app?: FeedbackApp;
  device?: FeedbackDevice;
  session?: FeedbackSession;
  bundleKey?: string;
  bundleBytes?: number;
  bundleSha256?: string;
  reportPreview?: string;
  previewTruncated?: boolean;
}

interface FeedbackList {
  items?: FeedbackIndex[];
  cursor?: string;
  truncated?: boolean;
}

const props = defineProps<{
  request: (path: string, init?: RequestInit) => Promise<FeedbackList>;
}>();

const emit = defineEmits<{ (event: 'unauthorized'): void }>();

const categoryLabels: Record<string, string> = {
  connection: '连接',
  display: '画面',
  audio: '声音',
  navigation: '导航与仪表',
  vehicle: '方控与车控',
  other: '其他',
};
const categoryOptions = Object.entries(categoryLabels).map(([value, label]) => ({ value, label }));

const sessionModeLabels: Record<string, string> = {
  wireless: '无线 CarPlay',
  usb: 'USB CarPlay',
};

const sessionStateLabels: Record<string, string> = {
  failed: '连接失败',
  connected: '已连接',
  active: '会话中',
  idle: '空闲',
};

const titleId = useId();
const items = ref<FeedbackIndex[]>([]);
const cursor = ref('');
const loaded = ref(false);
const loading = ref(false);
const loadingMore = ref(false);
const error = ref('');
const notice = ref('');
const deleting = ref('');
const detailError = ref('');
const categoryFilter = ref('all');
const versionFilter = ref('all');
const keyword = ref('');
const detail = ref<FeedbackIndex | null>(null);
const dialog = ref<HTMLDialogElement | null>(null);

const versions = computed(() => {
  const seen = new Set<string>();
  for (const item of items.value) seen.add(versionLabel(item));
  return [...seen].sort((left, right) => left.localeCompare(right));
});

const filtered = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  return items.value.filter((item) => {
    if (categoryFilter.value !== 'all' && (item.category ?? '') !== categoryFilter.value) {
      return false;
    }
    if (versionFilter.value !== 'all' && versionLabel(item) !== versionFilter.value) {
      return false;
    }
    if (!term) return true;
    const haystack = [
      item.description ?? '',
      item.id,
      versionLabel(item),
      deviceLabel(item),
    ]
      .join('\n')
      .toLowerCase();
    return haystack.includes(term);
  });
});

const hasMore = computed(() => Boolean(cursor.value));

function isUnauthorized(cause: unknown) {
  return cause instanceof Error && cause.message === 'unauthorized';
}

function categoryLabel(value?: string) {
  if (!value) return '未知类型';
  return categoryLabels[value] ?? value;
}

function formatTime(value?: number) {
  if (!value || !Number.isFinite(value)) return '时间未知';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function formatBytes(value?: number) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return '未知';
  if (value < 1024) return `${value} B`;
  const kilobytes = value / 1024;
  if (kilobytes < 1024) return `${kilobytes.toFixed(1)} KB`;
  return `${(kilobytes / 1024).toFixed(2)} MB`;
}

function excerpt(value?: string, max = 60) {
  const text = (value ?? '').replace(/\s+/g, ' ').trim();
  if (!text) return '（未填写描述）';
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

function versionLabel(item: FeedbackIndex) {
  const name = item.app?.versionName;
  if (name) return name;
  const code = item.app?.versionCode;
  return code ? `versionCode ${code}` : '未知版本';
}

function deviceLabel(item: FeedbackIndex) {
  const parts = [item.device?.manufacturer, item.device?.model].filter(
    (part): part is string => Boolean(part && part.trim()),
  );
  return parts.length ? parts.join(' ') : '未知设备';
}

function sessionLabel(value: string | undefined, labels: Record<string, string>) {
  if (!value) return '未知';
  return labels[value] ?? value;
}

function resetFilters() {
  categoryFilter.value = 'all';
  versionFilter.value = 'all';
  keyword.value = '';
}

async function load() {
  loading.value = true;
  error.value = '';
  notice.value = '';
  try {
    const body = await props.request('/api/v1/admin/feedback?limit=50');
    items.value = Array.isArray(body.items) ? body.items : [];
    cursor.value = body.cursor ?? '';
    loaded.value = true;
    if (versionFilter.value !== 'all' && !versions.value.includes(versionFilter.value)) {
      versionFilter.value = 'all';
    }
  } catch (cause) {
    if (isUnauthorized(cause)) {
      emit('unauthorized');
      return;
    }
    error.value = cause instanceof Error ? cause.message : '加载反馈失败';
  } finally {
    loading.value = false;
  }
}

async function loadMore() {
  if (!cursor.value || loadingMore.value) return;
  loadingMore.value = true;
  error.value = '';
  try {
    const body = await props.request(
      `/api/v1/admin/feedback?limit=50&cursor=${encodeURIComponent(cursor.value)}`,
    );
    const known = new Set(items.value.map((item) => item.id));
    for (const item of body.items ?? []) {
      if (!known.has(item.id)) {
        known.add(item.id);
        items.value.push(item);
      }
    }
    cursor.value = body.cursor ?? '';
  } catch (cause) {
    if (isUnauthorized(cause)) {
      emit('unauthorized');
      return;
    }
    error.value = cause instanceof Error ? cause.message : '加载更多反馈失败';
  } finally {
    loadingMore.value = false;
  }
}

async function openDetail(item: FeedbackIndex) {
  detail.value = item;
  detailError.value = '';
  await nextTick();
  dialog.value?.showModal();
}

function closeDetail() {
  dialog.value?.close();
}

function onDialogClose() {
  detail.value = null;
  detailError.value = '';
}

function download(item: FeedbackIndex | null) {
  if (!item) return;
  // Download under the existing admin session cookie; no public URL is created.
  window.location.href = `/api/v1/admin/feedback/${encodeURIComponent(item.id)}/bundle`;
}

async function remove(item: FeedbackIndex | null) {
  if (!item) return;
  const label = `${formatTime(item.receivedAt)} 的反馈`;
  if (!window.confirm(`删除 ${label}？反馈包与索引会一并删除，且不可恢复。`)) return;
  deleting.value = item.id;
  detailError.value = '';
  notice.value = '';
  try {
    await props.request(`/api/v1/admin/feedback/${encodeURIComponent(item.id)}`, {
      method: 'DELETE',
    });
    closeDetail();
    await load();
    notice.value = '已删除该条反馈';
  } catch (cause) {
    if (isUnauthorized(cause)) {
      emit('unauthorized');
      return;
    }
    const text = cause instanceof Error ? cause.message : '删除失败';
    if (detail.value) detailError.value = text;
    else error.value = text;
  } finally {
    deleting.value = '';
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="feedback-panel">
    <div class="feedback-toolbar">
      <div class="feedback-filters">
        <label>
          问题类型
          <select v-model="categoryFilter">
            <option value="all">全部类型</option>
            <option v-for="option in categoryOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label>
          DiPlay 版本
          <select v-model="versionFilter">
            <option value="all">全部版本</option>
            <option v-for="version in versions" :key="version" :value="version">
              {{ version }}
            </option>
          </select>
        </label>
        <label class="feedback-filter-wide">
          关键词
          <input
            v-model="keyword"
            type="search"
            placeholder="搜索描述、设备型号或反馈编号"
          />
        </label>
      </div>
      <div class="feedback-toolbar-meta">
        <p class="release-count">{{ filtered.length }} / {{ items.length }} 条</p>
        <button class="btn secondary btn--compact" type="button" :disabled="loading" @click="load">
          刷新
        </button>
      </div>
    </div>

    <p v-if="notice" class="admin-message" role="status">{{ notice }}</p>
    <p v-if="error && items.length" class="admin-message" role="status">{{ error }}</p>

    <div v-if="loading && !items.length" class="state-card">
      <span class="state-spinner" aria-hidden="true"></span>
      <div>
        <h2>正在加载反馈</h2>
        <p>正在读取最近的用户反馈记录。</p>
      </div>
    </div>

    <div v-else-if="error && !items.length" class="state-card state-card--error">
      <span class="state-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none">
          <path
            d="M12 8v5M12 16.5v.5"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
          />
          <path
            d="M10.6 4.3 3.4 17a1.6 1.6 0 0 0 1.4 2.4h14.4a1.6 1.6 0 0 0 1.4-2.4L13.4 4.3a1.6 1.6 0 0 0-2.8 0Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
        </svg>
      </span>
      <div>
        <h2>反馈加载失败</h2>
        <p>{{ error }}</p>
      </div>
      <button class="btn secondary btn--compact" type="button" @click="load">重试</button>
    </div>

    <div v-else-if="loaded && !items.length" class="state-card">
      <span class="state-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none">
          <path
            d="M4.5 6.5h15v11h-15z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          <path
            d="M7.5 10h9M7.5 13.5h5.5"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
          />
        </svg>
      </span>
      <div>
        <h2>暂无用户反馈</h2>
        <p>还没有收到反馈包。用户从车机提交后会出现在这里。</p>
      </div>
    </div>

    <div v-else-if="!filtered.length" class="state-card">
      <span class="state-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none">
          <circle cx="11" cy="11" r="6" stroke="currentColor" stroke-width="1.5" />
          <path d="m15.5 15.5 4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
      </span>
      <div>
        <h2>没有符合筛选条件的反馈</h2>
        <p>关键词与筛选只在已加载的反馈中生效。</p>
      </div>
      <button class="btn secondary btn--compact" type="button" @click="resetFilters">
        重置筛选
      </button>
    </div>

    <template v-else>
      <div class="feedback-table-wrap">
        <table class="feedback-table">
          <thead>
            <tr>
              <th scope="col">接收时间</th>
              <th scope="col">类型</th>
              <th scope="col">DiPlay 版本</th>
              <th scope="col">设备型号</th>
              <th scope="col">包大小</th>
              <th scope="col">问题描述</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filtered" :key="item.id">
              <td class="feedback-nowrap">{{ formatTime(item.receivedAt) }}</td>
              <td>
                <span class="tag tag--brand">{{ categoryLabel(item.category) }}</span>
              </td>
              <td class="feedback-nowrap">{{ versionLabel(item) }}</td>
              <td>{{ deviceLabel(item) }}</td>
              <td class="feedback-nowrap">{{ formatBytes(item.bundleBytes) }}</td>
              <td class="feedback-description">{{ excerpt(item.description) }}</td>
              <td>
                <div class="feedback-row-actions">
                  <button
                    class="btn secondary btn--compact"
                    type="button"
                    @click="openDetail(item)"
                  >
                    详情
                  </button>
                  <button
                    class="btn secondary btn--compact"
                    type="button"
                    @click="download(item)"
                  >
                    下载
                  </button>
                  <button
                    class="btn danger btn--compact"
                    type="button"
                    :disabled="deleting === item.id"
                    @click="remove(item)"
                  >
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="hasMore" class="feedback-more">
        <button
          class="btn secondary btn--compact"
          type="button"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '正在加载…' : '加载更多' }}
        </button>
      </div>
    </template>

    <dialog
      ref="dialog"
      class="feedback-detail-dialog"
      :aria-labelledby="titleId"
      @close="onDialogClose"
      @click.self="closeDetail"
    >
      <template v-if="detail">
        <div class="dialog-inner">
          <div class="dialog-head">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M4.5 6.5h15v11h-15z"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linejoin="round"
              />
              <path
                d="M7.5 10h9M7.5 13.5h5.5"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
              />
            </svg>
            <div>
              <h2 :id="titleId" class="dialog-title">{{ categoryLabel(detail.category) }} · 用户反馈</h2>
              <p class="dialog-sub">收到时间 {{ formatTime(detail.receivedAt) }}</p>
            </div>
          </div>

          <p v-if="detailError" class="admin-message" role="status">{{ detailError }}</p>

          <section class="feedback-detail-block">
            <h3>问题描述</h3>
            <p v-if="detail.description" class="feedback-detail-text">{{ detail.description }}</p>
            <p v-else class="feedback-detail-empty">用户没有填写问题描述。</p>
          </section>

          <section class="feedback-detail-block">
            <h3>应用与设备</h3>
            <dl class="feedback-detail-grid">
              <div>
                <dt>DiPlay 版本</dt>
                <dd>{{ versionLabel(detail) }}</dd>
              </div>
              <div>
                <dt>versionCode</dt>
                <dd>{{ detail.app?.versionCode ?? '未知' }}</dd>
              </div>
              <div>
                <dt>分发渠道</dt>
                <dd>{{ detail.app?.channel || '未知' }}</dd>
              </div>
              <div>
                <dt>设备</dt>
                <dd>{{ deviceLabel(detail) }}</dd>
              </div>
              <div>
                <dt>主板</dt>
                <dd>{{ detail.device?.board || '未知' }}</dd>
              </div>
              <div>
                <dt>Android 版本</dt>
                <dd>{{ detail.device?.androidRelease || '未知' }}</dd>
              </div>
              <div>
                <dt>会话模式</dt>
                <dd>{{ sessionLabel(detail.session?.mode, sessionModeLabels) }}</dd>
              </div>
              <div>
                <dt>会话状态</dt>
                <dd>{{ sessionLabel(detail.session?.state, sessionStateLabels) }}</dd>
              </div>
            </dl>
          </section>

          <section class="feedback-detail-block">
            <h3>反馈包</h3>
            <dl class="feedback-detail-grid">
              <div>
                <dt>大小</dt>
                <dd>{{ formatBytes(detail.bundleBytes) }}</dd>
              </div>
              <div>
                <dt>反馈编号</dt>
                <dd class="feedback-mono">{{ detail.id }}</dd>
              </div>
              <div class="feedback-detail-wide">
                <dt>SHA-256</dt>
                <dd class="feedback-mono">{{ detail.bundleSha256 || '未知' }}</dd>
              </div>
            </dl>
          </section>

          <section class="feedback-detail-block">
            <h3>
              诊断摘要预览
              <span v-if="detail.previewTruncated" class="tag">已截断</span>
            </h3>
            <pre class="feedback-report">{{ detail.reportPreview || '反馈包中没有可预览的诊断摘要。' }}</pre>
          </section>
        </div>

        <div class="dialog-foot">
          <div class="dialog-actions">
            <button class="btn secondary btn--compact" type="button" @click="closeDetail">
              关闭
            </button>
            <button class="btn secondary btn--compact" type="button" @click="download(detail)">
              下载反馈包
            </button>
            <button
              class="btn danger btn--compact"
              type="button"
              :disabled="deleting === detail.id"
              @click="remove(detail)"
            >
              删除
            </button>
          </div>
        </div>
      </template>
    </dialog>
  </div>
</template>

<style scoped>
.feedback-panel {
  width: 100%;
}

.feedback-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px 18px;
  margin-bottom: 14px;
  padding: 14px 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-doc);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.feedback-filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px 14px;
  min-width: 0;
  flex: 1 1 520px;
}

.feedback-filters > label {
  display: grid;
  gap: 4px;
  min-width: 0;
  flex: 1 1 150px;
  color: var(--color-text);
  font-size: 13px;
  font-weight: 600;
}

.feedback-filters .feedback-filter-wide {
  flex: 2 1 240px;
}

.feedback-filters select,
.feedback-filters input {
  min-height: 44px;
}

.feedback-toolbar-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.feedback-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.feedback-table {
  width: 100%;
  min-width: 960px;
  border-collapse: collapse;
  font-size: 13px;
  line-height: 1.6;
}

.feedback-table th,
.feedback-table td {
  padding: 12px 14px;
  border-bottom: 1px solid var(--color-border);
  text-align: left;
  vertical-align: top;
}

.feedback-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f8fafc;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.feedback-table tbody tr:last-child td {
  border-bottom: 0;
}

.feedback-table tbody tr:hover {
  background: #fbfcfe;
}

.feedback-nowrap {
  white-space: nowrap;
}

.feedback-description {
  min-width: 220px;
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.feedback-row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.feedback-row-actions .btn {
  min-height: 36px;
  padding: 4px 10px;
  font-size: 13px;
}

.feedback-more {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.feedback-detail-dialog {
  width: min(880px, calc(100vw - 32px));
}

.feedback-detail-block {
  margin-bottom: 20px;
}

.feedback-detail-block h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 700;
}

.feedback-detail-text {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: #f8fafc;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.feedback-detail-empty {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.feedback-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px 20px;
  margin: 0;
}

.feedback-detail-grid dt {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.feedback-detail-grid dd {
  margin: 2px 0 0;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.feedback-detail-grid .feedback-detail-wide {
  grid-column: 1 / -1;
}

.feedback-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.feedback-report {
  max-height: 320px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: #f8fafc;
  color: var(--color-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 767px) {
  .feedback-toolbar-meta {
    margin-left: 0;
  }

  .feedback-row-actions .btn {
    min-height: 44px;
  }
}
</style>
