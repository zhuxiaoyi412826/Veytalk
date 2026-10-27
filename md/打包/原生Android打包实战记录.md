# 原生安卓（im-android）打包实战记录（2026-10-27 实测，可复刻版）

> 本文是 **im-android 原生工程** 从环境到出包的逐步复刻手册，与
> [原生安卓im-android开发方案.md](./原生安卓im-android开发方案.md)（设计篇）配套。
> 该工程与壳版（`im-ui/android/`，Capacitor）**完全独立**：不进 Maven 多模块、不动 im-ui，
> 所有配置均在 `im-android/` 目录内闭环。
>
> 原生版与壳版最大的打包差异：**后端地址不再是编译期注入**。
> 壳版改服务器要改 `.env.capacitor` 重新打包；原生版在登录页/「我的」页运行时改，
> 存 DataStore 立即生效，**换网段/热点免重打包**。

## 最终产物

| 产物 | 完整路径 | 大小（M3 完成时实测） |
|---|---|---|
| 安卓调试 APK | `c:\daima\Veytalk\im-android\app\build\outputs\apk\debug\app-debug.apk` | **107.7 MB** |

- 包名 `com.im.nativeapp`（壳版是 `com.im.app`，**可同机共存**对比测试）
- versionCode 1 / versionName 0.1.0
- debug 签名直接装机自用；正式分发走第七节 Release 签名

---

## 一、实测环境（版本逐个对过）

| 软件 | 实测版本 | 说明 |
|---|---|---|
| JDK | **21.0.6 LTS**（`java -version` 验证） | AGP 8.x 要求 JDK 17+，21 实测可用 |
| Android SDK Platform | **android-36** | compileSdk = 36 必须装对应平台 |
| Android SDK Build-Tools | 36.0.0（35.0.0 也在，不影响） | SDK Manager → SDK Tools 页勾选 |
| Gradle | **8.14.3**（wrapper 自动下载，无需手装） | wrapper 走腾讯镜像，见 2.2 |
| AGP | 8.11.1 | 写在 `im-android/build.gradle.kts` |
| Kotlin | 2.1.21（KSP 2.1.21-2.0.2，版本必须精确匹配） | 同上 |
| Hilt | 2.56.2 | 依赖注入 |
| Compose BOM | 2025.05.01 | UI 全部 Compose 原生重写 |

关键 SDK 级别：**minSdk 24（Android 7.0）/ targetSdk 36 / compileSdk 36**。

---

## 二、一次性准备

### 2.1 新建 `im-android/local.properties`（机器专属，勿提交 git）

```properties
sdk.dir=C:/Users/zhuxiaoyi/AppData/Local/Android/Sdk
```

> 路径改成自己机器的 SDK 位置（Android Studio → SDK Manager 顶部可见）。
> 注意用正斜杠 `/` 或双反斜杠 `\\`，单反斜杠会被转义吃掉。

### 2.2 镜像配置（已在仓库内，随 git 走，换机不用重做）

**Gradle wrapper**（`im-android/gradle/wrapper/gradle-wrapper.properties`）：

```properties
# 腾讯镜像：官方 services.gradle.org 直连下载在 80% 左右必超时（壳版已踩过）
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-all.zip
networkTimeout=120000
```

**Maven 仓库**（`im-android/settings.gradle.kts`，阿里云镜像在前、官方源兜底）：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google(); mavenCentral()
    }
}
```

**Gradle 参数**（`im-android/gradle.properties`）：

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

---

## 三、日常构建（就两条命令）

```powershell
cd c:\daima\Veytalk\im-android
.\gradlew.bat assembleDebug
```

- 产物：`app\build\outputs\apk\debug\app-debug.apk`
- 增量构建实测约 40 秒；首次全量要下依赖，时间取决于镜像网速
- 工程根目录有 `构建命令.txt`，内容与上面一致，忘了直接打开看

装到手机（手机开 USB 调试并连电脑，adb 在 `SDK\platform-tools` 里）：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

或者直接拷 APK 到手机点开安装（debug 签名，系统不拦）。

---

## 四、APK 体积构成（107.7 MB 实测拆解）与瘦身方案

### 4.1 体积去哪了

| 构成 | 体积 | 来源 |
|---|---|---|
| `lib/` 原生库 | **80.5 MB** | WebRTC + ML Kit，各打了 **4 个 CPU 架构** |
| `classes*.dex` | ~73 MB | debug 包未开 R8 压缩混淆，全部类原样打进 |
| `assets/` | 2.4 MB | ML Kit 中文 OCR 模型（bundled 变体内置） |
| 资源/清单 | ~1 MB | — |

`lib/` 里两个大户，各占约一半：

```text
libjingle_peerconnection_so.so  ×4 架构 ≈ 41 MB   ← WebRTC（1:1 音视频通话）
libmlkit_google_ocr_pipeline.so ×4 架构 ≈ 39 MB   ← ML Kit 中文 OCR
```

按架构拆：**x86_64 23.3 MB + x86 22.8 MB + arm64-v7a 21.6 MB + armeabi-v7a 12.7 MB**。
x86/x86_64 合计 **46 MB 只服务模拟器**，真机全是 ARM，纯浪费。

### 4.2 瘦身手段（按性价比排序）

**① 砍掉 x86 架构（预计 -46 MB，降到 ~60 MB）**

`im-android/app/build.gradle.kts` 的 `defaultConfig { }` 里加：

```kotlin
ndk {
    // 真机全是 ARM；x86/x86_64 仅模拟器用，局域网分发场景纯浪费
    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
}
```

代价：x86 模拟器跑不了（用 ARM 镜像模拟器或真机替代），真机无任何影响。

**② Release 开压缩混淆（dex 预计再砍 30~50%）**

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

⚠️ 未实测。开启后需为 kotlinx-serialization 的 `@Serializable` 类补 keep 规则
（Hilt/Retrofit/Compose 的规则各库自带 consumer-proguard 已覆盖），
开完必须全功能回归一遍再发。**目前 release 块是 `isMinifyEnabled = false`**。

**③ OCR 模型改 Play 动态下发（再省 ~20 MB）——本场景不适用**

`text-recognition-chinese`（bundled）模型打进 APK；换
`play-services-mlkit-text-recognition-chinese` 可由 Play 商店按需下发。
但本项目是局域网直接发 APK 安装，用户机器没有 Play 商店，**维持 bundled 不变**。

---

## 五、后端地址：运行时配置（原生版独有）

| 对比项 | 壳版（Capacitor） | 原生版（im-android） |
|---|---|---|
| 配置时机 | 编译期注入 `.env.capacitor` | **运行时**「我的 → 修改服务器地址」 |
| 默认值 | 打包时固定 | `http://192.168.1.2:8080` |
| 改地址 | 改文件重新打包 | 输入即存 DataStore，重建网络层立即生效 |
| 流媒体地址 | 同改 | 自动跟随：同 host、端口换 8088 |

