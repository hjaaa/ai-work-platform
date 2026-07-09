<template>
  <div class="brand-logo" :style="containerStyle" aria-hidden="true">
    <svg ref="logoSvg" viewBox="0 0 100 100" :width="svgSize" :height="svgSize">
      <g stroke="var(--dc-surface)" stroke-width="2" stroke-linecap="round" opacity="0.42">
        <line data-edge="0-1" x1="28" y1="30" x2="72" y2="28" />
        <line data-edge="1-2" x1="72" y1="28" x2="52" y2="56" />
        <line data-edge="2-3" x1="52" y1="56" x2="74" y2="74" />
        <line data-edge="3-0" x1="74" y1="74" x2="28" y2="30" />
        <line data-edge="0-2" x1="28" y1="30" x2="52" y2="56" />
      </g>
      <g>
        <circle data-pulse="0-1" r="2.6" fill="var(--dc-surface)" cx="28" cy="30" opacity="0" />
        <circle data-pulse="1-2" r="2.6" fill="var(--dc-surface)" cx="72" cy="28" opacity="0" />
        <circle data-pulse="2-3" r="2.6" :fill="`var(--dc-logo-pulse)`" cx="52" cy="56" opacity="0" />
      </g>
      <g>
        <circle data-node="0" cx="28" cy="30" r="6.5" fill="var(--dc-surface)" />
        <circle data-node="1" cx="72" cy="28" r="6" fill="var(--dc-logo-light)" />
        <circle data-node="2" cx="52" cy="56" r="7.5" fill="var(--dc-surface)" />
        <circle data-node="3" cx="74" cy="74" r="6" fill="var(--dc-logo-light)" />
      </g>
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(defineProps<{ size?: number; svgSize?: number; radius?: number }>(), {
  size: 64,
  svgSize: 44,
  radius: 18,
})

const containerStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  borderRadius: `${props.radius}px`,
}))

// 品牌 logo 动效：4 个节点在多套阵型间平滑换位，连线跟随、脉冲沿边流动
const logoSvg = ref<SVGSVGElement | null>(null)
const LOGO_FORMATIONS: [number, number][][] = [
  [[28, 30], [72, 28], [52, 56], [74, 74]],
  [[30, 72], [74, 32], [34, 30], [70, 66]],
  [[72, 70], [28, 66], [70, 32], [30, 28]],
  [[50, 28], [74, 58], [50, 76], [26, 56]],
]
const LOGO_BASE_R = [6.5, 6, 7.5, 6]
let logoRaf = 0

function logoSmoother(t: number) {
  return t * t * t * (t * (t * 6 - 15) + 10)
}

onMounted(() => {
  const svg = logoSvg.value
  if (!svg || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const nodes = [0, 1, 2, 3].map((i) => svg.querySelector<SVGCircleElement>(`[data-node="${i}"]`))
  const parseEnds = (el: SVGElement, key: 'edge' | 'pulse') => {
    const [a = 0, b = 0] = (el.dataset[key] ?? '').split('-').map(Number)
    return { el, a, b }
  }
  const edges = [...svg.querySelectorAll<SVGLineElement>('[data-edge]')].map((el) =>
    parseEnds(el, 'edge'),
  )
  const pulses = [...svg.querySelectorAll<SVGCircleElement>('[data-pulse]')].map((el) =>
    parseEnds(el, 'pulse'),
  )
  const start = performance.now()

  const loop = (now: number) => {
    const N = LOGO_FORMATIONS.length
    const travel = 1700
    const hold = 700
    const seg = travel + hold
    const elapsed = now - start
    const idx = Math.floor(elapsed / seg) % N
    const local = elapsed % seg
    const e = logoSmoother(Math.min(local / travel, 1))
    const A = LOGO_FORMATIONS[idx] ?? []
    const B = LOGO_FORMATIONS[(idx + 1) % N] ?? []
    const pos = A.map((p, i): [number, number] => {
      const q = B[i] ?? p
      return [p[0] + (q[0] - p[0]) * e, p[1] + (q[1] - p[1]) * e]
    })
    nodes.forEach((n, i) => {
      const p = pos[i]
      if (!n || !p) return
      n.setAttribute('cx', p[0].toFixed(2))
      n.setAttribute('cy', p[1].toFixed(2))
      n.setAttribute('r', ((LOGO_BASE_R[i] ?? 6) * (1 + 0.06 * Math.sin(now / 280 + i))).toFixed(2))
    })
    edges.forEach(({ el, a, b }) => {
      const pa = pos[a]
      const pb = pos[b]
      if (!pa || !pb) return
      el.setAttribute('x1', pa[0].toFixed(2))
      el.setAttribute('y1', pa[1].toFixed(2))
      el.setAttribute('x2', pb[0].toFixed(2))
      el.setAttribute('y2', pb[1].toFixed(2))
    })
    pulses.forEach(({ el, a, b }, k) => {
      const pa = pos[a]
      const pb = pos[b]
      if (!pa || !pb) return
      const u = (now / 1500 + k * 0.34) % 1
      el.setAttribute('cx', (pa[0] + (pb[0] - pa[0]) * u).toFixed(2))
      el.setAttribute('cy', (pa[1] + (pb[1] - pa[1]) * u).toFixed(2))
      el.setAttribute('opacity', Math.sin(Math.PI * u).toFixed(2))
    })
    logoRaf = requestAnimationFrame(loop)
  }
  logoRaf = requestAnimationFrame(loop)
})

onBeforeUnmount(() => {
  if (logoRaf) cancelAnimationFrame(logoRaf)
})
</script>

<style scoped>
.brand-logo {
  overflow: hidden;
  background: linear-gradient(150deg, var(--el-color-primary), var(--dc-logo-gradient-end));
  box-shadow: 0 12px 26px -8px var(--dc-logo-shadow);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
