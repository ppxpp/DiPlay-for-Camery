<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { fetchReleases, type ChannelKey, type Release } from '../data/releases.ts';

const props = withDefaults(
  defineProps<{
    initialStable?: Release[];
    initialBeta?: Release[];
  }>(),
  { initialStable: () => [], initialBeta: () => [] },
);

const initialChannel: ChannelKey =
  props.initialStable.length === 0 && props.initialBeta.length > 0 ? 'beta' : 'stable';
const channel = ref<ChannelKey>(initialChannel);
const releases = ref<Release[]>(
  initialChannel === 'stable' ? props.initialStable : props.initialBeta,
);
const error = ref('');
const loading = ref(releases.value.length === 0);
let requestId = 0;

async function load(
  next: ChannelKey = channel.value,
  fallbackWhenEmpty = false,
  silent = false,
) {
  const id = ++requestId;
  channel.value = next;
  if (!silent) loading.value = true;
  error.value = '';
  try {
    let target = next;
    let list = await fetchReleases(target);
    if (id !== requestId) return;
    if (fallbackWhenEmpty && list.length === 0) {
      target = 'beta';
      channel.value = target;
      list = await fetchReleases(target);
    }
    if (id !== requestId) return;
    releases.value = list;
  } catch (cause) {
    if (id === requestId) {
      releases.value = [];
      error.value = cause instanceof Error ? cause.message : '加载失败';
    }
  } finally {
    if (id === requestId) loading.value = false;
  }
}

function setChannel(next: ChannelKey) {
  if (channel.value === next) return;
  void load(next);
}

function formatPublishedAt(value: string | null) {
  if (!value) return '发布时间未知';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

onMounted(() => {
  void load(channel.value, true, releases.value.length > 0);
});
</script>

<template>
  <section class="release-page" aria-label="版本列表" :aria-busy="loading">
    <div class="release-toolbar">
      <div class="channel-switch" role="group" aria-label="版本渠道">
        <button type="button" :aria-pressed="channel === 'stable'" @click="setChannel('stable')">
          正式版
        </button>
        <button type="button" :aria-pressed="channel === 'beta'" @click="setChannel('beta')">
          内测版
        </button>
      </div>
      <p v-if="!loading && !error" class="release-count">{{ releases.length }} 个版本</p>
    </div>

    <div v-if="loading" class="state-card" role="status">
      <span class="state-spinner" aria-hidden="true"></span>
      <p>正在加载版本…</p>
    </div>

    <div v-else-if="error" class="state-card state-card--error" role="alert">
      <span class="state-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6" />
          <path d="M12 7.5v5m0 3v.3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
      </span>
      <div>
        <h2>版本列表加载失败</h2>
        <p>{{ error }}</p>
      </div>
      <button class="btn secondary btn--compact" type="button" @click="load">重试</button>
    </div>

    <div v-else-if="!releases.length" class="state-card">
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
        <p>该渠道还没有发布版本。</p>
      </div>
    </div>

    <div v-else class="release-list">
      <article
        v-for="release in releases"
        :key="`${release.versionName || release.version}-${release.versionCode}`"
        class="release-card"
      >
        <div class="release-card-head">
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
                <h2>{{ release.versionName || release.version }}</h2>
                <span class="tag tag--brand">{{ channel === 'stable' ? '正式版' : '内测版' }}</span>
              </div>
              <p class="release-meta">
                <span>versionCode {{ release.versionCode }}</span>
                <span>{{ formatPublishedAt(release.publishedAt) }}</span>
              </p>
            </div>
          </div>

          <div class="release-actions">
            <template v-for="artifact in release.artifacts" :key="artifact.name">
              <a
                v-if="artifact.url"
                class="btn primary btn--compact"
                :href="artifact.url"
                :title="artifact.name"
                :aria-label="`下载 ${artifact.name}`"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M12 4v9m0 0-3.4-3.4M12 13l3.4-3.4M5 16v2.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V16"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
                下载 APK
              </a>
              <span v-else class="btn primary btn--compact" aria-disabled="true">下载 APK</span>
            </template>
            <a
              class="btn secondary btn--compact"
              :href="`/releases/${channel}/${release.versionCode}/`"
            >
              详情
            </a>
          </div>
        </div>

        <p v-if="release.notes" class="release-notes">{{ release.notes }}</p>
      </article>
    </div>
  </section>
</template>
