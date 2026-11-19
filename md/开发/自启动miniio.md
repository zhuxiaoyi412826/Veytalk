# Windows 使用 NSSM 将 MinIO 注册为系统服务（开机自启）

> 环境：Windows，MinIO 单机开发版，D:\minIO 目录；NSSM 稳定版 **2.24**（最稳定经典版本）

## NSSM 稳定版下载

> NSSM 2.24 是公认稳定版本，无新开发版的 bug，**官方直链**

- 官网页面：https://nssm.cc/download
- 稳定版 2.24 压缩包直链：`https://nssm.cc/release/nssm-2.24.zip`

> 解压后目录结构： `nssm-2.24\nssm-2.24\win64\nssm.exe`（64 位 Windows 用这个） `nssm-2.24\nssm-2.24\win32\nssm.exe`（32 位） ✅ 无需安装，直接拿 exe 使用

## 目录规划

表格

| 路径                                          | 说明                                   |
| --------------------------------------------- | -------------------------------------- |
| `D:\minIO\minio.exe`                          | MinIO 主程序                           |
| `D:\minIO\data`                               | 对象存储数据目录                       |
| `D:\minIO\logs`                               | MinIO 运行日志目录（**必须手动新建**） |
| `D:\minIO\nssm-2.24\nssm-2.24\win64\nssm.exe` | NSSM 工具                              |

## 前置验证（必须先做！）

前台直接启动 MinIO，确认程序本身可以正常运行，排除参数、账号密码、目录问题。 管理员 PowerShell 执行：

```
$env:MINIO_ROOT_USER="minioadmin"
$env:MINIO_ROOT_PASSWORD="minioadmin"
D:\minIO\minio.exe server D:\minIO\data --console-address ":9001"
```

正常输出 API、WebUI 地址，浏览器访问 `http://127.0.0.1:9001` 可登录。

> API 端口：9000（代码对接）；Web 控制台端口：9001（浏览器后台）

------

# 方式一：图形界面 GUI 配置（新手推荐，可视化）

1. **管理员 PowerShell**，进入 nssm win64 目录

```
cd D:\minIO\nssm-2.24\nssm-2.24\win64
```

1. 打开 GUI 安装窗口

```
.\nssm.exe install MinIO
```

弹出 NSSM 图形配置窗口，逐项填写：

### Application 选项卡

- Path：`D:\minIO\minio.exe`
- Working directory（工作目录）：`D:\minIO`
- Arguments：

```
server D:\minIO\data --console-address ":9001"
```

### Details 选项卡

- Display name：`MinIO Object Storage`
- Description：`MinIO 对象存储服务`
- Startup type：`Automatic`（开机自动启动）

### Environment 选项卡

点 Add 添加两行环境变量（内置注入，不依赖系统环境变量）

```
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin
```

### Log on 选项卡（解决 D 盘权限，重点！）

选择 `This account`

- User name：`.\zhuxiaoyi`
- Password：你的 Windows 开机密码

> ❗不要使用默认 LocalSystem 账户，LocalSystem 访问 D 盘容易权限不足闪退

### I/O 选项卡（日志）

> 提前手动建好 `D:\minIO\logs`

- Output (stdout): `D:\minIO\logs\stdout.log`
- Error (stderr): `D:\minIO\logs\stderr.log` 勾选 `Rotate output files` 日志自动轮转

### Recovery 选项卡（崩溃自动重启）

First failure / Second failure / Subsequent failures：全部选择 **Restart the service** Restart service after：`5000`（5 秒后自动重启）

点击 OK 保存，服务注册完成。

### GUI 方式启停命令

```
#启动
.\nssm start MinIO
#查看状态
.\nssm status MinIO
#编辑GUI界面（后续修改配置用）
.\nssm edit MinIO
#删除服务
.\nssm remove MinIO confirm
```

------

# 方式二：命令行配置（适合脚本自动化，无图形界面）

管理员 PowerShell 执行，进入 nssm 目录

```
cd D:\minIO\nssm-2.24\nssm-2.24\win64
```

## 1. 创建服务

```
.\nssm.exe install MinIO
```

## 2. 基础程序配置

