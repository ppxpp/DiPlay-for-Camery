<script setup lang="ts">
import { onMounted, ref } from 'vue';
import DownloadButton from './DownloadButton.vue';
import { site } from '../data/site.ts';
import { fetchUpdateChannel, formatUpdateSize, type UpdateChannel } from '../data/updates.ts';

const props = defineProps<{
  initialStable: UpdateChannel | null;
  initialBeta: UpdateChannel | null;
}>();

const stable = ref(props.initialStable);
const beta = ref(props.initialBeta);

onMounted(async () => {
  const [stableResult, betaResult] = await Promise.allSettled([
    fetchUpdateChannel('stable'),
    fetchUpdateChannel('beta'),
  ]);
  if (stableResult.status === 'fulfilled') stable.value = stableResult.value;
  if (betaResult.status === 'fulfilled') beta.value = betaResult.value;
});
</script>

<template>
  <div class="download-panel">
    <div class="actions-primary">
      <DownloadButton :channel="stable" label="下载稳定版" unavailable-text="暂无稳定版" />
      <DownloadButton
        :channel="beta"
        label="下载测试版"
        tone="secondary"
        icon="flask"
        unavailable-text="暂无测试版"
      />
    </div>

    <div class="actions-secondary">
      <a
        v-if="site.docs.install"
        class="btn secondary"
        :href="site.docs.install"
        target="_blank"
        rel="noopener"
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M4.5 5.5h5A2.5 2.5 0 0 1 12 8v10.5a2.5 2.5 0 0 0-2.5-2.5h-5V5.5Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          <path
            d="M19.5 5.5h-5A2.5 2.5 0 0 0 12 8v10.5a2.5 2.5 0 0 1 2.5-2.5h5V5.5Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
        </svg>
        安装教程
      </a>
      <span v-else class="btn secondary" aria-disabled="true">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M4.5 5.5h5A2.5 2.5 0 0 1 12 8v10.5a2.5 2.5 0 0 0-2.5-2.5h-5V5.5Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          <path
            d="M19.5 5.5h-5A2.5 2.5 0 0 0 12 8v10.5a2.5 2.5 0 0 1 2.5-2.5h5V5.5Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
        </svg>
        安装教程
      </span>
      <a class="btn secondary" :href="site.repoUrl" target="_blank" rel="noopener">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="m9.5 8-4 4 4 4m5-8 4 4-4 4"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        源码仓库
      </a>
    </div>

    <p v-if="stable" class="hero-meta">
      <span>
        稳定版 <span aria-hidden="true"> · </span> versionCode {{ stable.manifest.versionCode }}
      </span>
      <span v-if="formatUpdateSize(stable.artifact.size)">大小 {{ formatUpdateSize(stable.artifact.size) }}</span>
      <span v-if="stable.artifact.sha256" class="meta-hash">
        SHA-256 <code>{{ stable.artifact.sha256 }}</code>
      </span>
    </p>
    <p v-if="beta" class="hero-meta">
      <span>
        测试版 <span aria-hidden="true"> · </span> versionCode {{ beta.manifest.versionCode }}
        <span aria-hidden="true"> · </span> 可能不稳定
      </span>
      <span v-if="formatUpdateSize(beta.artifact.size)">大小 {{ formatUpdateSize(beta.artifact.size) }}</span>
      <span v-if="beta.artifact.sha256" class="meta-hash">
        SHA-256 <code>{{ beta.artifact.sha256 }}</code>
      </span>
    </p>
  </div>
</template>
