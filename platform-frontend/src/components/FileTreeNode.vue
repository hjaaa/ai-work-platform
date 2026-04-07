<template>
  <div>
    <div
      :class="['tree-node', { selected: selectedPath === node.path }]"
      :style="{ paddingLeft: depth * 16 + 8 + 'px' }"
      @click="handleClick"
    >
      <el-icon v-if="node.directory" :size="14">
        <component :is="expanded ? 'FolderOpened' : 'Folder'" />
      </el-icon>
      <el-icon v-else :size="14"><Document /></el-icon>
      <span class="node-name">{{ node.name }}</span>
    </div>
    <template v-if="node.directory && expanded && node.children">
      <FileTreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :depth="depth + 1"
        :selected-path="selectedPath"
        @select="$emit('select', $event)"
      />
    </template>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  selectedPath: { type: String, default: '' }
})

const emit = defineEmits(['select'])
const expanded = ref(props.depth < 2)

function handleClick() {
  if (props.node.directory) {
    expanded.value = !expanded.value
  } else {
    emit('select', props.node.path)
  }
}
</script>

<style scoped>
.tree-node {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}
.tree-node:hover {
  background: #f5f7fa;
}
.tree-node.selected {
  background: #ecf5ff;
  color: #409eff;
}
.node-name {
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
