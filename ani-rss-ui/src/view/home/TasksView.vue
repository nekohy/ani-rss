<template>
  <div class="torrents-page app-page-layout">
    <PageHeaderView title="下载" :subtitle="`OpenList 共 ${tasks.length} 个任务`"/>
    <div class="torrents-body app-page-content app-page-padding">
      <div class="torrents-container">
        <div class="torrents-toolbar">
          <el-tabs v-model="activeTab" class="torrents-tabs">
            <el-tab-pane name="undone">
              <template #label>
                <span class="tab-label">进行中</span>
                <el-tag size="small" type="primary">{{ undoneTasks.length }}</el-tag>
              </template>
            </el-tab-pane>
            <el-tab-pane name="done">
              <template #label>
                <span class="tab-label">已结束</span>
                <el-tag size="small" type="success">{{ doneTasks.length }}</el-tag>
              </template>
            </el-tab-pane>
          </el-tabs>
          <div class="sort-actions">
            <el-dropdown trigger="click" @command="changeSortType">
              <el-button class="sort-field-button">
                <el-icon>
                  <Sort/>
                </el-icon>
                <span>{{ currentSortLabel }}</span>
                <el-icon class="sort-field-arrow">
                  <ArrowDown/>
                </el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                      v-for="item in sortTypeList"
                      :key="item.value"
                      :command="item.value">
                    <span class="sort-option-label">{{ item.label }}</span>
                    <el-icon v-if="sortType === item.value" class="el-icon--right">
                      <Check/>
                    </el-icon>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-tooltip :content="sortOrder === 'asc' ? '正序' : '倒序'" placement="top">
              <el-button
                  :aria-label="sortOrder === 'asc' ? '正序' : '倒序'"
                  class="sort-order-button"
                  @click="toggleSortOrder">
                <el-icon>
                  <SortUp v-if="sortOrder === 'asc'"/>
                  <SortDown v-else/>
                </el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>
        <el-empty v-if="!activeTasks.length" :description="emptyDescription" class="torrents-empty"/>
        <el-scrollbar v-else class="torrents-scrollbar">
          <el-card v-for="task in activeTasks"
                   :key="task.type + task.id"
                   shadow="never"
                   class="torrents-card">
            <p>{{ task.title }}</p>
            <el-text v-if="task.detail" class="torrents-detail" size="small" type="info">{{ task.detail }}</el-text>
            <el-progress :percentage="task.percentage" :status="progressStatus(task)"/>
            <div class="torrents-size-info">
              <span>
                <span class="torrents-size-value">{{ formatTorrentSize(task.completed) }}</span>
                /
                <span class="torrents-size-value">{{ formatTorrentSize(task.size) }}</span>
              </span>
              <span v-if="task.status" class="torrents-size-label">{{ task.status }}</span>
            </div>
            <el-text v-if="task.error" class="torrents-detail" size="small" type="danger">{{ task.error }}</el-text>
            <template #footer>
              <div class="flex torrents-footer">
                <div>
                  <el-tag class="torrents-tag-spacer" type="info">
                    {{ task.type === 'move' ? '移动' : '离线下载' }}
                  </el-tag>
                </div>
                <div>
                  <el-tag class="torrents-tag-spacer" :type="stateType(task)">
                    {{ stateLabel(task) }}
                  </el-tag>
                </div>
              </div>
            </template>
          </el-card>
        </el-scrollbar>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, onActivated, onDeactivated, onUnmounted, ref} from "vue";
import * as http from "@/js/http.js";
import {ArrowDown, Check, Sort, SortDown, SortUp} from "@element-plus/icons-vue";
import {formatSize} from "@/js/format.js";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";

const activeTab = ref('undone')
// 记录排序方式
let sortType = ref('name')
// 记录排序顺序 asc=正序, desc=倒序
let sortOrder = ref('asc')

let sortTypeList = [
  {
    label: "名称",
    value: "name",
    fun: (value) => {
      return value.sort((a, b) => a.title.localeCompare(b.title));
    }
  },
  {
    label: "进度",
    value: "progress",
    fun: (value) => {
      return value.sort((a, b) => b.percentage - a.percentage);
    }
  }
]

// OpenList 的任务状态 (tache)
const STATES = {
  Pending: ['等待中', 'info'],
  Running: ['进行中', 'primary'],
  Succeeded: ['成功', 'success'],
  Canceling: ['取消中', 'warning'],
  Canceled: ['已取消', 'info'],
  Error: ['出错', 'danger'],
  Failing: ['失败中', 'danger'],
  Failed: ['失败', 'danger'],
  Waiting_for_Retry: ['等待重试', 'warning'],
  Preparing_to_Retry: ['准备重试', 'warning']
}

let polling = false
let stopped = false

let tasks = ref([])

