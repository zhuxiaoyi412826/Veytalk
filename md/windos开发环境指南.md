# indows 开发环境部署.md

> 包含：MinIO 对象存储 + MySQL8.4.10 + Redis5.0.14.1 统一说明：**所有注册 / 删除 / 启停系统服务操作，必须使用【管理员 PowerShell】**

## 目录

1. [MinIO 对象存储部署（NSSM 注册系统服务，开机自启）](#1-minio-对象存储部署nssm注册系统服务开机自启)
2. [MySQL 8.4.10 WinX64 ZIP 包部署（原生服务，开机自启）](#2-mysql-8410-winx64-zip包部署原生服务开机自启)
3. [Redis 5.0.14.1 Windows 部署（原生命令注册服务，开机自启）](#3-redis-50141-windows部署原生命令注册服务开机自启)
4. [统一常用排错速查表](#4-统一常用排错速查表)

------

# 1. MinIO 对象存储部署（NSSM 注册系统服务，开机自启）

> NSSM 版本：2.24；下载地址：`https://nssm.cc/release/nssm-2.24.zip`

## 1.1 目录规划

表格

| 路径                 | 说明               |
| -------------------- | ------------------ |
| `D:\minio\minio.exe` | minio 主程序       |
| `D:\minio\data`      | 对象存储数据目录   |
| `D:\minio\logs`      | 日志目录，手动新建 |

## 1.2 NSSM 两种部署方式

### 方式 1：NSSM GUI 图形界面（推荐，可视化配置）

管理员 PowerShell 进入 nssm win64 目录

```
cd D:\minio\nssm-2.24\nssm-2.24\win64
.\nssm.exe install MinIO
```

弹出 NSSM 配置窗口：

1. Application

- Path：`D:\minio\minio.exe`
- Startup directory：`D:\minio`
- Arguments：`server D:\minio\data --console --address 0.0.0.0:9000 --console-address 0.0.0.0:9001`

1. Details

- Display name：MinIO
- Description：MinIO 对象存储服务
- Startup type：Automatic（开机自启）

1. I/O

- Output：`D:\minio\logs\stdout.log`
- Error：`D:\minio\logs\stderr.log`

> 提前手动新建`D:\minio\logs`，NSSM 不会自动创建文件夹

1. Recovery（崩溃自动重启） 第 1/2/3 次失败：Restart the service；重启延迟 5000ms

保存后，启停命令

```
.\nssm start MinIO
.\nssm stop MinIO
.\nssm edit MinIO
.\nssm remove MinIO confirm
```

### 方式 2：NSSM 命令行脚本方式（自动化）

```
cd D:\minio\nssm-2.24\nssm-2.24\win64
# 创建服务
.\nssm install MinIO D:\minio\minio.exe server D:\minio\data --console --address 0.0.0.0:9000 --console-address 0.0.0.0:9001
# 基础信息
.\nssm set MinIO AppDirectory D:\minio
.\nssm set MinIO DisplayName "MinIO"
.\nssm set MinIO Description "MinIO对象存储服务"
# 设置开机自启
.\nssm set MinIO Start SERVICE_AUTO_START
# 日志配置
.\nssm set MinIO AppStdout D:\minio\logs\stdout.log
.\nssm set MinIO AppStderr D:\minio\logs\stderr.log
.\nssm set MinIO AppRotateFiles 1
.\nssm set MinIO AppRotateOnline 1
# 崩溃重启策略
.\nssm set MinIO ServiceFailureReset 86400
.\nssm set MinIO FailureAction 0 RESTART 5000
.\nssm set MinIO FailureAction 1 RESTART 5000
.\nssm set MinIO FailureAction 2 RESTART 5000
# 启动服务
.\nssm start MinIO
```

## 1.3 验证

```
# 查看服务状态
Get-Service MinIO | Select Name,Status,StartType
# 端口检测
netstat -ano | findstr "9000"
```

浏览器访问：`http://127.0.0.1:9000`

## 1.4 防火墙开放端口（局域网访问）

```
New-NetFirewallRule -DisplayName "MinIO 9000" -Direction Inbound -LocalPort 9000 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "MinIO 9001" -Direction Inbound -LocalPort 9001 -Protocol TCP -Action Allow
```

## MinIO 踩坑清单

1. ❌ `Install/Remove of the Service Denied!`：非管理员 PowerShell。
2. ❌ 注册成功，启动一闪停止：前台直接运行`minio.exe server D:\minio\data`看报错；检查目录读写权限。
3. ❌ NSSM 日志不生成：`logs`文件夹需要手动预先创建。
4. ❌ 其他电脑无法访问：防火墙未放行 9000/9001，监听地址不是 0.0.0.0。

## MinIO 快速排错清单

1. 终端为管理员身份
2. `Get-Service MinIO`查看服务状态，Running 为正常
3. 端口检测：`netstat -ano | findstr "9000"`
4. 前台直接启动抓取实时错误
5. 查看日志：`D:\minio\logs\stderr.log`

------

# 2. MySQL 8.4.10 WinX64 ZIP 包部署（原生服务，开机自启）

> 路径：`D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64` 服务名：MySQL84，端口 3306

## 2.1 目录规划

表格

| 路径                                                         | 说明                              |
| ------------------------------------------------------------ | --------------------------------- |
| `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\bin` | mysql 可执行文件目录              |
| `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\my.ini` | 主配置文件（根目录，与 bin 同级） |
| `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\data` | 数据目录，初始化自动生成          |

## 2.2 my.ini 配置

```
[mysqld]
basedir = D:/mysql/mysql/mysql-8.4.10-winx64/mysql-8.4.10-winx64
datadir = D:/mysql/mysql/mysql-8.4.10-winx64/mysql-8.4.10-winx64/data
port = 3306
character-set-server=utf8mb4
default-storage-engine=INNODB

[mysql]
default-character-set=utf8mb4
```

> ⚠️ 路径必须用`/`正斜杠，**不能用`\`反斜杠**；不要中文、多余空格。

## 2.3 部署步骤（管理员 PowerShell）

```
# 进入bin目录
cd D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\bin

# 初始化数据库（执行前删除已存在的data文件夹！生成临时root密码）
.\mysqld --initialize --console
```

> 保存控制台输出临时密码：`A temporary password is generated for root@localhost: xxxxxx`

```
# 注册Windows系统服务
.\mysqld --install MySQL84 --defaults-file="D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\my.ini"

# 设置开机自启
sc config MySQL84 start= auto

# 启动服务
net start MySQL84
```

## 2.4 登录 MySQL & 修改 root 密码

二选一：

1. bin 目录执行：`.\mysql -uroot -p`
2. 将`bin`路径加入系统 PATH，新开终端直接 `mysql -uroot -p`

登录后执行 SQL 修改密码

```
ALTER USER 'root'@'localhost' IDENTIFIED BY '你的新密码';
FLUSH PRIVILEGES;
```

## 2.5 局域网访问（可选）

```
CREATE USER 'root'@'%' IDENTIFIED BY '你的密码';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
```

管理员 PowerShell 放行防火墙端口

```
New-NetFirewallRule -DisplayName "MySQL 3306" -Direction Inbound -LocalPort 3306 -Protocol TCP -Action Allow
```

## MySQL 常用服务命令

```
# 查看状态+启动类型
Get-Service MySQL84 | Select Name,Status,StartType
# 停止
net stop MySQL84
# 删除服务
.\mysqld --remove MySQL84
# 端口检测
netstat -ano | findstr "3306"
```

## MySQL 踩坑清单

1. ❌ `Install/Remove of the Service Denied!`：非管理员终端。
2. ❌ `系统错误5。拒绝访问。`：非管理员执行 net start/net stop。
3. ❌ `ERROR 2003 (HY000): Can't connect ...(10061)`：MySQL 服务未启动，3306 无监听。
4. ❌ 无法识别 mysql 命令：bin 目录未加入系统 PATH。
5. ❌ 注册成功启动后立刻停止：my.ini 反斜杠错误、data 目录残留旧文件。
6. ❌ 忘记临时密码：删除 data 目录，重新初始化。

## MySQL 快速排错清单

1. 确认 PowerShell 管理员身份
2. `Get-Service MySQL84`，Running 为正常
3. 端口检测`netstat -ano | findstr "3306"`
4. 前台启动看报错：`.\mysqld --console`
5. 查看日志 `data/*.err`
6. 检查 my.ini 路径为正斜杠`/`
7. 确认执行过初始化

## MySQL 一键重置全套命令

```
net stop MySQL84
.\mysqld --remove MySQL84
Remove-Item D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\data -Recurse -Force
.\mysqld --initialize --console
.\mysqld --install MySQL84 --defaults-file="D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\my.ini"
sc config MySQL84 start= auto
net start MySQL84
```

------

# 3. Redis 5.0.14.1 Windows 部署（原生命令注册服务，开机自启）

> 路径：`C:\software\Redis\Redis-x64-5.0.14.1`，端口 6379

## 3.1 目录规划

表格

| 路径                                                      | 说明           |
| --------------------------------------------------------- | -------------- |
| `C:\software\Redis\Redis-x64-5.0.14.1\redis-server.exe`   | Redis 主程序   |
| `C:\software\Redis\Redis-x64-5.0.14.1\redis.windows.conf` | Redis 配置文件 |

## 3.2 redis.windows.conf 基础配置

```
port 6379
requirepass 123456
daemonize no
save 900 1
save 300 10
save 60 10000
```

> 局域网访问需要注释`bind 127.0.0.1`，设置`protected-mode no`

## 3.3 部署步骤（管理员 PowerShell）

```
cd C:\software\Redis\Redis-x64-5.0.14.1

# 注册开机自启服务
.\redis-server.exe --service-install redis.windows.conf --loglevel verbose --service-name Redis

# 启动服务
.\redis-server.exe --service-start
```

## 3.4 验证

```
# 查看服务状态
Get-Service Redis | Select Name,Status,StartType
# 端口检测
netstat -ano | findstr "6379"
# 连接客户端
.\redis-cli
AUTH 123456
```

## Redis 全套服务命令（5.0.14.1）

```
# 注册服务
.\redis-server.exe --service-install redis.windows.conf --loglevel verbose --service-name Redis
# 启动
.\redis-server.exe --service-start
# 停止
.\redis-server.exe --service-stop
# 删除服务
.\redis-server.exe --service-uninstall --service-name Redis
# 查询状态
Get-Service Redis | Select Name,Status,StartType
```

## 局域网访问（可选）

修改 redis.windows.conf

```
# bind 127.0.0.1
protected-mode no
requirepass 123456
```

防火墙放行 6379

```
New-NetFirewallRule -DisplayName "Redis 6379" -Direction Inbound -LocalPort 6379 -Protocol TCP -Action Allow
```

## Redis 踩坑清单

1. ❌ `--service-status` 参数不存在！使用`Get-Service Redis`查询状态。
2. ❌ Install/Remove Denied：非管理员终端。
3. ❌ 连接提示目标积极拒绝：Redis 服务 Stopped，6379 端口无监听。
4. ❌ 注册成功启动失败：前台运行`.\redis-server.exe redis.windows.conf`查看报错；检查配置语法、端口占用。
5. ❌ 双击 redis-server 只是临时前台运行，关闭窗口程序退出，**不是系统服务**。
6. ❌ redis-cli 无法识别：未配置 PATH，进入目录执行`.\redis-cli`。

## Redis 快速排错清单

1. 管理员 PowerShell
2. `Get-Service Redis`查看状态，Running 正常
3. 端口检测`netstat -ano | findstr "6379"`
4. 前台启动看实时报错
5. 检查 redis.windows.conf 语法、端口占用
6. 局域网：本机先连通，再检查 bind、protected-mode、防火墙

## Redis 一键重置命令

```
cd C:\software\Redis\Redis-x64-5.0.14.1
.\redis-server.exe --service-uninstall --service-name Redis
.\redis-server.exe --service-install redis.windows.conf --loglevel verbose --service-name Redis
.\redis-server.exe --service-start
```

------

# 4. 统一常用排错速查表

## 4.1 通用权限问题

> Install/Remove of the Service Denied! | 系统错误 5 拒绝访问 原因：PowerShell 不是管理员身份。 ✅ 解决：右键 PowerShell → **以管理员身份运行**。

## 4.2 连接报错：目标计算机积极拒绝（10061）

> MySQL / Redis 常见 含义：服务未启动，端口没有监听。 排查步骤：

1. `Get-Service 服务名` 查看状态是否 Running
2. 前台直接运行程序抓取报错日志
3. 检查端口占用

## 4.3 端口查看通用命令

```
# MinIO:9000 | MySQL:3306 | Redis:6379
netstat -ano | findstr "端口号"
```

## 4.4 查看服务状态通用模板

```
Get-Service 服务名 | Select Name,Status,StartType
```

## 4.5 防火墙放行端口通用模板

```
New-NetFirewallRule -DisplayName "名称" -Direction Inbound -LocalPort 端口 -Protocol TCP -Action Allow
```

## 4.6 环境变量 PATH

将各个组件 bin 目录加入系统 PATH 后，新开终端可全局调用命令。

------

你直接复制全部内容保存为 `Windows 开发环境部署.md` 即可。

# 5. 配套脚本

## 5.1 一次性查看 MinIO / MySQL84 / Redis 三个服务状态脚本

新建文件 `Check-All-Service.ps1`

```
<#
批量查看 MinIO、MySQL84、Redis 服务状态与启动类型
管理员/普通终端均可执行
#>
Write-Host "`n===== 开发环境服务状态 =====" -ForegroundColor Cyan
$serviceList = @("MinIO","MySQL84","Redis")
Get-Service -Name $serviceList -ErrorAction SilentlyContinue | Select-Object Name, Status, StartType

Write-Host "`n===== 端口监听检查 =====" -ForegroundColor Cyan
$portList = @(
    @{Name="MinIO";Port=9000},
    @{Name="MySQL";Port=3306},
    @{Name="Redis";Port=6379}
)
foreach ($item in $portList){
    $result = netstat -ano | findstr ":$($item.Port)"
    if($result){
        Write-Host "$($item.Name) 端口 $($item.Port) 正在监听" -ForegroundColor Green
    }else{
        Write-Host "$($item.Name) 端口 $($item.Port) 无监听【服务未启动】" -ForegroundColor Red
    }
}
Write-Host "`n=========================`n" -ForegroundColor Cyan
```

**运行方式**

```
.\Check-All-Service.ps1
```

## 5.2 批量启动三个服务（管理员 PowerShell）

新建 `Start-All-Service.ps1`

```
<#
批量启动 MinIO MySQL84 Redis
必须管理员PowerShell执行
#>
$serviceList = @("MinIO","MySQL84","Redis")
foreach($svc in $serviceList){
    $s = Get-Service $svc -ErrorAction SilentlyContinue
    if($s -and $s.Status -ne "Running"){
        Write-Host "正在启动 $svc ..."
        Start-Service $svc
    }elseif($s -and $s.Status -eq "Running"){
        Write-Host "$svc 已经在运行" -ForegroundColor Green
    }else{
        Write-Host "$svc 服务不存在" -ForegroundColor Yellow
    }
}
```

## 5.3 批量停止三个服务（管理员 PowerShell）

新建 `Stop-All-Service.ps1`

```
<#
批量停止 MinIO MySQL84 Redis
必须管理员PowerShell执行
#>
$serviceList = @("MinIO","MySQL84","Redis")
foreach($svc in $serviceList){
    $s = Get-Service $svc -ErrorAction SilentlyContinue
    if($s -and $s.Status -eq "Running"){
        Write-Host "正在停止 $svc ..."
        Stop-Service $svc
    }elseif($s -and $s.Status -ne "Running"){
        Write-Host "$svc 已经停止"
    }else{
        Write-Host "$svc 服务不存在" -ForegroundColor Yellow
    }
}
```

> 使用提示：
>
> 1. 保存 ps1 脚本后，右键 PowerShell【管理员身份】运行；
> 2. 批量启停脚本**需要管理员权限**，状态查看脚本不需要管理员；
> 3. 如果系统禁止执行 ps1 脚本，执行一次放行：
>
> ```
> Set-ExecutionPolicy RemoteSigned
> ```



