<template>
  <WorkManage type="image" title="图片管理" perm="system:image:manage" />
</template>

<script setup>
/**
 * 后台「图片管理」屏：直播页图片板块（图集）的作品列表与分类字典。
 *
 * 三个板块页面都是 WorkManage 的薄壳，只有 type / title / perm 三个参数不同——
 * 表格列、表单形态、接口路径全部由 WorkManage 按 type 分支，这里刻意不写任何逻辑，
 * 免得日后「三处同构代码各改各的」。权限点 129 system:image:manage，种子在 sql/versions/V1.0.6。
 */
import WorkManage from '@/components/WorkManage.vue'
</script>
