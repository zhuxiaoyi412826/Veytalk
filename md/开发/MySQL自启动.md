# MySQL8.4.10 WinX64 ZIP 包 稳定部署文档

> 环境：Windows，免安装 ZIP 版 MySQL 8.4.10；路径：`D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64` 服务名：MySQL84，端口：3306

## 一、目录规划

表格

| 路径                                                         | 说明                                         |
| ------------------------------------------------------------ | -------------------------------------------- |
| `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\bin` | mysql 可执行文件目录                         |
| `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\my.ini` | 主配置文件（放在 mysql 根目录，和 bin 同级） |
| `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\data` | 数据库数据目录，初始化自动生成               |

## 二、my.ini 配置（直接复制）

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

## 三、前置操作（管理员 PowerShell 执行）

进入 bin 目录

```
cd D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\bin
```

### 1. 初始化数据库（生成 data 目录 + root 临时密码）

> ⚠️ **执行前必须删除已存在的 data 文件夹**

```
.\mysqld --initialize --console
```

执行成功，控制台输出临时密码，**务必保存**： `A temporary password is generated for root@localhost: xxxxxx`

### 2. 注册 Windows 系统服务

```
.\mysqld --install MySQL84 --defaults-file="D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\my.ini"
```

成功提示：`Service successfully installed.`

### 3. 设置开机自启动

mysqld 注册服务默认是自动，可手动确认 / 修改：

```
# 查看服务状态+启动类型
Get-Service MySQL84 | Select-Object Name,Status,StartType

# 手动设置开机自动（Automatic）
sc config MySQL84 start= auto
```

> `start=`后面**必须保留空格**，语法强制要求。

### 4. 启停服务命令

```
# 启动
net start MySQL84
# 停止
net stop MySQL84
# 删除服务（卸载用）
.\mysqld --remove MySQL84
```

### 5. 登录 MySQL 并修改 root 密码

#### 5.1 本地登录

两种方式：

1. 进入 bin 目录执行

```
.\mysql -uroot -p
```

1. 【推荐】配置系统环境变量 PATH 把下面路径加到系统 Path： `D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\bin`

> 添加后**关闭所有终端，重新打开 PowerShell**，可直接使用：

```
mysql -uroot -p
```

#### 5.2 修改 root 密码（MySQL8 强制修改临时密码）

```
ALTER USER 'root'@'localhost' IDENTIFIED BY '你的新密码';
FLUSH PRIVILEGES;
```

> 密码至少 8 位，满足 MySQL 密码策略。

### 6. 局域网访问（可选）

1. 登录 MySQL，开放 root 任意 IP 访问

```
CREATE USER 'root'@'%' IDENTIFIED BY '你的密码';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
```

1. 防火墙放行 3306 端口（管理员 PowerShell）

```
New-NetFirewallRule -DisplayName "MySQL 3306" -Direction Inbound -LocalPort 3306 -Protocol TCP -Action Allow
```

## 四、验证

```
# 查看3306端口监听
netstat -ano | findstr "3306"
```

有输出代表 MySQL 正常监听端口。

------

# 🚨 MySQL 踩坑记录

1. ❌ `Install/Remove of the Service Denied!`

> 原因：PowerShell**不是管理员身份**。 ✅ 解决：右键 PowerShell，【以管理员身份运行】，所有 mysqld 注册 / 初始化 / 启停服务都要用管理员终端。

1. ❌ `系统错误 5。拒绝访问。`

> 原因：非管理员窗口执行`net start / net stop`启停服务，权限不足。 ✅ 解决：打开管理员 PowerShell 操作。

1. ❌ `ERROR 2003 (HY000): Can't connect to MySQL server on 'localhost:3306' (10061)`

> 原因：MySQL 服务未启动，3306 端口没有监听。 ✅ 排查：`Get-Service MySQL84`查看状态；前台`.\mysqld --console`查看实时报错；查看 data 目录下`.err`日志。

1. ❌ `无法将“mysql”项识别为 cmdlet`

> 原因：mysql 的 bin 目录未添加到系统 PATH 环境变量。 ✅ 解决：进入 bin 目录执行`.\mysql`，或者配置系统 PATH。

1. ❌ 注册服务成功，但是启动后立刻停止

> 常见：my.ini 路径使用`\`反斜杠、data 目录残留旧文件、初始化未执行。 ✅ 重置方案：删除服务 → 删除 data 文件夹 → 重新初始化 → 重新注册服务。

1. ❌ my.ini 路径写法错误

> Windows 下 my.ini 必须使用`/`正斜杠，不能`\`，否则 mysqld 找不到目录，服务闪退。

1. ❌ 忘记保存初始化临时密码

> 处理：删除 data 目录，重新执行`.\mysqld --initialize --console`，重新生成密码。

## 五、快速排错清单（按顺序检查）

1. 确认 PowerShell 为**管理员身份**；
2. 查看服务状态：`Get-Service MySQL84 | Select Name,Status,StartType`，状态必须为 Running；
3. 检查端口：`netstat -ano | findstr "3306"`，无输出代表服务没正常启动；
4. 前台启动看实时报错：`.\mysqld --console`；
5. 查看错误日志：`data/*.err`；
6. 检查 my.ini 路径是否为`/`正斜杠；
7. 确认初始化已经执行，data 目录正常生成；
8. 端口占用：如果 3306 被占用，修改 my.ini 的 port 端口号；
9. 局域网访问不通：先本机 127.0.0.1 登录测试，再检查防火墙 3306 放行规则。

## 六、一键重置全套命令（服务异常崩溃，从头重建）

```
#1 删除旧服务
net stop MySQL84
.\mysqld --remove MySQL84
#2 删除旧data目录
Remove-Item D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\data -Recurse -Force
#3 重新初始化，获取临时密码
.\mysqld --initialize --console
#4 注册服务
.\mysqld --install MySQL84 --defaults-file="D:\mysql\mysql\mysql-8.4.10-winx64\mysql-8.4.10-winx64\my.ini"
#5 设置开机自启
sc config MySQL84 start= auto
#6 启动服务
net start MySQL84
```

> 可以直接保存为 `MySQL8.4-Windows-ZIP部署.md`。