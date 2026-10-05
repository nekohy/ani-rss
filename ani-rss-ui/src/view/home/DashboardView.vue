<template>
  <div class="dashboard-page app-page-layout">
    <EditAniView ref="editAniRef"/>
    <PlayListView ref="playListRef"/>
    <CoverView ref="coverRef"/>
    <DelAniView ref="delAniRef"/>
    <BgmRateView ref="bgmRateRef"/>
    <PageHeaderView title="首页" :subtitle="`${todayLabel} · ${todayText}`">
      <template #actions>
        <el-button :loading="refreshLoading" icon="Refresh" @click="loadAll" class="auto-button">
          刷新
        </el-button>
      </template>
    </PageHeaderView>
    <div class="dashboard-body app-page-content app-page-padding">

      <el-scrollbar class="dashboard-scrollbar">
        <div class="dashboard-content">
          <div class="metric-grid">
            <div class="metric-item">
              <div class="metric-icon subscriptions">
                <el-icon>
                  <List/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">订阅数量</el-text>
                <div class="metric-value">{{ subscriptionTotal }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon enabled">
                <el-icon>
                  <CircleCheck/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">已启用</el-text>
                <div class="metric-value">{{ enabledTotal }}</div>
              </div>
            </div>
          </div>

          <section class="dashboard-section today-section">
            <div class="today-section-content">
              <div class="section-title">
                <h3>今天的订阅</h3>
                <div class="today-heading-actions">
                  <el-tag type="info">{{ todayAnis.length }}</el-tag>
                  <div v-if="todayAnis.length" class="today-scroll-actions">
                    <el-button aria-label="向左滚动" bg circle text @click="scrollToday(-1)">
                      <el-icon>
                        <ArrowLeft/>
                      </el-icon>
                    </el-button>
                    <el-button aria-label="向右滚动" bg circle text @click="scrollToday(1)">
                      <el-icon>
                        <ArrowRight/>
                      </el-icon>
                    </el-button>
                  </div>
                </div>
              </div>
              <el-empty v-if="!todayAnis.length" description="今天没有订阅"/>
              <div v-else ref="todayTrack" class="today-track">
                <div v-for="ani in todayAnis"
                     :key="ani.id"
                     class="today-item">
                  <AniCoverView
                      :item="ani"
                      @edit="editAniRef?.show"
                      @playlist="playListRef?.show"
                      @cover="coverRef?.show"
                      @del="delAniRef?.show"
                      @rate="bgmRateRef?.show"/>
                </div>
              </div>
            </div>
          </section>

          <section class="dashboard-section">
            <div class="section-title">
              <h3>疑似停更列表</h3>
              <el-tag type="warning">{{ procrastinatingList.length }}</el-tag>
            </div>
            <el-empty v-if="!procrastinatingList.length" description="暂无疑似停更订阅"/>
            <el-table v-else :data="procrastinatingList" class="dashboard-table" size="small">
              <el-table-column label="订阅" min-width="220" prop="title" show-overflow-tooltip/>
              <el-table-column label="进度" width="100">
                <template #default="{ row }">
                  {{ row.currentEpisodeNumber }} / {{ row.totalEpisodeNumber || '*' }}
                </template>
              </el-table-column>
              <el-table-column label="最后下载" width="150">
                <template #default="{ row }">
                  {{ row.lastDownloadTime > 0 ? fromNow(row.lastDownloadTime) : formatDate(row.releaseDate) }}
                </template>
              </el-table-column>
              <el-table-column label="间隔" width="90">
                <template #default="{ row }">
                  {{ row.procrastinatingDays }} 天
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import {computed, onActivated, ref} from "vue";
import {ArrowLeft, ArrowRight, CircleCheck, List} from "@element-plus/icons-vue";
import {formatDate, fromNow} from "@/js/format.js";
import * as http from "@/js/http.js";
import AniCoverView from "@/view/home/AniCoverView.vue";
import EditAniView from "@/view/home/EditAniView.vue";
import PlayListView from "@/view/play/PlayListView.vue";
import CoverView from "@/view/home/CoverView.vue";
import DelAniView from "@/view/home/DelAniView.vue";
import BgmRateView from "@/view/home/BgmRateView.vue";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";

const weekLabels = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const dayMs = 24 * 60 * 60 * 1000

const refreshLoading = ref(false)
const weekList = ref([])
const subscriptionTotalValue = ref(0)
const todayTrack = ref()
const editAniRef = ref()
const delAniRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()
const config = ref({
  procrastinatingDay: 14
})

const todayLabel = computed(() => weekLabels[new Date().getDay()])
const flatAnis = computed(() => weekList.value.flatMap(week => week.items || []))
const enabledAnis = computed(() => flatAnis.value.filter(item => item.enable))
const subscriptionTotal = computed(() => subscriptionTotalValue.value || flatAnis.value.length)
const enabledTotal = computed(() => enabledAnis.value.length)
const todayAnis = computed(() => {
  const today = weekList.value.find(week => week.weekLabel === todayLabel.value)
  return today ? (today.items || []).filter(item => item.enable) : []
})
const todayText = computed(() => todayAnis.value.length ? `${todayAnis.value.length} 个订阅` : '没有订阅')
const procrastinatingList = computed(() => {
  const threshold = Number(config.value.procrastinatingDay || 14)
  return enabledAnis.value
      .filter(item => item.procrastinating !== false)
      .filter(item => !item.totalEpisodeNumber || item.currentEpisodeNumber < item.totalEpisodeNumber)
      .map(item => {
        const time = getCompareTime(item)
        const procrastinatingDays = time ? Math.floor((Date.now() - time) / dayMs) : 0
        return {
          ...item,
          procrastinatingDays
        }
      })
      .filter(item => item.procrastinatingDays >= threshold)
      .sort((a, b) => b.procrastinatingDays - a.procrastinatingDays)
})

const getCompareTime = item => {
  if (item.lastDownloadTime > 0) {
    return item.lastDownloadTime
  }
  const releaseTime = new Date(item.releaseDate).getTime()
  return Number.isNaN(releaseTime) ? 0 : releaseTime
}

const scrollToday = direction => {
  const track = todayTrack.value
  if (!track) {
    return
  }
  track.scrollBy({
    left: direction * Math.min(track.clientWidth * 0.75, 560),
    behavior: 'smooth'
  })
}

const loadAni = async () => {
  const res = await http.listAni()
  weekList.value = res.data.weekList || []
  subscriptionTotalValue.value = res.data.total || 0
}

const loadConfig = async () => {
  const res = await http.config()
  config.value = res.data || config.value
}

const loadAll = async () => {
  refreshLoading.value = true
  try {
    await Promise.all([
      loadAni(),
      loadConfig()
    ])
  } finally {
    refreshLoading.value = false
  }
}

onActivated(loadAll)
</script>

<style scoped>
.metric-grid {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.metric-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.metric-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.metric-icon.subscriptions {
  color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.metric-icon.enabled {
  color: var(--el-color-success);
  background-color: var(--el-color-success-light-9);
}

.metric-value {
  margin-top: 2px;
  font-size: 24px;
  line-height: 1.2;
  font-weight: 700;
}

.dashboard-scrollbar {
  flex: 1;
  min-height: 0;
}

.dashboard-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  padding-bottom: 8px;
}

.dashboard-section {
  min-width: 0;
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.section-title h3 {
  font-size: 16px;
  line-height: 1.4;
}

.today-section {
  position: relative;
  grid-column: 1 / -1;
  overflow: hidden;
}

.today-section-content {
  min-width: 0;
}

.today-heading-actions,
.today-scroll-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.today-scroll-actions .el-button {
  width: 28px;
  height: 28px;
  margin-left: 0;
}

.today-track {
  min-width: 0;
  display: flex;
  gap: 10px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 2px 2px 8px;
  scroll-behavior: smooth;
  scroll-snap-type: x proximity;
  scrollbar-color: var(--el-border-color) transparent;
  scrollbar-width: thin;
}

.today-track::-webkit-scrollbar {
  height: 6px;
}

.today-track::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: var(--el-border-color);
}

.today-track::-webkit-scrollbar-track {
  background-color: transparent;
}

.today-item {
  flex: 0 0 142px;
  min-width: 0;
  scroll-snap-align: start;
}

.dashboard-table {
  width: 100%;
}

@media (max-width: 560px) {
  .metric-item {
    padding: 10px;
  }

  .today-scroll-actions {
    display: none;
  }

  .today-item {
    flex-basis: 118px;
  }
}
</style>