const doneTasks = computed(() => tasks.value.filter(task => task.done))
const undoneTasks = computed(() => tasks.value.filter(task => !task.done))
const activeTasks = computed(() => activeTab.value === 'done' ? doneTasks.value : undoneTasks.value)
const emptyDescription = computed(() => activeTab.value === 'done' ? '当前无已结束任务' : '当前无进行中任务')
const currentSortLabel = computed(() => sortTypeList.find(item => item.value === sortType.value)?.label || '名称')

const stateLabel = task => STATES[task.state]?.[0] || task.state
const stateType = task => STATES[task.state]?.[1] || 'info'
const progressStatus = task => task.state === 'Succeeded' ? 'success' : (task.error ? 'exception' : '')

// download magnet:?xt=... to (/115/花月/offline/xx E01)
const DOWNLOAD_REG = /^download (.+) to \((.+)\)$/
// move [/115](/花月/offline/xx E01/xx E01.mkv) to [/onedrive](/花月/Anime/...)
const MOVE_REG = /^move \[(.*?)]\((.*?)\) to \[(.*?)]\((.*?)\)$/

const baseName = path => path.replace(/\/+$/, '').split('/').pop() || path

/**
 * 任务名拆成标题和详情, 补上大小和进度
 */
const toTask = info => {
  let title = info.name
  let detail = ''
  let m = DOWNLOAD_REG.exec(info.name)
  if (m) {
    title = baseName(m[2])
    detail = m[2]
  } else if ((m = MOVE_REG.exec(info.name))) {
    title = baseName(m[2])
    detail = `→ ${(m[3] + m[4]).replace(/\/+/g, '/')}`
  }
  const percentage = Math.min(100, Math.round((Number(info.progress) || 0) * 10) / 10)
  const size = Number(info.totalBytes)
  return {
    ...info,
    title,
    detail,
    percentage,
    size,
    completed: size * percentage / 100
  }
}

const formatTorrentSize = bytes => {
  const size = Number(bytes)
  if (!Number.isFinite(size) || size <= 0) {
    return '-'
  }
  return formatSize(size)
}

const resort = () => {
  tasks.value = sortInfos(tasks.value)
}

let changeSortType = type => {
  if (sortType.value === type) {
    return
  }
  sortType.value = type
  sortOrder.value = 'asc'
  resort()
}

let toggleSortOrder = () => {
  sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  resort()
}

let sortInfos = (infos) => {
  for (let sortTypeItem of sortTypeList) {
    let {value, fun} = sortTypeItem;
    if (value !== sortType.value) {
      continue
    }
    let sorted = fun([...infos])
    return sortOrder.value === 'asc' ? sorted : sorted.reverse()
  }
  return infos;
}

let startPolling = async () => {
  if (polling) {
    return
  }
  polling = true
  while (!stopped) {
    try {
      let res = await http.openListTasks()
      let infos = await res.data
      tasks.value = sortInfos(infos.map(toTask))
    } catch (_) {
    }
    await sleep(3000)
  }
  polling = false
}

let sleep = ms => {
  return new Promise(resolve => setTimeout(resolve, ms));
}

const resumePolling = () => {
  stopped = false
  startPolling()
}

const pausePolling = () => {
  stopped = true
}

onActivated(resumePolling)
onDeactivated(pausePolling)
onUnmounted(pausePolling)
</script>

<style scoped>
.torrents-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.torrents-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px 10px;
  flex-shrink: 0;
}

.torrents-tabs {
  flex: 1;
  min-width: 0;
}

.torrents-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.torrents-tabs :deep(.el-tabs__nav-wrap:after) {
  height: 0;
}

.torrents-tabs :deep(.el-tabs__item) {
  height: 36px;
  font-weight: 600;
}

.tab-label {
  margin-right: 6px;
  flex-shrink: 0;
}

.sort-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
}

.sort-actions .el-button {
  margin-left: 0;
}

.sort-field-button {
  min-width: 82px;
  padding: 0 9px;
}

.sort-field-arrow {
  margin-left: 6px;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.sort-order-button {
  width: 30px;
  height: 30px;
  padding: 0;
}

.torrents-scrollbar {
  flex: 1;
  overflow: hidden;
}

.torrents-empty {
  flex: 1;
}

.torrents-card {
  margin-bottom: 4px;
}

.torrents-detail {
  display: block;
  margin-bottom: 6px;
  word-break: break-all;
}

.torrents-size-info {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-top: 6px;
  font-size: 13px;
  line-height: 20px;
  font-variant-numeric: tabular-nums;
}

.torrents-size-label {
  color: var(--el-text-color-placeholder);
}

.torrents-size-value {
  color: var(--el-text-color-regular);
}

.torrents-footer {
  width: 100%;
  justify-content: space-between;
}

.torrents-tag-spacer {
  margin-top: 4px;
  margin-left: 4px;
}

@media (max-width: 700px) {
  .torrents-toolbar {
    align-items: flex-end;
    gap: 6px;
    padding: 0 0 8px;
  }

  .torrents-tabs :deep(.el-tabs__item) {
    padding: 0 10px;
  }
}
</style>
