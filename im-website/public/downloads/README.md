# 下载产物放置目录

官网三个下载按钮的 href 固定指向本目录下三个规范文件名（ASCII、无空格）：

| 文件名 | 内容 | 来源 |
| --- | --- | --- |
| `Veytalk-Setup-<版本>.exe` | Windows NSIS 安装版 | `electron/release/IM通讯 Setup <版本>.exe` 拷贝改名 |
| `Veytalk-Windows-x64-portable.zip` | Windows 免安装绿色版 | `electron/release/win-unpacked/` 整目录压缩 |
| `Veytalk-Android.apk` | Android 客户端 | 仓库内任意 `*.apk`（暂未产出，缺失时按钮 404） |

一键收集（在 im-website 目录）：

```powershell
npm run fetch:downloads
```

脚本会读取 `electron/package.json` 的 version 拼出目标文件名，源产物不存在时逐项跳过并给出提示。
大文件不进 git（.gitignore 已排除本目录下 exe/zip/apk），部署时把产物拷到此处或改用 GitHub Releases 直链。
