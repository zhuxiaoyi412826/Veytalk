import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as elementIcons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
// Element Plus 官方深色变量：引入后只要给 <html> 加 .dark 类，所有 EP 组件自动转深色
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { useSettingsStore } from './stores/settings'
import './styles/index.css'

const app = createApp(App)

// pinia 必须在任何 store 被使用之前装好：router 的前置守卫只读 localStorage 不碰 store，
// 但 ws/dispatch 会在布局组件挂载时取 store，顺序错了会拿到 undefined
app.use(createPinia())
app.use(router)
// 中文 locale：分页器的「共 x 条」、日期选择器的月份名等内置文案都靠它
app.use(ElementPlus, { locale: zhCn })

// 首屏就把上次的主题/字号/主色刷到 DOM：必须在 pinia 装好之后、mount 之前，
// 否则会先看到一闪默认蓝色再变用户选的主题。
useSettingsStore().init()

// 图标全量注册，名字就是官方导出的 PascalCase（Plus / Search / Delete …），
// 与 element-plus 自己的 ElXxx 组件不会重名。
// 模板里统一写 <el-icon><Plus /></el-icon>，不必在每个组件里单独 import。
Object.entries(elementIcons).forEach(([name, component]) => {
  app.component(name, component)
})

/*
 * 手机软键盘收起后把可视视口的残留平移抹平。
 *
 * 本页是 html/body/#app 全 100% + body overflow:hidden 的固定壳，页面自己永不滚动。
 * 浏览器为了露出被键盘挡住的输入框会把可视视口往上顶；键盘收起时若没复位（移动端
 * 浏览器的老毛病），偏移就留在那里，而命中测试是跟着可视视口走的——于是「看到的」
 * 和「点得到的」错开一段，点输入框弹不出键盘、点别的也落错地方，
 * 只有回桌面或重登这种整页重布局才能救。index.html 的 interactive-widget 是预防，
 * 这里是兜底：不支持那个参数的浏览器上，键盘一收起（可视视口高度回到布局高度）
 * 就主动把平移归零。键盘还开着时不动，免得跟浏览器抢着滚动把输入框推出视野。 */
const visualViewport = window.visualViewport
if (visualViewport) {
  const settleViewport = () => {
    if (visualViewport.height < window.innerHeight - 2) {
      return
    }
    if (Math.abs(visualViewport.offsetTop) < 2 && Math.abs(visualViewport.offsetLeft) < 2) {
      return
    }
    window.scrollTo(0, 0)
    visualViewport.scrollTo?.(0, 0)
  }
  visualViewport.addEventListener('resize', settleViewport)
  visualViewport.addEventListener('scroll', settleViewport)
}

app.mount('#app')
