/**
 * 「移动端」入口的页签定义。
 *
 * 单独成一个模块是因为这份表有<b>两个消费者</b>：侧栏菜单（决定这条菜单出不出现）、
 * 顶栏的分段控件（决定切换条上有哪些项与高亮哪一个），而内容区（MobileView）还要按同一份表
 * 决定渲染哪个面板。三处各抄一份，迟早出现「菜单里有、顶栏上没得、点进去渲染第三个」这种对不上号的状态，
 * 而且是从界面上看不出来的那种——所以这里只留一份，两侧都从它取。
 *
 * 每个页签<b>各认各的权限点</b>，不合并成 system:mobile:manage：能往公网递 APK 的人未必能改分类，
 * 能改分类的人未必能读用户反馈。合并只会逼着超级管理员给人整套打包授权。
 */
export const MOBILE_TABS = [
  { value: 'android', label: 'Android 版本', perm: 'system:release' },
  { value: 'categories', label: '视频分类', perm: 'system:video:manage' },
  { value: 'feedback', label: '意见反馈', perm: 'system:feedback' }
]

/**
 * 当前账号能看到的页签。
 *
 * @param can 判定函数，调用方传 (code) => auth.can(code)。这里不直接 import store：
 *            utils 层一旦被 store 反向依赖，就会踩上和 router/index.js 同一类循环引用问题。
 */
export function visibleMobileTabs(can) {
  return MOBILE_TABS.filter((tab) => can(tab.perm))
}

/**
 * 落定的页签：地址栏里的 tab 参数只在<b>可见</b>集合里才作数，否则回落到第一个可见项。
 *
 * 要这么绕一层是因为两处都得算出同一个答案：手输 ?tab=feedback 但账号没有反馈权限时，
 * 如果内容区自己回落而顶栏仍显示 feedback，就得到「高亮的页签和屏幕上的内容不是一回事」。
 * 同一条规则写在函数里，两边各调一次，天然对得上。
 */
export function resolveMobileTab(tabs, requested) {
  return tabs.find((tab) => tab.value === requested)?.value || tabs[0]?.value || ''
}
