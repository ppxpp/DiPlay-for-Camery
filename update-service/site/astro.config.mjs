import sitemap from '@astrojs/sitemap';
import vue from '@astrojs/vue';
import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://diplay.mgulu.com',
  integrations: [vue(), sitemap({ filter: (page) => !page.includes('/admin') })],
  build: { inlineStylesheets: 'auto' }
});
