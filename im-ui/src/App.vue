<template>
  <router-view />
</template>

<script setup>
// 根组件只负责承载路由。
// 登录页与主界面用的是两套完全不同的布局（前者居中卡片、后者三栏），
// 所以这里不放任何公共外壳，公共部分全部在 layout/MainLayout.vue 里。
</script>
