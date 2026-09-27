# IM 即时通讯系统 — 打包为 EXE（Windows 桌面端）与 Android（安卓端）

> 本文档基于项目技术栈：  
> **后端**：Spring Boot 4.0.8 + JDK 21，Maven 多模块，产物为 `im-server.jar`  
> **前端**：Vue 3.5 + Vite 7 + Element Plus 2.14，产物为 `dist/` 静态文件

---

## 目录

- [一、总体架构](#一总体架构)
- [二、需要安装的软件](#二需要安装的软件)
- [三、打包后端 JAR](#三打包后端-jar)
- [四、打包为 Windows EXE 桌面应用](#四打包为-windows-exe-桌面应用)
  - [方案 A：Electron（前端桌面壳）+ jpackage（后端 bundled JRE）](#方案-aelectron前端桌面壳--jpackage后端-bundled-jre)
  - [方案 B：Tauri（更轻量的替代）](#方案-btauri更轻量的替代)
- [五、打包为 Android APK](#五打包为-android-apk)
  - [Capacitor 方案](#capacitor-方案)
- [六、常见问题](#六常见问题)

---

## 一、总体架构

```
┌──────────────────────────────────────────────────┐
│                    用户设备                        │
│                                                    │
│  ┌─────────────┐    ┌──────────────────────────┐  │
│  │  EXE 桌面端  │    │     Android APK          │  │
│  │             │    │                          │  │
│  │ Electron壳  │    │  Capacitor WebView 壳    │  │
│  │  + Vue 前端  │    │  + Vue 前端（dist）       │  │
│  └──────┬──────┘    └────────────┬─────────────┘  │
│         │                        │                 │
│         └────────┬───────────────┘                 │
│                  │ HTTP + WebSocket                 │
│                  ▼                                  │
│  ┌──────────────────────────────┐                  │
│  │  后端服务（im-server.jar）    │                  │
│  │  Spring Boot 4 + JDK 21     │                  │
│  │  端口 8080                    │                  │
│  └──────────────┬───────────────┘                  │
│                 │                                   │
│     ┌───────────┼───────────┐                      │
│     ▼           ▼           ▼                      │
│   MySQL      Redis       MinIO                     │
└──────────────────────────────────────────────────┘
```

**EXE 桌面端**：Electron 提供桌面窗口，内嵌 Vue 前端页面，后端 JAR 通过 jpackage 打包为自带 JRE 的独立 exe，两者一起分发。

**Android 端**：Capacitor 将 Vue 前端打包为原生 WebView 壳应用，连接远程服务器上的后端。

---

## 二、需要安装的软件

### 2.1 通用依赖（已有则跳过）

| 软件 | 版本要求 | 用途 | 下载地址 |
|------|---------|------|---------|
| **JDK** | 21+ | 后端编译 & 运行 | https://adoptium.net/ |
| **Maven** | 3.8+ | 后端构建 | https://maven.apache.org/download.cgi |
| **Node.js** | 18+ (LTS) | 前端构建 | https://nodejs.org/ |
| **npm** | 9+ (随 Node 安装) | 包管理 | — |

### 2.2 EXE 打包所需

| 软件 | 版本要求 | 用途 | 下载地址 |
|------|---------|------|---------|
| **Electron** | 最新稳定版 | 将 Vue 前端包装为桌面应用 | https://www.electronjs.org/ |
| **electron-builder** | 最新稳定版 | 打包为 Windows 安装包（.exe / .msi） | https://www.electron.build/ |
| **jpackage** | JDK 21 自带 | 将后端 JAR 打包为自带 JRE 的 exe | JDK 内置 |
| **WiX Toolset 3** | 3.11+ | electron-builder 生成 MSI 安装包时需要 | https://wixtoolset.org/docs/wix3/ |

> **可选替代**：如果想用 Tauri 替代 Electron，需要安装 **Rust** (https://www.rust-lang.org/)。

### 2.3 Android 打包所需

| 软件 | 版本要求 | 用途 | 下载地址 |
|------|---------|------|---------|
| **Android Studio** | 最新稳定版 | Android SDK + 模拟器 + 构建工具 | https://developer.android.com/studio |
| **Android SDK** | API 33+ | 编译 Android 应用 | 通过 Android Studio 安装 |
| **Android SDK Build-Tools** | 33+ | APK 打包工具 | 通过 Android Studio 安装 |
| **Gradle** | 8+ (Capacitor 自带) | Android 构建 | Capacitor 自动管理 |
| **Capacitor CLI** | 最新稳定版 | 将 Web 应用桥接为原生 Android 项目 | `npm install @capacitor/cli` |
| **Java JDK** | 17+ | Android Gradle 构建需要 | https://adoptium.net/ |

---

## 三、打包后端 JAR

后端打包产物 `im-server.jar` 是一个包含所有依赖的可执行 fat jar。

### 步骤

```bash
# 1. 进入项目根目录
cd d:\daima\Lianshi\spring-boot-duomokuia

# 2. Maven 清理并打包（跳过测试）
mvn clean package -DskipTests

# 3. 产物位置
# im-bootstrap/target/im-server.jar
```

### 验证运行

```bash
# 开发环境
java -jar im-bootstrap/target/im-server.jar

# 生产环境（需要设置环境变量）
set SPRING_PROFILES_ACTIVE=prod
set MYSQL_HOST=你的数据库地址
set MYSQL_PASSWORD=你的密码
set REDIS_HOST=你的Redis地址
java -jar im-bootstrap/target/im-server.jar
```

---

## 四、打包为 Windows EXE 桌面应用

> 📘 **本项目已用 Electron 实际打包成功（产物约 90 MB）**：完整的环境准备、前端「浏览器 /
> Electron 两用」适配、打包命令、踩坑与解决（含 SSL 证书拦截绕过）、3 张 Mermaid 流程图与注意事项，
> 见 **[《Electron 打包指南（实操版）》](./Electron打包指南.md)**。
> 下面的方案 A（Electron）与方案 B（Tauri）为通用思路概览，**实际落地请以新文档为准**。

### 方案 A：Electron（前端桌面壳）+ jpackage（后端 bundled JRE）

这是最成熟的方案，分两部分打包：

#### 第一部分：Electron 前端桌面应用

##### 1. 构建前端静态文件

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\im-ui

# 构建生产版本
npm run build

# 产物在 im-ui/dist/ 目录
```

> **重要**：生产环境的 API 地址需要指向实际后端地址。  
> 修改 `im-ui/src/api/request.js` 中的 `baseURL`，或使用环境变量。

##### 2. 创建 Electron 项目

在项目根目录创建 `electron/` 文件夹：

```
d:\daima\Lianshi\spring-boot-duomokuia\
└── electron/
    ├── package.json
    ├── main.js              # Electron 主进程
    ├── preload.js            # 预加载脚本
    └── dist/                 # 从 im-ui/dist/ 复制过来
```

**`electron/package.json`**：

```json
{
  "name": "im-desktop",
  "version": "1.0.0",
  "description": "IM 即时通讯桌面客户端",
  "main": "main.js",
  "scripts": {
    "start": "electron .",
    "build": "electron-builder --win",
    "build:dir": "electron-builder --win --dir"
  },
  "build": {
    "appId": "com.im.desktop",
    "productName": "IM通讯",
    "directories": {
      "output": "release"
    },
    "win": {
      "target": [
        {
          "target": "nsis",
          "arch": ["x64"]
        }
      ],
      "icon": "dist/favicon.ico"
    },
    "nsis": {
      "oneClick": false,
      "allowToChangeInstallationDirectory": true,
      "createDesktopShortcut": true,
      "shortcutName": "IM通讯"
    },
    "files": [
      "main.js",
      "preload.js",
      "dist/**/*"
    ]
  },
  "devDependencies": {
    "electron": "^33.0.0",
    "electron-builder": "^25.0.0"
  }
}
```

**`electron/main.js`**：

```javascript
const { app, BrowserWindow, Menu } = require('electron')
const path = require('path')

// 后端服务地址（部署后修改为实际地址）
const BACKEND_URL = 'http://localhost:8080'

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'IM 即时通讯',
    icon: path.join(__dirname, 'dist', 'favicon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  // 隐藏默认菜单栏（可选）
  Menu.setApplicationMenu(null)

  // 加载前端页面
  win.loadFile(path.join(__dirname, 'dist', 'index.html'))

  // 开发时打开 DevTools
  // win.webContents.openDevTools()
}

app.whenReady().then(createWindow)

app.on('window-all-closed', () => {
  app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow()
  }
})
```

**`electron/preload.js`**：

```javascript
// 预加载脚本 - 在渲染进程可以访问 Node API 之前执行
// 保持 contextIsolation: true，不暴露任何 Node API 给前端
// 如果将来需要桌面端特有功能（如系统通知、文件选择），在这里通过
// contextBridge 暴露安全接口
```

##### 3. 修改前端 API 地址

在 `im-ui/src/api/request.js` 中，需要让 baseURL 支持 Electron 环境：

```javascript
// 检测是否在 Electron 中运行
const isElectron = navigator.userAgent.includes('Electron')

const http = axios.create({
  baseURL: isElectron ? 'http://localhost:8080/api' : '/api',
  timeout: 15000
})
```

同样，WebSocket 连接地址也需要适配（`im-ui/src/ws/socket.js`）。

##### 4. 复制前端构建产物并安装依赖

```bash
# 复制前端构建产物
xcopy /E /Y d:\daima\Lianshi\spring-boot-duomokuia\im-ui\dist\* ^
  d:\daima\Lianshi\spring-boot-duomokuia\electron\dist\

# 进入 Electron 项目
cd d:\daima\Lianshi\spring-boot-duomokuia\electron

# 安装依赖
npm install

# 测试运行
npm start
```

##### 5. 打包为 EXE 安装包

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\electron

# 打包为 Windows 安装程序（NSIS 安装包）
npm run build

# 产物在 electron/release/ 目录
# im-desktop-1.0.0-setup.exe  ← 安装程序
```

---

#### 第二部分：jpackage 打包后端为独立 EXE

使用 JDK 21 自带的 `jpackage` 工具，将 `im-server.jar` + JRE 打包为独立的 Windows 可执行文件，用户无需安装 Java。

##### 1. 生成自定义 JRE（精简体积）

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia

# 使用 jlink 生成精简 JRE（只包含项目需要的模块）
jlink ^
  --add-modules java.base,java.compiler,java.desktop,java.instrument,^
java.logging,java.management,java.naming,java.net.http,java.rmi,^
java.scripting,java.security.jgss,java.security.sasl,java.sql,^
java.sql.rowset,java.transaction.xa,java.xml,jdk.unsupported ^
  --strip-debug ^
  --no-man-pages ^
  --no-header-files ^
  --compress=zip-6 ^
  --output jre-custom
```

> 精简后的 JRE 大约 50-80 MB（完整 JRE 约 300 MB）。  
> 如果不想折腾 jlink，也可以直接用 `--runtime-image` 指向完整 JDK 目录。

##### 2. 使用 jpackage 打包

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia

jpackage ^
  --type app-image ^
  --name "IM-Server" ^
  --input im-bootstrap\target ^
  --main-jar im-server.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --runtime-image jre-custom ^
  --dest dist\server ^
  --win-console ^
  --app-version 1.0.0 ^
  --java-options "-Xms256m" ^
  --java-options "-Xmx512m" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "--spring.profiles.active=prod"
```

**参数说明**：

| 参数 | 说明 |
|------|------|
| `--type app-image` | 生成免安装目录（exe + 依赖文件），不是安装包 |
| `--name` | 应用程序名称 |
| `--input` | 包含 JAR 文件的目录 |
| `--main-jar` | 主 JAR 文件名 |
| `--main-class` | Spring Boot fat jar 的启动类（固定值） |
| `--runtime-image` | 自定义 JRE 目录（上一步 jlink 的产物） |
| `--dest` | 输出目录 |
| `--win-console` | 保留控制台窗口（后端服务需要看日志） |
| `--java-options` | 传递给 JVM 的参数 |

##### 3. 产物

```
dist\server\
├── IM-Server/
│   ├── IM-Server.exe          ← 双击即可运行
│   ├── app/
│   │   └── im-server.jar
│   └── runtime/               ← 内嵌的 JRE
│       ├── bin/
│       ├── conf/
│       └── ...
```

将 `IM-Server/` 整个文件夹与 Electron 安装包一起分发即可。

##### 4. 一键启动脚本（可选）

创建 `start.bat` 放在分发目录根：

```bat
@echo off
start /B "" "%~dp0IM-Server\IM-Server.exe"
timeout /t 5 /nobreak > nul
start "" "%~dp0im-desktop-1.0.0-setup.exe"
```

---

### 方案 B：Tauri（更轻量的替代）

Tauri 生成的 exe 只有几 MB（Electron 约 80-150 MB），但需要安装 Rust。

#### 1. 安装 Rust

```powershell
# 使用 rustup 安装
Invoke-WebRequest -Uri https://win.rustup.ms/x86_64 -OutFile rustup-init.exe
.\rustup-init.exe -y
```

#### 2. 初始化 Tauri 项目

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\im-ui

# 安装 Tauri CLI
npm install --save-dev @tauri-apps/cli

# 初始化 Tauri 配置
npx tauri init
```

按提示填写：
- App name: `im-desktop`
- Window title: `IM 即时通讯`
- Web assets relative path: `../dist`（指向 vite build 的产物）
- Dev server URL: `http://localhost:5173`
- Frontend dev command: `npm run dev`
- Frontend build command: `npm run build`

#### 3. 配置 Tauri

编辑 `im-ui/src-tauri/tauri.conf.json`：

```json
{
  "productName": "IM通讯",
  "version": "1.0.0",
  "identifier": "com.im.desktop",
  "build": {
    "frontendDist": "../dist",
    "devUrl": "http://localhost:5173"
  },
  "app": {
    "windows": [
      {
        "title": "IM 即时通讯",
        "width": 1200,
        "height": 800,
        "resizable": true,
        "minWidth": 800,
        "minHeight": 600
      }
    ]
  },
  "bundle": {
    "active": true,
    "targets": ["nsis"],
    "icon": [
      "icons/32x32.png",
      "icons/128x128.png",
      "icons/icon.ico"
    ]
  }
}
```

#### 4. 构建

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\im-ui

# 先构建前端
npm run build

# 打包为 Windows exe
npx tauri build

# 产物在 src-tauri/target/release/bundle/nsis/
```

---

## 五、打包为 Android APK

### Capacitor 方案

Capacitor 是 Ionic 出品的跨平台框架，可以将 Web 应用打包为 iOS / Android 原生应用。  
原理是用原生 WebView 壳加载 Vue 前端，通过插件调用原生能力（相机、通知等）。

#### 1. 安装 Capacitor

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\im-ui

# 安装 Capacitor 核心包和 CLI
npm install @capacitor/core @capacitor/cli

# 初始化 Capacitor
npx cap init
```

按提示填写：
- App name: `IM通讯`
- App ID: `com.im.app`（Android 包名，需唯一）
- Web asset directory: `dist`

#### 2. 添加 Android 平台

```bash
# 安装 Android 平台包
npm install @capacitor/android

# 添加 Android 平台（会生成 android/ 目录）
npx cap add android
```

#### 3. 构建前端

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\im-ui

# 构建生产版本
npm run build
```

#### 4. 修改前端 API 地址

Android WebView 中运行时没有 Nginx 代理，需要直接指向后端地址。

修改 `im-ui/src/api/request.js`：

```javascript
// 检测运行环境
const isElectron = navigator.userAgent.includes('Electron')
const isAndroid = navigator.userAgent.includes('Android')
const isNative = isElectron || isAndroid

// 生产环境后端地址（部署后替换为实际域名或 IP）
const API_BASE = isNative
  ? 'http://你的服务器IP:8080/api'
  : '/api'

const http = axios.create({
  baseURL: API_BASE,
  timeout: 15000
})
```

修改 `im-ui/src/ws/socket.js` 中的 WebSocket 地址：

```javascript
// 原生环境下直连后端 WebSocket
const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
const wsHost = isNative ? '你的服务器IP:8080' : location.host

const wsUrl = `${wsProtocol}//${wsHost}/ws?ticket=${ticket}`
```

> **提示**：建议将后端地址提取为配置文件 `src/config.js`，方便统一管理。

#### 5. 同步前端资源到 Android 项目

```bash
# 将 dist/ 内容同步到 android/app/src/main/assets/public/
npx cap sync android
```

> 每次修改前端代码后都需要执行 `npm run build` + `npx cap sync android`。

#### 6. 配置 Android 权限

编辑 `im-ui/android/app/src/main/AndroidManifest.xml`，添加网络权限：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 网络权限（必须） -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- 文件上传/下载需要 -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

    <!-- 拍照/录像需要 -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:usesCleartextTraffic="true"
        android:networkSecurityConfig="@xml/network_security_config"
        ...>
        ...
    </application>
</manifest>
```

创建 `android/app/src/main/res/xml/network_security_config.xml` 允许 HTTP 明文通信：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

> **注意**：`android:usesCleartextTraffic="true"` 允许 HTTP 请求。  
> 生产环境应使用 HTTPS，届时可移除此配置。

#### 7. 配置 Capacitor

编辑 `im-ui/capacitor.config.json`（或 `capacitor.config.ts`）：

```json
{
  "appId": "com.im.app",
  "appName": "IM通讯",
  "webDir": "dist",
  "server": {
    "androidScheme": "https"
  },
  "android": {
    "allowMixedContent": true,
    "captureInput": true,
    "webContentsDebuggingEnabled": true
  }
}
```

#### 8. 用 Android Studio 打开项目

```bash
npx cap open android
```

这会自动用 Android Studio 打开 `android/` 项目。

#### 9. 在 Android Studio 中构建 APK

##### 调试 APK（可直接安装测试）

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

产物路径：`android/app/build/outputs/apk/debug/app-debug.apk`

##### 签名发布 APK（上架用）

**第一步：生成签名密钥**

```bash
# 使用 keytool 生成签名证书（只需执行一次）
keytool -genkey -v -keystore im-release.keystore ^
  -alias im-key ^
  -keyalg RSA -keysize 2048 ^
  -validity 10000
```

> 按提示输入密码和证书信息，记住密码。

**第二步：配置 Gradle 签名**

编辑 `android/app/build.gradle`：

```groovy
android {
    // ... 其他配置

    signingConfigs {
        release {
            storeFile file('../im-release.keystore')
            storePassword '你的密钥密码'
            keyAlias 'im-key'
            keyPassword '你的密钥密码'
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
        }
    }
}
```

**第三步：构建 Release APK**

```
Build → Generate Signed Bundle / APK → APK → 选择 im-release.keystore → Next → release → Finish
```

产物路径：`android/app/build/outputs/apk/release/app-release.apk`

#### 10. 命令行构建（可选，无需打开 Android Studio）

```bash
cd d:\daima\Lianshi\spring-boot-duomokuia\im-ui\android

# 调试 APK
.\gradlew assembleDebug

# 发布 APK
.\gradlew assembleRelease

# 产物在 app\build\outputs\apk\
```

---

## 六、常见问题

### Q1: Electron 应用无法连接后端

**原因**：Electron 加载的是本地 `file://` 协议页面，没有 Nginx 代理。  
**解决**：修改 `request.js` 的 `baseURL` 为后端完整地址 `http://服务器IP:8080/api`。

### Q2: Android 网络请求失败

**原因**：Android 9+ 默认禁止 HTTP 明文通信。  
**解决**：
1. `AndroidManifest.xml` 添加 `android:usesCleartextTraffic="true"`
2. 添加 `network_security_config.xml` 允许明文
3. 生产环境建议使用 HTTPS

### Q3: Android WebSocket 连接失败

**原因**：WebSocket 地址需要直连后端，不能走代理路径。  
**解决**：检测 Android 环境，使用完整 WebSocket 地址 `ws://服务器IP:8080/ws`。

### Q4: Electron 打包后页面空白

**原因**：`vite.config.js` 中 `base` 路径不正确。  
**解决**：在 `vite.config.js` 中添加：

```javascript
export default defineConfig({
  base: './',  // 使用相对路径
  // ... 其他配置
})
```

### Q5: jpackage 打包报错 "Unsupported major.minor version"

**原因**：JAR 编译用的 JDK 版本与 jpackage 的 JRE 版本不匹配。  
**解决**：确保 jlink 生成的 JRE 版本 ≥ JAR 编译版本（都是 JDK 21）。

### Q6: Android 构建时 Gradle 下载极慢

**解决**：配置国内镜像，编辑 `android/build.gradle`：

```groovy
allprojects {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/central' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        google()
        mavenCentral()
    }
}
```

### Q7: 前端修改后 Android 没有更新

**解决**：每次修改前端代码后，必须执行：

```bash
npm run build
npx cap sync android
```

然后在 Android Studio 中重新 Build。

### Q8: Electron 应用体积过大（>150MB）

**原因**：Electron 自带 Chromium 和 Node.js。  
**解决**：
1. 使用 Tauri 替代（体积 < 10MB）
2. 使用 `electron-builder` 的 `nsis-web` 目标，共享 Electron 运行时

---

## 附录：完整打包流程速查

### EXE 打包流程

```
1. mvn clean package -DskipTests          # 编译后端 JAR
2. cd im-ui && npm run build              # 编译前端
3. 复制 im-ui/dist → electron/dist       # 前端产物放入 Electron 项目
4. cd electron && npm install             # 安装 Electron 依赖
5. npm start                              # 测试运行
6. npm run build                          # 打包为 exe 安装包
7. jpackage --type app-image ...          # 打包后端为独立 exe
```

### Android 打包流程

```
1. cd im-ui
2. npm install @capacitor/core @capacitor/cli   # 首次安装
3. npx cap init                                  # 首次初始化
4. npx cap add android                           # 首次添加平台
5. npm run build                                 # 编译前端
6. npx cap sync android                          # 同步到 Android 项目
7. npx cap open android                          # 用 Android Studio 打开
8. Build → Build APK                             # 构建 APK
```