```
# 设置exe路径
.\nssm set MinIO Application D:\minIO\minio.exe
# 设置工作目录
.\nssm set MinIO AppDirectory D:\minIO
# 启动参数
.\nssm set MinIO AppParameters server D:\minIO\data --console-address ":9001"
# 设置开机自动启动
.\nssm set MinIO Start SERVICE_AUTO_START
# 服务显示名称和描述
.\nssm set MinIO DisplayName "MinIO Object Storage"
.\nssm set MinIO Description "MinIO 对象存储服务"
```

## 3. 内置环境变量

```
.\nssm set MinIO AppEnvironment MINIO_ROOT_USER=minioadmin
.\nssm set MinIO AppEnvironment2 MINIO_ROOT_PASSWORD=minioadmin
```

## 4. 设置运行账号（权限核心）

```
.\nssm set MinIO ObjectName .\zhuxiaoyi
```

执行后弹窗输入 Windows 开机密码。

## 5. 日志配置

```
.\nssm set MinIO AppStdout D:\minIO\logs\stdout.log
.\nssm set MinIO AppStderr D:\minIO\logs\stderr.log
.\nssm set MinIO AppRotateFiles 1
.\nssm set MinIO AppRotateOnline 1
```

## 6. 崩溃自动重启策略

```
.\nssm set MinIO ServiceFailureReset 86400
.\nssm set MinIO FailureAction 0 RESTART 5000
.\nssm set MinIO FailureAction2 RESTART 5000
.\nssm set MinIO FailureAction3 RESTART 5000
```

## 7. 服务管理命令

```
#启动服务
.\nssm start MinIO
#查看运行状态
.\nssm status MinIO
#停止服务
.\nssm stop MinIO
#重启服务
.\nssm restart MinIO
#导出全部配置用于核对
.\nssm dump MinIO
#删除服务（卸载）
.\nssm remove MinIO confirm
#查看错误日志（启动失败排查）
Get-Content D:\minIO\logs\stderr.log
```

## 局域网访问：防火墙放行端口

管理员 PowerShell 执行，放行 9000、9001 端口

```
New-NetFirewallRule -DisplayName "MinIO API 9000" -Direction Inbound -LocalPort 9000 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "MinIO Console 9001" -Direction Inbound -LocalPort 9001 -Protocol TCP -Action Allow
```

------

# 🚨 踩坑记录（重点）

1. ❌ **直接双击 minio.exe**

> MinIO 不能双击运行，双击只会弹出提示窗口，一闪退出。**必须命令行启动**。

1. ❌ 前台能跑，注册成 Windows 服务启动直接闪退

> 最常见原因：默认`LocalSystem`系统账户**没有 D 盘读写权限**。 ✅ 解决方案：NSSM 配置`ObjectName`，指定你自己的 Windows 账号（zhuxiaoyi）。

1. ❌ 服务读取不到 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD

> Windows 系统服务环境读取系统环境变量经常失效。 ✅ 解决方案：**直接在 NSSM 内置 AppEnvironment 注入账号密码，不要依赖全局系统环境变量**。

1. ❌ 忘记设置 `AppDirectory`（工作目录）

> 工作目录为空时，服务运行上下文异常，程序直接退出。 ✅ 必须设置 `AppDirectory D:\minIO`。

1. ❌ 日志目录 `D:\minIO\logs` 没有提前手动创建

> NSSM 不会自动新建日志文件夹，文件夹不存在直接启动失败。

1. ❌ MinIO Root 密码小于 8 位

> MinIO 强制要求 ROOT 密码最少 8 个字符，`minioadmin`刚好 8 位。

1. ❌ 用 PowerShell 自带 `Start-Service MinIO` 报错提示笼统

> PowerShell 自带服务命令报错信息简短，看不出真实原因。 ✅ 推荐使用 `nssm start MinIO` 和查看 `stderr.log` 日志排查。

1. ❌ 生产环境使用 Windows 版 MinIO

> MinIO 官方不推荐 Windows 作为生产服务器，Windows 版本仅用于本地开发调试。

1. ❌ 局域网访问不通，忘记放行防火墙端口 9000、9001

> Windows 防火墙默认拦截外部入站请求，需要手动创建防火墙放行规则。

## 七、快速排错清单（启动失败按顺序逐项检查）

> 原则：**先前台手动运行确认程序正常，再排查服务配置；优先看 stderr.log 错误日志**

