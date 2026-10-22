<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, useId } from 'vue';
import { adminSession } from '../data/admin-session.ts';

const titleId = useId();
const menuOpen = ref(false);
const dialog = ref<HTMLDialogElement | null>(null);
const trigger = ref<HTMLButtonElement | null>(null);
const menu = ref<HTMLDivElement | null>(null);

async function openMenu() {
  menuOpen.value = true;
  await nextTick();
  menu.value?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
}

function closeMenu(restoreFocus = false) {
  menuOpen.value = false;
  if (restoreFocus) trigger.value?.focus();
}

function toggleMenu() {
  if (menuOpen.value) closeMenu(true);
  else void openMenu();
}

function openConfirm() {
  menuOpen.value = false;
  dialog.value?.showModal();
}

function closeConfirm() {
  dialog.value?.close();
  trigger.value?.focus();
}

function confirmLogout() {
  dialog.value?.close();
  window.dispatchEvent(new CustomEvent('admin:logout-request'));
}

function onPointerDown(event: PointerEvent) {
  if (!menuOpen.value) return;
  const target = event.target as Node;
  if (menu.value?.contains(target) || trigger.value?.contains(target)) return;
  closeMenu();
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !menuOpen.value) return;
  closeMenu(true);
}

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown);
  document.addEventListener('keydown', onKeydown);
});

onUnmounted(() => {
  document.removeEventListener('pointerdown', onPointerDown);
  document.removeEventListener('keydown', onKeydown);
});
</script>

<template>
  <template v-if="adminSession.authenticated">
    <div class="admin-user">
      <button
        ref="trigger"
        class="admin-user-trigger"
        type="button"
        aria-haspopup="menu"
        :aria-expanded="menuOpen"
        aria-controls="admin-user-menu"
        aria-label="管理员账户菜单"
        data-admin-user-trigger
        @click="toggleMenu"
      >
        <span class="admin-avatar" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="8" r="3.4" stroke="currentColor" stroke-width="1.6" />
            <path
              d="M5.2 19.5a6.8 6.8 0 0 1 13.6 0"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
            />
          </svg>
        </span>
        <span class="admin-user-name">管理员</span>
        <svg class="admin-user-chevron" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="m4 6 4 4 4-4"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>

      <div
        v-if="menuOpen"
        id="admin-user-menu"
        ref="menu"
        class="admin-user-menu"
        role="menu"
        aria-label="管理员账户"
      >
        <p class="admin-user-menu-head">管理员账户</p>
        <button type="button" role="menuitem" @click="openConfirm">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M10 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20H10M14.5 8l4 4-4 4M9 12h9"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          退出登录
        </button>
      </div>
    </div>

    <dialog
      ref="dialog"
      class="admin-confirm-dialog"
      :aria-labelledby="titleId"
      @click.self="closeConfirm"
    >
      <div class="dialog-inner">
        <div class="dialog-head">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M10 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20H10M14.5 8l4 4-4 4M9 12h9"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <div>
            <h2 :id="titleId" class="dialog-title">确认退出登录？</h2>
            <p class="dialog-sub">退出后需要重新输入管理员账号和密码。</p>
          </div>
        </div>
        <p class="admin-confirm-note">未保存的下发设置修改将不会生效。</p>
      </div>
      <div class="dialog-foot">
        <div class="dialog-actions">
          <button class="btn secondary" type="button" @click="closeConfirm">取消</button>
          <button class="btn primary" type="button" @click="confirmLogout">确认退出</button>
        </div>
      </div>
    </dialog>
  </template>
</template>
