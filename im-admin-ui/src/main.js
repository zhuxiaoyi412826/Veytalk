import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as elementIcons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/index.css'

/**
 * 后台入口。
 *
 * 与 im-ui/main.js 的差异都是有意的：
 *  - <b>不引 dark/css-vars、不装 settings store</b>：后台是干活用的界面，
 *    一期不做主题切换，引了深色变量就得给每个页面补一套暗色适配，那是纯成本；
 *  - <b>不装 WebSocket、不做 Electron 地址推导</b>：后台是纯拉模型；
 *  - <b>不做手机视口兜底</b>：后台的表格与侧边菜单本来就不适合窄屏，
 *    小屏上访问由布局给一句「请在电脑上使用」。
 */
const app = createApp(App)

// pinia 必须在任何 store 被使用之前装好：router 守卫里的动态 import 会取 auth store
app.use(createPinia())
app.use(router)
// 中文 locale：分页器的「共 x 条」、表格的「暂无数据」等内置文案都靠它
app.use(ElementPlus, { locale: zhCn })

// 图标全量注册，模板里直接写 <el-icon><Refresh /></el-icon>，不必逐组件 import
Object.entries(elementIcons).forEach(([name, component]) => {
  app.component(name, component)
})

app.mount('#app')
