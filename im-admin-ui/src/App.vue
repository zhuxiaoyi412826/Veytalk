<template>
  <router-view />
</template>

<script setup>
/**
 * 根组件只放一层 router-view。
 *
 * 刻意不做全局 loading 遮罩与全局错误边界：登录页与三个内页各自管自己的加载态，
 * 一个盖在整屏上的转圈会让「看板在刷、表格也在刷」这两种状态混成一团，
 * 反而看不出卡在哪一个请求上。
 */
</script>
