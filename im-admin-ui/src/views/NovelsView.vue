<template>
  <WorkManage type="novel" title="小说管理" perm="system:novel:manage" />
</template>

<script setup>
/**
 * 后台「小说管理」屏：直播页小说板块的正文列表与分类字典。
 *
 * 与前两个页面同一个 WorkManage，只是 type 换成 novel：列表多一列字数，表单换成摘要 +
 * Markdown 正文，查看弹窗渲染成正文核对。注意小说列表响应刻意不含正文（LONGTEXT），
 * 所以编辑与查看都会多走一次详情接口。权限点 131 system:novel:manage。
 */
import WorkManage from '@/components/WorkManage.vue'
</script>
