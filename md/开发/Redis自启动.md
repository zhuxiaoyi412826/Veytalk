# Redis Windows 免安装包 稳定部署文档（开机自启，两种方式：Redis 原生命令 + NSSM 图形 / 命令）

> 说明：微软归档版 Redis Windows（3.2.100）自带原生服务注册；新版 Redis Windows 分支推荐用 **NSSM** 注册为系统服务（崩溃自动重启，和 MinIO 保持统一工具） 默认端口：6379；服务名：Redis

## 目录规划（自行替换成你的实际路径）

表格

| 路径                          | 说明                 |
| ----------------------------- | -------------------- |
| `D:\redis\redis-server.exe`   | Redis 主程序         |
| `D:\redis\redis.windows.conf` | Redis 主配置文件     |
| `D:\redis\logs`               | 日志目录（手动新建） |
| `D:\redis\dump.rdb`           | 持久化文件，自动生成 |

## 前置准备

1. 修改 `redis.windows.conf` 常用配置

```
port 6379
# 开启密码（建议）
requirepass 123456
# 后台运行（服务模式）
daemonize no
# RDB持久化
save 900 1
save 300 10
save 60 10000
```

1. 管理员 PowerShell 操作，所有注册 / 启停服务**必须管理员终端**

------

# 方式一：Redis 原生自带命令（简单，优先推荐，3.2.100 微软版）

> 原生注册，直接自带开机自动启动

### 1. 进入 redis 目录

```
cd D:\redis
```

### 2. 注册 Windows 系统服务（开机自启）

```
.\redis-server.exe --service-install redis.windows.conf --loglevel verbose --service-name Redis
```

> 成功提示：`Redis service installed successfully`

### 3. 启停服务

```
#启动
.\redis-server.exe --service-start
#停止
.\redis-server.exe --service-stop
#卸载服务
.\redis-server.exe --service-uninstall
```

### 4. 查看服务状态与启动类型

```
Get-Service Redis | Select Name,Status,StartType
```

- `StartType: Automatic` → ✅ 开机自动

### 5. 登录测试

```
.\redis-cli
# 有密码则认证
AUTH 123456
```

### 6. 端口验证

```
netstat -ano | findstr "6379"
```

------

# 方式二：NSSM 注册 Redis 服务（和 MinIO 同一套工具，推荐新版 Redis，崩溃自动重启）

> NSSM 稳定版 2.24，下载地址同 MinIO：`https://nssm.cc/release/nssm-2.24.zip`

## 方式 2.1 图形 GUI 配置（新手可视化）

管理员 PowerShell，进入 nssm win64 目录

```
cd D:\minio\nssm-2.24\nssm-2.24\win64
.\nssm.exe install Redis
```

弹出 NSSM 窗口

1. **Application**

- Path：`D:\redis\redis-server.exe`
- Startup directory：`D:\redis`
- Arguments：`redis.windows.conf`

1. **Details**

- Display name：Redis Server
- Description：Redis 缓存服务
- Startup type：Automatic（开机自启）

1. **I/O**

- Output：`D:\redis\logs\stdout.log`
- Error：`D:\redis\logs\stderr.log`

> 提前手动新建 `D:\redis\logs`

1. **Recovery（重点：崩溃自动重启）** 第一、二、三次失败：Restart the service；重启延迟 5000 毫秒 点 OK 保存。

GUI 启停命令

```
.\nssm start Redis
.\nssm stop Redis
.\nssm edit Redis
.\nssm remove Redis confirm
```

## 方式 2.2 NSSM 命令行脚本方式（自动化）

管理员 PowerShell，进入 nssm 目录

```
cd D:\minio\nssm-2.24\nssm-2.24\win64
# 创建服务
.\nssm install Redis D:\redis\redis-server.exe redis.windows.conf
# 基础配置
.\nssm set Redis AppDirectory D:\redis
.\nssm set Redis DisplayName "Redis Server"
.\nssm set Redis Description "Redis缓存服务"
# 设置开机自动启动
.\nssm set Redis Start SERVICE_AUTO_START
# 日志
.\nssm set Redis AppStdout D:\redis\logs\stdout.log
.\nssm set Redis AppStderr D:\redis\logs\stderr.log
.\nssm set Redis AppRotateFiles 1
.\nssm set Redis AppRotateOnline 1
# 崩溃自动重启策略
.\nssm set Redis ServiceFailureReset 86400
.\nssm set Redis FailureAction 0 RESTART 5000
.\nssm set Redis FailureAction 1 RESTART 5000
.\nssm set Redis FailureAction 2 RESTART 5000
```

启动服务

```
.\nssm start Redis
```

## 局域网访问（可选）

1. 修改 `redis.windows.conf`

```
# 注释掉 bind 127.0.0.1
# bind 127.0.0.1
protected-mode no
requirepass 123456
```

1. 管理员 PowerShell 放行防火墙 6379 端口

```
New-NetFirewallRule -DisplayName "Redis 6379" -Direction Inbound -LocalPort 6379 -Protocol TCP -Action Allow
```

------

# 🚨 Redis 踩坑记录

1. ❌ 直接双击 `redis-server.exe`，关闭窗口服务停止

> 双击只是前台临时运行，**不会注册系统服务，无法开机自启**。必须用原生命令 / NSSM 注册系统服务。

1. ❌ Install/Remove of the Service Denied! 系统错误 5 拒绝访问

> PowerShell 不是管理员身份，注册、启停服务全部需要管理员终端。

1. ❌ 服务注册成功，启动后一闪停止

> ① redis.windows.conf 配置语法错误；② bind/protected-mode 配置冲突；③ 6379 端口被占用。 ✅ 前台直接运行看报错：`.\redis-server.exe redis.windows.conf`

1. ❌ redis-cli 无法识别命令

> redis 目录未加入系统 PATH 环境变量；进入 redis 目录执行 `.\redis-cli` 或者添加系统 Path。

1. ❌ 局域网其他电脑连不上 Redis

> ① bind 绑定 127.0.0.1；② protected-mode yes；③ Windows 防火墙 6379 未放行；④ 没有设置 requirepass 密码。

1. ❌ NSSM 日志不生成

> `D:\redis\logs` 文件夹**必须手动预先创建，NSSM 不会自动新建目录**。

## 快速排错清单（按顺序检查）

1. 确认 PowerShell 为**管理员身份**；
2. 查看服务状态：`Get-Service Redis | Select Name,Status,StartType`，状态 Running 才正常；
3. 端口检测：`netstat -ano | findstr "6379"`；无输出代表服务未正常启动；
4. 前台直接启动查看实时报错：`.\redis-server.exe redis.windows.conf`；
5. NSSM 方式查看日志：`D:\redis\logs\stderr.log`；
6. 检查 redis.windows.conf 语法，端口不冲突；
7. 局域网访问：本机先 127.0.0.1 连通，再检查 bind、protected-mode、防火墙。

## 一键重置全套命令（服务异常重建）

```
#原生版本卸载
.\redis-server.exe --service-uninstall
#NSSM版本卸载
#.\nssm remove Redis confirm

cd D:\redis
#重新注册（原生方式）
.\redis-server.exe --service-install redis.windows.conf --loglevel verbose --service-name Redis
#启动
.\redis-server.exe --service-start
```

> 可以保存为 `Redis-Windows部署.md`，后续合并到整套环境文档。