打包时**不需要**为后端地址做任何配置，出厂默认值不对用户进 App 自己改。

---

## 六、已踩坑记录（构建期/运行期各半）

1. **wrapper 官方源必超时** → 腾讯镜像 + `networkTimeout=120000`（2.2 已配，壳版踩过的坑原生版直接绕开）。
2. **minSdk 24 没有 `java.time`** → `compileOptions` 里开 `isCoreLibraryDesugaringEnabled = true`
   并加 `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")`，消息时间格式化直接可用。
3. **API 28+ 默认拦截明文 HTTP** → 局域网后端是 `http://192.168.x.x:8080`，
   Manifest 必须 `android:usesCleartextTraffic="true"`，否则登录直接网络错误。
4. **API 34+ 前台服务强制声明类型** → 直播开播推流用前台服务防杀，
   Manifest 需同时声明权限 `FOREGROUND_SERVICE_CAMERA / FOREGROUND_SERVICE_MICROPHONE`
   和 `<service android:foregroundServiceType="camera|microphone">`，缺一在 targetSdk 36 下
   `startForeground()` 直接抛异常。已配好，别删。
5. **KSP 版本与 Kotlin 精确绑定** → Kotlin 2.1.21 对应 KSP `2.1.21-2.0.2`，
   升 Kotlin 必须同步升 KSP 前缀，否则 `kspDebugKotlin` 任务报错。
6. **构建期间别动 `app/build/` 目录** → 与 Tauri「运行中的 app.exe 锁构建目录」同理，
   资源管理器/压缩软件占着 `build` 目录会让 `assembleDebug` 报「拒绝访问」。
7. **WebRTC 用预编译包** → `io.getstream:stream-webrtc-android:1.3.8`，
   不要试图自己编 libwebrtc（体积大头就是它，但自研编译成本远超收益）。

---

## 七、Release 正式打包（签名分发，待首次实测）

debug 包只适合自用测试。长期装机/发别人用：

```powershell
# 1. 生成 keystore（只做一次。文件和密码丢了 = 永远无法更新已发布的 App）
cd c:\daima\Veytalk\im-android
keytool -genkey -v -keystore im-release.keystore -alias im -keyalg RSA -keysize 2048 -validity 36500
```

2. `im-android/` 下新建 `keystore.properties`（**务必加 .gitignore**）：

```properties
storeFile=im-release.keystore
storePassword=你的密码
keyAlias=im
keyPassword=你的密码
```

3. `app/build.gradle.kts` 配置签名（完整可复制块）：

```kotlin
// 文件顶部 import 区
import java.util.Properties
import java.io.FileInputStream

// plugins { } 之后、android { } 之前
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    // ...原有内容不动...
    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = keystoreProperties["storeFile"]?.let { rootProject.file(it as String) }
            storePassword = keystoreProperties["storePassword"] as String
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false   // 要开混淆见 4.2-②，先保证能出包
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

4. 出包：

```powershell
cd c:\daima\Veytalk\im-android
.\gradlew.bat assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`

**release 前自查**：keystore 已备份（丢了不能更新已装 App）、abiFilters 已配（4.2-①）、
versionCode/versionName 已递增、真机全功能回归过一遍。

---

## 八、换电脑复刻路径

1. 装 JDK 17+（实测 21）+ Android Studio（SDK Manager 装 **Platform android-36** + Build-Tools）
2. clone 仓库后进 `im-android/`，新建 `local.properties` 写 `sdk.dir`（2.1）
3. `.\gradlew.bat assembleDebug`

wrapper、镜像、Manifest、依赖版本全部在仓库里随 git 走，不用重做。

## 九、文件清单

| 文件 | 作用 | 进 git |
|---|---|---|
| `im-android/build.gradle.kts` / `settings.gradle.kts` | 插件版本 / 镜像仓库 | ✅ |
| `im-android/app/build.gradle.kts` | 应用配置与全部依赖 | ✅ |
| `im-android/gradle.properties` / `gradle/wrapper/` | 构建参数 / wrapper 镜像 | ✅ |
| `im-android/app/src/main/` | 全部源码与 Manifest | ✅ |
| `im-android/local.properties` | SDK 路径 | ❌ 机器专属 |
| `im-android/keystore.properties` / `*.keystore` | 正式签名 | ❌ 含密码，泄露=被冒签 |
| `im-android/构建命令.txt` | 构建命令备忘 | 随意 |
