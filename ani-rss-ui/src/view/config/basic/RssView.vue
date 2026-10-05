<template>
  <SettingsItem label="RSS开关">
    <el-switch v-model:model-value="props.config.rss"/>
  </SettingsItem>
  <SettingsItem label="RSS间隔">
    <el-input-number v-model:model-value="props.config.rssSleepMinutes" :disabled="!props.config.rss" :min="10">
      <template #suffix>
        <span>分钟</span>
      </template>
    </el-input-number>
  </SettingsItem>
  <SettingsItem label="RSS超时">
    <el-input-number v-model:model-value="props.config['rssTimeout']"
                     :max="60" :min="6">
      <template #suffix>
        <span>秒</span>
      </template>
    </el-input-number>
  </SettingsItem>
  <SettingsItem label="自动禁用订阅">
    <div class="full-width">
      <el-switch v-model:model-value="props.config.autoDisabled"/>
      <br>
      <el-text class="mx-1" size="small">
        根据 Bangumi 获取总集数 当所有集数都已下载时自动禁用该订阅
      </el-text>
    </div>
  </SettingsItem>
  <SettingsItem label="自动更新总集数">
    <div class="full-width">
      <el-switch v-model="props.config.updateTotalEpisodeNumber"/>
      <div>
        <el-checkbox v-model="props.config.forceUpdateTotalEpisodeNumber"
                     :disabled="!props.config.updateTotalEpisodeNumber"
                     class="el-checkbox-danger"
                     label="强制更新"/>
      </div>
    </div>
  </SettingsItem>
  <SettingsItem label="自动跳过X.5集">
    <el-switch v-model:model-value="props.config.skip5"/>
  </SettingsItem>
  <SettingsItem label="遗漏检测">
    <div>
      <div>
        <el-switch v-model:model-value="props.config.omit"/>
      </div>
      <el-text class="mx-1" size="small">
        总开关 若检测到RSS中集数出现遗漏会发送通知
      </el-text>
    </div>
  </SettingsItem>
  <SettingsItem label="摸鱼检测">
    <div>
      <div>
        <el-switch v-model="props.config['procrastinating']"/>
      </div>
      <div>
        <el-input-number v-model="props.config['procrastinatingDay']"
                         :disabled="!props.config['procrastinating']" :max="365"
                         :min="7">
          <template #suffix>
            <span>天</span>
          </template>
        </el-input-number>
      </div>
      <el-checkbox
          :disabled="!props.config['procrastinating']"
          v-model="props.config.procrastinatingMasterOnly"
          label="仅启用主RSS摸鱼检测"/>
      <br>
      <el-text class="mx-1" size="small">
        检测到主RSS更新摸鱼会发送通知<br>
        建议配合 <strong>自动禁用订阅</strong> 食用
      </el-text>
    </div>
  </SettingsItem>
  <SettingsItem label="备用RSS">
    <div class="full-width">
      <div>
        <el-switch v-model:model-value="props.config.standbyRss"/>
      </div>
      <div>
        <el-checkbox v-model="props.config['coexist']" :disabled="!props.config.standbyRss"
                     label="多字幕组共存模式"/>
        <el-checkbox v-model="props.config['copyMasterToStandby']" :disabled="!props.config.standbyRss"
                     label="添加订阅时自动复制主rss至备用rss"/>
      </div>
      <div class="flex full-width justify-end">
        <el-link
            type="primary"
            href="https://docs.wushuo.top/config/basic/rss#back-rss"
            target="_blank">
          详细说明
        </el-link>
      </div>
    </div>
  </SettingsItem>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";
import {ElText} from "element-plus";

let props = defineProps(['config'])
</script>

<style scoped>
.justify-end {
  justify-content: end;
}
</style>
