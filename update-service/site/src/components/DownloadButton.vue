<script setup lang="ts">
import { computed, ref, useId } from 'vue';
import { site } from '../data/site.ts';
import {
  displayShortVersion,
  displayVersion,
  formatUpdateSize,
  type UpdateChannel,
} from '../data/updates.ts';

const props = withDefaults(
  defineProps<{
    channel: UpdateChannel | null;
    label: string;
    tone?: 'primary' | 'secondary';
    icon?: 'download' | 'flask';
    unavailableText?: string;
  }>(),
  { tone: 'primary', icon: 'download', unavailableText: '暂无版本' },
);

const titleId = useId();
const dialog = ref<HTMLDialogElement | null>(null);
const agreed = ref(false);
const version = computed(() => (props.channel ? displayVersion(props.channel.manifest) : ''));
const shortVersion = computed(() =>
  props.channel ? displayShortVersion(props.channel.manifest) : '',
);
const available = computed(() => Boolean(props.channel?.artifact.url?.trim()));

function open() {
  if (!available.value) return;
  agreed.value = false;
  dialog.value?.showModal();
}

function close() {
  dialog.value?.close();
}

function download() {
  if (!props.channel || !available.value) return;
  const link = document.createElement('a');
  link.href = props.channel.artifact.url;
  link.rel = 'noopener';
  document.body.append(link);
  link.click();
  link.remove();
}

function confirmAndDownload() {
  if (!agreed.value || !available.value) return;
  close();
  download();
}
</script>

<template>
  <div>
    <button
      :class="['btn', tone]"
      type="button"
      :disabled="!available"
      :title="available ? version : undefined"
      @click="open"
    >
      <svg v-if="icon === 'flask'" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M9.5 3.5h5M10.5 3.5v4.7L6.4 16a1.9 1.9 0 0 0 1.7 2.8h7.8a1.9 1.9 0 0 0 1.7-2.8l-4.1-7.8V3.5"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
        <path d="M8.2 14h7.6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M12 4v9m0 0-3.4-3.4M12 13l3.4-3.4M5 16v2.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V16"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <span class="btn-copy">
        <span class="btn-label">{{ label }}</span>
        <span class="btn-version">{{ available ? `v${shortVersion}` : unavailableText }}</span>
      </span>
    </button>

    <dialog v-if="available" ref="dialog" :aria-labelledby="titleId" @click.self="close">
      <div class="dialog-inner">
        <div class="dialog-head">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 3 4.5 6v6c0 4.2 3 7.6 7.5 9 4.5-1.4 7.5-4.8 7.5-9V6L12 3Z"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linejoin="round"
            />
            <path
              d="M12 8.5v4m0 2.6v.4"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
          </svg>
          <div>
            <h2 :id="titleId" class="dialog-title">下载前请阅读免责声明</h2>
            <p class="dialog-sub">确认以下内容后再下载安装包。</p>
          </div>
        </div>

        <ol class="dialog-list">
          <li v-for="(item, index) in site.disclaimers" :key="index">{{ item }}</li>
        </ol>

        <div class="dialog-box">
          <p>
            版本 <bdi>{{ version }}</bdi>
            <template v-if="channel.artifact.size"> · 大小 {{ formatUpdateSize(channel.artifact.size) }}</template>
            <template v-if="channel.artifact.sha256">
              · SHA-256 <code>{{ channel.artifact.sha256 }}</code></template
            >
          </p>
          <p v-if="channel.key === 'beta'">这是测试版，可能不稳定，请谨慎使用。</p>
          <p>
            依 GPL-3.0 分发，与本安装包对应的完整源码见
            <a :href="site.sourceRefUrl || site.repoUrl" target="_blank" rel="noopener">源码仓库</a>。
          </p>
        </div>
      </div>

      <div class="dialog-foot">
        <label class="agree">
          <input v-model="agreed" type="checkbox" />
          <span>我已阅读并同意上述免责声明</span>
        </label>
        <div class="dialog-actions">
          <button class="btn secondary" type="button" @click="close">取消</button>
          <button class="btn primary" type="button" :disabled="!agreed" @click="confirmAndDownload">
            同意并下载
          </button>
        </div>
      </div>
    </dialog>
  </div>
</template>
