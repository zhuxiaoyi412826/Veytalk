/**
 * 移动端可靠的「代码调起文件选择框」。
 *
 * 两个手机端陷阱：
 * 1. input 被 display:none 隐藏时，部分手机浏览器 / WebView 会静默忽略 click()，
 *    表现为点按钮没反应、唤不起相册和文件管理器。input 必须「渲染但不可见」
 *    （1px + opacity:0，见各视图的 xx-file-input 样式），不能用 display:none。
 * 2. showPicker() 是专为「代码调起选择框」设计的标准 API，移动端比模拟 click() 可靠；
 *    不支持或抛错（如非用户手势触发）时回退 click()。注意它返回 Promise，
 *    被安全策略异步拒绝时同步 try/catch 抓不到，必须 .catch 里回退 click()。
 * 3. 荣耀自带浏览器等国产魔改浏览器可能连 JS 间接调起都静默拦截，
 *    那种场景只能让手指直接点在 input 本体上（见 ChatWindow 的透明覆盖层做法）。
 *
 * 注意：必须在 click/touch 回调里同步调用，前面不能有任何 await，
 * 否则脱离用户手势上下文，移动端会拒绝弹出选择框。
 */
export function openFilePicker(input) {
  if (!input) return
  if (typeof input.showPicker === 'function') {
    try {
      const result = input.showPicker()
      // showPicker 返回 Promise：被浏览器安全策略异步拒绝时（国产手机浏览器常见）回退 click()
      if (result && typeof result.catch === 'function') {
        result.catch(() => input.click())
      }
      return
    } catch {
      // 同步抛错（非用户手势等）落到 click()
    }
  }
  input.click()
}
