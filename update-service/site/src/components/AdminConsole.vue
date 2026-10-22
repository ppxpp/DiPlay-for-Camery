<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { adminSession } from '../data/admin-session.ts';

type Channel = 'stable' | 'beta';
type ChannelFilter = 'all' | Channel;
type Management = {
  enabled: boolean;
  mode: 'all' | 'percentage' | 'whitelist';
  percentage?: number;
  ids?: string[];
  notes: string;
};
type Release = {
  channel: Channel;
  version: string;
  versionName?: string;
  versionCode: number;
  publishedAt?: string;
  management?: Management;
};
type ApiRelease = Omit<Release, 'channel'>;

const channel = ref<ChannelFilter>('all');
const authenticated = ref(false);
const username = ref('');
const password = ref('');
const releases = ref<Release[]>([]);
const currentVersions = ref<Record<Channel, string | null>>({ stable: null, beta: null });
const message = ref('');
const busy = ref(false);

function timestamp(value?: string) {
  const time = value ? Date.parse(value) : 0;
  return Number.isFinite(time) ? time : 0;
}

async function request(path: string, init: RequestInit = {}) {
  const response = await fetch(path, {
    ...init,
    headers: { 'content-type': 'application/json', ...(init.headers ?? {}) },
    credentials: 'same-origin',
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error ?? `请求失败（${response.status}）`);
  return body;
}

async function load() {
  busy.value = true;
  try {
    const channels: Channel[] = channel.value === 'all' ? ['stable', 'beta'] : [channel.value];
    const results = await Promise.all(
      channels.map(async (value) => {
        const result = await request(`/api/v1/admin/releases?channel=${value}`);
        return {
          channel: value,
          currentVersion: (result.currentVersion ?? null) as string | null,
          releases: (result.releases ?? []) as ApiRelease[],
        };
      }),
    );

    const nextCurrent: Record<Channel, string | null> = { stable: null, beta: null };
    const nextReleases: Release[] = [];
    for (const result of results) {
      nextCurrent[result.channel] = result.currentVersion;
      for (const release of result.releases) {
        nextReleases.push({
          ...release,
          channel: result.channel,
          management: { enabled: true, mode: 'all', notes: '', ...release.management },
        });
      }
    }

    currentVersions.value = nextCurrent;
    releases.value = nextReleases.sort(
      (left, right) =>
        timestamp(right.publishedAt) - timestamp(left.publishedAt) ||
        right.versionCode - left.versionCode,
    );
    authenticated.value = true;
    adminSession.authenticated = true;
  } catch (cause) {
    if (cause instanceof Error && cause.message === 'unauthorized') {
      authenticated.value = false;
      adminSession.authenticated = false;
    } else {
      message.value = cause instanceof Error ? cause.message : '加载失败';
    }
  } finally {
    busy.value = false;
  }
}

function setChannel(next: ChannelFilter) {
  if (channel.value === next) return;
  channel.value = next;
  load();
}

function formatPublishedAt(value?: string) {
  if (!value) return '发布时间未知';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

async function login() {
  message.value = '';
  try {
    await request('/api/v1/admin/login', {
      method: 'POST',
      body: JSON.stringify({ username: username.value, password: password.value }),
    });
    password.value = '';
    await load();
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '登录失败';
  }
}

async function save(release: Release) {
  message.value = '';
  try {
    const management = release.management!;
    await request('/api/v1/admin/releases', {
      method: 'PUT',
      body: JSON.stringify({
        channel: release.channel,
        version: release.version,
        management: {
          ...management,
          ids: management.mode === 'whitelist' ? (management.ids ?? []) : undefined,
          percentage: management.mode === 'percentage' ? Number(management.percentage ?? 0) : undefined,
        },
      }),
    });
    message.value = `${release.version} 已保存`;
    await load();
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '保存失败';
  }
}

async function rollback(release: Release) {
  if (!window.confirm(`将自动更新推荐版本回撤到 ${release.version}？当前推荐版本会停止下发。`)) return;
  try {
    const result = await request('/api/v1/admin/releases', {
      method: 'POST',
      body: JSON.stringify({ channel: release.channel, version: release.version }),
    });
    message.value = `已回撤到 ${result.currentVersion}`;
    await load();
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '回撤失败';
  }
}

async function logout() {
  try {
    await request('/api/v1/admin/logout', { method: 'POST' });
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '退出失败';
    return;
  }
  authenticated.value = false;
  adminSession.authenticated = false;
  releases.value = [];
  message.value = '';
}

const handleLogoutRequest = () => {
  void logout();
};

onMounted(() => {
  void load();
  window.addEventListener('admin:logout-request', handleLogoutRequest);
});

onUnmounted(() => {
  window.removeEventListener('admin:logout-request', handleLogoutRequest);
});
</script>

<template>
  <section class="admin-console" aria-label="版本管理控制台">
    <form v-if="!authenticated" class="admin-login" @submit.prevent="login">
      <div class="admin-login-head">
        <span class="admin-login-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <rect
              x="5"
              y="10"
              width="14"
              height="10"
              rx="2"
              stroke="currentColor"
              stroke-width="1.5"
            />
            <path
              d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
            <path d="M12 13.5v3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
        </span>
        <div>
          <h2>管理员登录</h2>
          <p>登录后可管理版本下发与回撤。</p>
        </div>
      </div>

      <label>
        管理员账号
        <input
          v-model="username"
          autocomplete="username"
          placeholder="请输入管理员账号"
          required
        />
      </label>
      <label>
        密码
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
          required
        />
      </label>
      <button class="btn primary" type="submit" :disabled="busy">登录</button>
      <p v-if="message" class="admin-message" role="status">{{ message }}</p>
    </form>

    <template v-else>
      <div class="admin-filter-row">
        <div class="channel-switch" role="group" aria-label="版本渠道">
          <button type="button" :aria-pressed="channel === 'all'" @click="setChannel('all')">
            全部
          </button>
          <button
            type="button"
            :aria-pressed="channel === 'stable'"
            @click="setChannel('stable')"
          >
            正式版
          </button>
          <button type="button" :aria-pressed="channel === 'beta'" @click="setChannel('beta')">
            内测版
          </button>
        </div>
        <p class="release-count">{{ releases.length }} 个版本</p>
      </div>

      <p v-if="message" class="admin-message" role="status">{{ message }}</p>

      <div v-if="!releases.length" class="state-card">
        <span class="state-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <path
              d="M4.5 8 12 4.5 19.5 8v8L12 19.5 4.5 16V8Z"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linejoin="round"
            />
            <path
              d="m4.5 8 7.5 4 7.5-4M12 12v7.5"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linejoin="round"
            />
          </svg>
        </span>
        <div>
          <h2>暂无版本</h2>
          <p>{{ channel === 'all' ? '当前没有已发布的版本。' : '该渠道还没有发布版本。' }}</p>
        </div>
      </div>

      <div v-else class="release-list">
        <article v-for="release in releases" :key="`${release.channel}-${release.version}`" class="release-card admin-release">
          <header class="release-card-head">
            <div class="release-identity">
              <span class="release-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <path
                    d="M4.5 8 12 4.5 19.5 8v8L12 19.5 4.5 16V8Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linejoin="round"
                  />
                  <path
                    d="m4.5 8 7.5 4 7.5-4M12 12v7.5"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
              <div class="release-title">
                <div class="release-title-row">
                  <h2>{{ release.versionName ?? release.version }}</h2>
                  <span
                    v-if="release.version === currentVersions[release.channel]"
                    class="tag tag--brand"
                  >
                    当前推荐
                  </span>
                  <span v-if="channel === 'all'" class="tag">
                    {{ release.channel === 'stable' ? '正式版' : '内测版' }}
                  </span>
                </div>
                <p class="release-meta">
                  <span>versionCode {{ release.versionCode }}</span>
                  <span>{{ formatPublishedAt(release.publishedAt) }}</span>
                </p>
              </div>
            </div>

            <div class="release-actions">
              <button
                v-if="release.version !== currentVersions[release.channel]"
                class="btn secondary btn--compact"
                type="button"
                @click="rollback(release)"
              >
                回撤到此版本
              </button>
            </div>
          </header>

          <div class="admin-release-grid">
            <label class="toggle">
              <input v-model="release.management!.enabled" type="checkbox" />
              <span>允许自动下发</span>
            </label>
            <label>
              下发模式
              <select v-model="release.management!.mode">
                <option value="all">全量</option>
                <option value="percentage">按百分比</option>
                <option value="whitelist">白名单</option>
              </select>
            </label>
            <label v-if="release.management!.mode === 'percentage'">
              下发比例（0–100%）
              <input
                v-model.number="release.management!.percentage"
                type="number"
                min="0"
                max="100"
              />
            </label>
            <label v-if="release.management!.mode === 'whitelist'" class="wide-field">
              客户端 ID（每行一个）
              <textarea
                :value="(release.management!.ids ?? []).join('\n')"
                rows="5"
                @input="
                  release.management!.ids = ($event.target as HTMLTextAreaElement).value
                    .split(/[\n,，\s]+/)
                    .map((id) => id.trim())
                    .filter(Boolean)
                "
              />
            </label>
            <label class="wide-field">
              发布说明
              <textarea v-model="release.management!.notes" rows="4" maxlength="10000" />
            </label>
          </div>

          <div class="admin-release-actions">
            <span>修改后保存才会生效</span>
            <button
              class="btn primary btn--compact"
              type="button"
              :disabled="busy"
              @click="save(release)"
            >
              保存设置
            </button>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>
