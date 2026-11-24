<template>
  <WorkManage type="music" title="音乐管理" perm="system:music:manage" />
</template>

<script setup>
/**
 * 后台「音乐管理」屏：直播页音乐板块的音频列表与分类字典。
 *
 * 与 ImagesView 同一个 WorkManage，只是 type 换成 music：列表多一列时长，
 * 表单里图集换成单个音频文件，查看弹窗换成可试听的 <audio>。权限点 130 system:music:manage。
 */
import WorkManage from '@/components/WorkManage.vue'
</script>