### 第 1 步：前置校验（90% 问题卡在这）

1. 管理员 PowerShell 手动前台启动 MinIO，确认能正常运行：

```
$env:MINIO_ROOT_USER="minioadmin"
$env:MINIO_ROOT_PASSWORD="minioadmin"
D:\minIO\minio.exe server D:\minIO\data --console-address ":9001"
```

- 前台能正常启动 → 问题只在 NSSM 服务配置；
- 前台直接报错 → 先解决程序本身（目录、权限、账号密码、端口）。

### 第 2 步：检查目录与文件（服务启动闪退头号诱因）

1. 确认文件夹全部存在：`D:\minIO\data`、`D:\minIO\logs`；**NSSM 不会自动创建文件夹**。
2. 右键文件夹 → 属性 → 安全：当前 Windows 账号（zhuxiaoyi）拥有**读写修改权限**。
3. 确认`minio.exe`未被杀毒软件隔离、阻止。

### 第 3 步：核对 NSSM 服务配置（逐条核对）

执行命令导出全部配置，对照检查：

```
.\nssm dump MinIO
```

必查项：

1. Application：`D:\minIO\minio.exe`（路径不能有空格、中文）
2. AppDirectory：`D:\minIO`（工作目录不能为空）
3. AppParameters：`server D:\minIO\data --console-address ":9001"`（参数不能少）
4. AppEnvironment、AppEnvironment2：账号密码已写入，不要依赖系统环境变量。
5. ObjectName：已指定`.\zhuxiaoyi`，不要使用默认 LocalSystem（D 盘权限不足）。

### 第 4 步：账号密码校验（MinIO 强制规则）

1. 用户名 + 密码长度：**密码必须≥8 位**，`minioadmin`刚好 8 位；少于 8 位直接启动失败。
2. 环境变量不能有空格，格式严格为`KEY=VALUE`。

### 第 5 步：端口占用排查

管理员 PowerShell 执行，查看 9000/9001 是否被占用：

```
netstat -ano | findstr "9000"
netstat -ano | findstr "9001"
```

- 端口被占用：杀掉占用进程，或者修改 MinIO 控制台端口。

### 第 6 步：读取错误日志（最核心定位手段）

```
Get-Content D:\minIO\logs\stderr.log
```

常见日志关键字对应问题：

1. `permission denied` → 目录权限不足，更换运行账户并给文件夹授权。
2. `invalid credentials` → 账号密码位数不足或配置写错。
3. `address already in use` → 端口被占用。
4. 无任何日志输出 → 日志文件夹未创建，或 NSSM 日志路径配置错误。

### 第 7 步：进程残留清理

1. 打开任务管理器，结束所有残留的`minio.exe`进程。
2. 重启服务前先执行：`.\nssm stop MinIO`，避免端口被旧进程占用。

### 第 8 步：服务启动权限兜底测试

临时把服务登录账户改为**本地系统账户**测试：

- 改完能正常启动 → 就是自定义账户（zhuxiaoyi）缺少文件夹读写权限；
- 依然闪退 → 回到前面检查参数、环境变量、目录。

### 第 9 步：终极重置方案（所有配置都排查仍失败）

1. 卸载旧服务：`.\nssm remove MinIO confirm`
2. 重新执行整套 NSSM 配置命令，避免旧配置残留冲突。
3. 重启电脑，清空端口占用，再启动服务。

### 第 10 步：局域网访问不通排查

1. 先本机浏览器打开 `http://127.0.0.1:9001`，本机能打开再排查外网。
2. 检查 Windows 防火墙入站规则，确认 9000、9001 端口已放行。
3. 关闭第三方安全软件临时测试拦截问题。

## 八、常用一键排查命令（直接复制执行）

```
# 1.查看服务完整配置
.\nssm dump MinIO
# 2.查看错误日志
Get-Content D:\minIO\logs\stderr.log
# 3.检测端口占用
netstat -ano | findstr "9000"
netstat -ano | findstr "9001"
# 4.重启服务并查看状态
.\nssm restart MinIO; .\nssm status MinIO
```

## 访问地址

- Web 管理后台：`http://127.0.0.1:9001`
- API 接口地址（代码 / MC 客户端连接）：`http://127.0.0.1:9000`
- 账号：`minioadmin`，密码：`minioadmin`