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
  <div>
    <div class="actions">
      <DownloadButton :channel="stable" label="下载稳定版" unavailable-text="暂无稳定版" />
      <DownloadButton
        :channel="beta"
        label="下载测试版"
        tone="secondary"
        unavailable-text="暂无测试版"
      />
      <a v-if="site.docs.install" class="btn secondary" :href="site.docs.install" target="_blank" rel="noopener">
        安装教程
      </a>
      <span v-else class="btn secondary" aria-disabled="true">安装教程</span>
      <a class="btn secondary" :href="site.repoUrl" target="_blank" rel="noopener">源码仓库</a>
    </div>

    <p v-if="stable" class="hero-meta">
      <span>
        稳定版 <span aria-hidden="true"> · </span> versionCode {{ stable.manifest.versionCode }}
      </span>
      <span v-if="formatUpdateSize(stable.artifact.size)">大小 {{ formatUpdateSize(stable.artifact.size) }}</span>
      <span v-if="stable.artifact.sha256">SHA-256 <code>{{ stable.artifact.sha256 }}</code></span>
    </p>
    <p v-if="beta" class="hero-meta">
      <span>
        测试版 <span aria-hidden="true"> · </span> versionCode {{ beta.manifest.versionCode }}
        <span aria-hidden="true"> · </span> 可能不稳定
      </span>
      <span v-if="formatUpdateSize(beta.artifact.size)">大小 {{ formatUpdateSize(beta.artifact.size) }}</span>
      <span v-if="beta.artifact.sha256">SHA-256 <code>{{ beta.artifact.sha256 }}</code></span>
    </p>
  </div>
</template>
