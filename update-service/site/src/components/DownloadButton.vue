<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue';
import { site } from '../data/site.ts';
import { displayVersion, formatUpdateSize, type UpdateChannel } from '../data/updates.ts';

const props = withDefaults(
  defineProps<{
    channel: UpdateChannel | null;
    label: string;
    tone?: 'primary' | 'secondary';
    unavailableText?: string;
  }>(),
  { tone: 'primary', unavailableText: '暂无版本' },
);

const STORAGE_KEY = 'diplay-camry.disclaimer-accepted.v1';
const titleId = useId();
const dialog = ref<HTMLDialogElement | null>(null);
const agreed = ref(false);
const accepted = ref(false);
const hint = ref('');
const version = computed(() => (props.channel ? displayVersion(props.channel.manifest) : ''));
const ready = () => Boolean(props.channel?.artifact.url.trim());

onMounted(() => {
  accepted.value = localStorage.getItem(STORAGE_KEY) === '1';
});

function open() {
  if (!props.channel) return;
  if (accepted.value && ready()) {
    download();
    return;
  }
  agreed.value = false;
  hint.value = ready() ? '' : '下载地址尚未配置，请稍后再试。';
  dialog.value?.showModal();
}

function close() {
  dialog.value?.close();
}

function download() {
  if (!props.channel) return;
  const link = document.createElement('a');
  link.href = props.channel.artifact.url;
  link.rel = 'noopener';
  document.body.append(link);
  link.click();
  link.remove();
}

function confirmAndDownload() {
  if (!agreed.value || !ready()) return;
  localStorage.setItem(STORAGE_KEY, '1');
  accepted.value = true;
  close();
  download();
}
</script>

<template>
  <div>
    <button :class="['btn', tone]" type="button" :disabled="!channel" @click="open">
      <span class="btn-copy">
        <span class="btn-label">{{ label }}</span>
        <span class="btn-version">{{ channel ? `v${version}` : unavailableText }}</span>
      </span>
      <svg v-if="channel" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M8 2.5v8m0 0L4.5 7M8 10.5 11.5 7M3 13.5h10"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>

    <dialog v-if="channel" ref="dialog" :aria-labelledby="titleId" @click.self="close">
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
            版本 <bdi>v{{ version }}</bdi>
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
        <p v-if="hint" class="dialog-hint">{{ hint }}</p>
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
