<template>
  <div class="mobile">
    <el-alert
      v-if="!currentTab"
      class="hint"
      type="error"
      :closable="false"
      title="当前账号没有移动端相关的任何权限"
      description="三个页签分别认 system:release（Android 版本）、system:video:manage（视频分类）、system:feedback（意见反馈），一个都没有时这一屏没有可显示的内容。"
    />

    <!-- 页签本身在顶栏（AdminLayout 的 el-segmented，与 admin / 退出 同一行），这里只出内容：
         再放一排 el-tabs 就是同一件事两个位置，切起来还会打架 -->
    <ReleasesView v-else-if="currentTab === 'android'" lock-platform="android" title="Android 版本管理" />
    <VideoCategoryPanel
      v-else-if="currentTab === 'categories'"
      hint="手机客户端视频页顶部的标签栏就是这一套分类（与 Web 视频板块共用同一份字典，改一处两端同时生效）：增删改、排序（改 sort）、启停。客户端那边第一项「直播」是它自己的固定入口，不来自这里。"
    />
    <FeedbackView v-else-if="currentTab === 'feedback'" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import FeedbackView from '@/views/FeedbackView.vue'
import ReleasesView from '@/views/ReleasesView.vue'
import VideoCategoryPanel from '@/components/VideoCategoryPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { resolveMobileTab, visibleMobileTabs } from '@/utils/mobileTabs'

/**
 * 后台「移动端」这一屏：凡只服务手机客户端的管理动作，都收在这一个入口里，页签在顶栏切换。
 *
 * 本组件是个<b>薄壳</b>——三个面板分别是既有视图与既有组件，这里不写任何业务逻辑，也不新造接口。
 * 与 {@code ImagesView}/{@code MusicsView}/{@code NovelsView} 三页共用 {@code WorkManage} 是同一套路子：
 * 一处实现、多个入口，免得日后同一段逻辑三处各改各的。
 *
 * <b>页签条画在 AdminLayout 的顶栏</b>，与六个内容板块的切换条同一位置、同一显隐规则（只在
 * 自己那一屏出现），所以本页只按 {@code ?tab=} 决定渲染哪个面板。两边都调
 * {@code resolveMobileTab} 算同一个值：手输一个没权限的 tab、或者干脆不带参数时，
 * 顶栏的高亮与屏幕上的内容仍指向同一页，不会出现「高亮写着意见反馈、内容是版本表」。
 *
 * 三个页签<b>各判各的权限点</b>，而不是新增一个 system:mobile:manage：
 * ① 不用往 im_permission 插种子，也就省掉一次 SQL 变更与鉴权缓存清理；
 * ② 授权语义本来就该分开——能发 APK 的人未必能改分类，能改分类的人未必能看用户反馈。
 *
 * 旧菜单（版本管理 / 意见反馈 / 视频管理·分类管理）<b>全部保留</b>：两处入口吃的是同一批数据，
 * 删掉旧入口会让已收藏的地址与同事的操作习惯一起断掉，而收益只是菜单少三条。
 *
 * 后续新增移动端专属的面板（推送、启动页配置、App 内活动位等）改 utils/mobileTabs 那张表，
 * 再在这里加一个分支——不要往侧栏堆条目，侧栏已经二十多条了。
 */
const auth = useAuthStore()
const route = useRoute()

const tabs = computed(() => visibleMobileTabs((code) => auth.can(code)))
const currentTab = computed(() => resolveMobileTab(tabs.value, route.query.tab))
</script>

<style scoped>
.hint {
  margin-bottom: 16px;
}
</style>
