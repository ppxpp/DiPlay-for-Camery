# 凯美瑞站点视觉优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优化凯美瑞版下载介绍页的清爽科技视觉，同时保留现有内容和交互。

**Architecture:** 沿用 Astro 页面、Vue 下载按钮和当前语义结构。集中调整全局 CSS 的颜色、排版、布局、状态和响应式规则。

**Tech Stack:** Astro 7、Vue 3、原生 CSS。

---

### Task 1: 统一页面视觉样式

**Files:**
- Modify: `src/styles/global.css`

- [x] **Step 1: 微调设计变量和页面背景**

在 `:root` 中统一冷色强调色、背景、表面、边框、圆角和阴影变量；让 `body` 使用轻柔的顶部光晕与浅色基底。

```css
:root {
  --bg: #f7f9fc;
  --surface: #fff;
  --surface-2: #f2f5fa;
  --line: #e4e9f1;
  --text: #142033;
  --muted: #5d6d84;
  --accent: #3569e8;
  --accent-strong: #2453c7;
  --accent-soft: #eaf0ff;
}
```

- [x] **Step 2: 加强首屏、导航、按钮和卡片层级**

调整 `.topbar`、`.hero`、`.eyebrow`、`.btn`、`.spec-card`、`.doc-link` 和 `.card` 的间距、圆角、边框、阴影和悬停反馈。保持主要按钮高对比，并为弱化状态保留清晰边界。

```css
.card,
.spec-card,
.doc-link {
  border-color: var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
```

- [x] **Step 3: 完善移动端与弹窗样式**

细化窄屏标题、按钮与栅格间距，确保免责声明弹窗内部可滚动、底部操作清晰，并为减少动态效果偏好的用户关闭平移动画。

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
  }
}
```

- [x] **Step 4: 对照设计说明检查覆盖范围**

确认首屏、设备卡片、文档、安全说明、页脚、弹窗、移动端和键盘焦点都沿用统一样式；不改内容数据或交互逻辑。
