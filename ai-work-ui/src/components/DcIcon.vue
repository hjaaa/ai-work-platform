<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    style="display: block"
    aria-hidden="true"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ name: string; size?: number }>(), {
  size: 18,
})

// 线性图标 path 集，移植自设计稿 icon() 的 P 映射（stroke=1.6）
const ICONS: Record<string, string[]> = {
  home: ['M4 11.5 12 4l8 7.5', 'M6 10.3V20h12V10.3', 'M10 20v-5h4v5'],
  models: ['M7 7h10v10H7z', 'M10 10h4v4h-4z', 'M9 3v2M15 3v2M9 19v2M15 19v2M3 9h2M3 15h2M19 9h2M19 15h2'],
  prompts: ['M14 3H7a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V7z', 'M14 3v4h4', 'M9 12h6M9 16h4'],
  agents: ['M8 8h8a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z', 'M12 5v3', 'M9.5 13h.01M14.5 13h.01', 'M4 12v3M20 12v3'],
  kb: ['M5 5a2 2 0 0 1 2-2h11v18H7a2 2 0 0 0-2 2z', 'M18 3v16'],
  tasks: ['M9 6h11M9 12h11M9 18h11', 'M4.5 6h.01M4.5 12h.01M4.5 18h.01'],
  datasets: ['M12 3 3 8l9 5 9-5z', 'M3 12l9 5 9-5', 'M3 16l9 5 9-5'],
  label: ['M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V4h9l8.6 8.6a2 2 0 0 1 0 1.8z', 'M7.5 7.5h.01'],
  members: ['M16 20v-1.5a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4V20', 'M9.5 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7', 'M21 20v-1.5a4 4 0 0 0-3-3.9', 'M16.5 3.7a3.5 3.5 0 0 1 0 6.6'],
  spaces: ['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z'],
  roles: ['M12 3l7 3v6c0 5-3.5 7.5-7 9-3.5-1.5-7-4-7-9V6z', 'M9 12l2 2 4-4'],
  apps: ['M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', 'M3 10h18', 'M6 7.5h.01M9 7.5h.01'],
  logs: ['M3 12h4l2 6 4-15 2.5 9H21'],
  settings: ['M4 8h8M16 8h4', 'M12 6v4', 'M4 16h4M12 16h8', 'M8 14v4'],
  search: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z', 'M20 20l-3.5-3.5'],
  bell: ['M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9', 'M13.7 21a2 2 0 0 1-3.4 0'],
  chevron: ['M6 9l6 6 6-6'],
  panel: ['M4 5h16v14H4z', 'M10 5v14'],
}

const paths = computed(() => ICONS[props.name] ?? ICONS.apps)
</script>
