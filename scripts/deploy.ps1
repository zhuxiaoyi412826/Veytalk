# =============================================================================
# deploy.ps1 —— 一键构建并上传「后端 jar / 前端三站」到生产服务器
#
# 用法（在本仓库任意目录的 PowerShell 里）：
#   powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1
#   或先 cd 到仓库根：.\scripts\deploy.ps1
#
# 运行后弹出菜单，输入编号（多选请用逗号分隔，中英文逗号都认）：
#   1) 仅 jar 包        2) 仅 im-ui        3) 仅 im-admin-ui
#   4) 仅 im-website    5) 全部上传
#   例：输入 1,3 表示「只传后端 + 只传 im-admin-ui」
#
# ★ 依赖（Windows 10/11 自带，缺则装）：ssh、scp（OpenSSH 客户端）、node/npm。
# ★ 前端「覆盖即生效」的前提：容器已在跑、web/<站> 目录已由 up -d 建好。本脚本只替换文件，
#   不碰 nginx；因此**只删目录里的内容、保留目录本身**——若把目录整体 rm -rf，nginx 里那条
#   :ro bind mount 会指向被删的 inode、变成空挂载，除非 restart nginx 否则新文件看不到。
# ★ 后端 jar 不是 bind mount，是镜像内容（compose build 的 context=./app）：换完 jar 必须
#   在服务器上 `docker compose build im-server && docker compose up -d im-server` 才生效，
#   本脚本会在传完 jar 后打印这两条命令供你复制执行（不擅自替你重启线上服务）。
# =============================================================================

# ------------------------------------------------------------------ 按需修改
$SSH_USER  = "root"              # 服务器登录用户
$SSH_HOST  = "120.78.126.132"    # ★ 改成你的服务器地址
$SSH_PORT  = 22                  # SSH 端口
$SSH_KEY   = "D:\文件\密钥\阿里云服务器登录私钥\_GitHub.pem"   # 阿里云登录私钥（root 仅密钥登录，本服务器禁用密码）

$REMOTE_APP_DIR = "/opt/im-docker/app"   # 后端 jar 目录
$REMOTE_WEB_DIR = "/opt/im-docker/web"   # 前端三站父目录

$PROJECT_ROOT = (Resolve-Path "$PSScriptRoot\..").Path
$JAR_LOCAL    = Join-Path $PROJECT_ROOT "im-bootstrap\target\im-server.jar"

# 站内名称 → 本地目录（dist 在其下的 dist 子目录，远端目录名与站名一致）
$SITE_MAP = [ordered]@{
    "im-ui"        = Join-Path $PROJECT_ROOT "im-ui"
    "im-admin-ui"  = Join-Path $PROJECT_ROOT "im-admin-ui"
    "im-website"   = Join-Path $PROJECT_ROOT "im-website"
}

# ------------------------------------------------------------------ 基础设施
$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$remote = "$SSH_USER@$SSH_HOST"

# ssh 公共参数（端口 -p，密钥 -i）
$sshBase = @()
if ($SSH_PORT -ne 22) { $sshBase += @("-p", "$SSH_PORT") }
if ($SSH_KEY)         { $sshBase += @("-i", $SSH_KEY) }
# scp 公共参数（端口是大写 -P）
$scpBase = @()
if ($SSH_PORT -ne 22) { $scpBase += @("-P", "$SSH_PORT") }
if ($SSH_KEY)         { $scpBase += @("-i", $SSH_KEY) }

function Write-Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    ✔ $m" -ForegroundColor Green }
function Write-Info($m) { Write-Host "    · $m" -ForegroundColor DarkGray }

# 远程执行一条 bash 命令，非零退出即抛错中断
function Invoke-Remote([string]$cmd) {
    Write-Info "远程: $cmd"
    & ssh @sshBase $remote $cmd
    if ($LASTEXITCODE -ne 0) { throw "远程命令失败（exit=$LASTEXITCODE）：$cmd" }
}

# scp 上传，非零退出即抛错中断
function Send-Scp([string]$local, [string]$remotePath) {
    Write-Info "上传: $local -> ${remote}:$remotePath"
    & scp @scpBase $local "${remote}:$remotePath"
    if ($LASTEXITCODE -ne 0) { throw "scp 上传失败（exit=$LASTEXITCODE）：$local" }
}

# 用 ZipArchive 逐条 CreateEntry 压目录「内容」。.NET Framework（PowerShell 5.1）的 Compress-Archive
# 与 ZipFile.CreateFromDirectory 都会把子目录条目写成反斜杠 '\'（违反 ZIP 规范），Linux unzip 直接
# exit=1 失败；这里手工把相对路径的分隔符换成 '/'，且 zip 根即文件、解包不多套一层目录。
function New-ZipFromDir([string]$sourceDir, [string]$zipPath) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $base = $sourceDir.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $fs = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
    $archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($f in (Get-ChildItem -Path $sourceDir -Recurse -File)) {
            $rel = $f.FullName.Substring($base.Length).Replace('\', '/')
            $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
            $es = $entry.Open()
            try {
                $src = [System.IO.File]::OpenRead($f.FullName)
                try { $src.CopyTo($es) } finally { $src.Dispose() }
            } finally { $es.Dispose() }
        }
    } finally {
        $archive.Dispose(); $fs.Dispose()
    }
}

# ------------------------------------------------------------------ 任务：jar
function Deploy-Jar {
    Write-Step "部署后端 im-server.jar"
    if (-not (Test-Path $JAR_LOCAL)) {
        throw "找不到 jar：$JAR_LOCAL`n    请先执行：mvn -pl im-bootstrap -am package -DskipTests"
    }
    $jarSize = [math]::Round((Get-Item $JAR_LOCAL).Length / 1MB, 1)
    Write-Info "本地产物：$JAR_LOCAL（$jarSize MB）"

    # 1) 先删服务器上的旧 jar，2) 再传新 jar —— 严格「先删后传」，避免残留
    Invoke-Remote "mkdir -p $REMOTE_APP_DIR && rm -f $REMOTE_APP_DIR/im-server.jar"
    Send-Scp $JAR_LOCAL "$REMOTE_APP_DIR/"
    Write-Ok "jar 已替换到 $REMOTE_APP_DIR/im-server.jar"

    Write-Host ""
    Write-Host "    ★ jar 不是热挂载，必须重建容器才生效。SSH 到服务器执行：" -ForegroundColor Yellow
    Write-Host "        cd /opt/im-docker" -ForegroundColor Yellow
    Write-Host "        docker compose build im-server && docker compose up -d im-server" -ForegroundColor Yellow
    Write-Host "        （换了 TRTC 等配置再补一句：docker compose restart im-server）" -ForegroundColor Yellow
}

# ------------------------------------------------------------------ 任务：前端某站
function Deploy-Site([string]$name) {
    $siteDir = $SITE_MAP[$name]
    Write-Step "部署前端 $name"
    if (-not (Test-Path $siteDir)) { throw "找不到站点目录：$siteDir" }

    # 1) npm run build（用 npm.cmd 绕开 npm.ps1 的执行策略限制）
    Push-Location $siteDir
    try {
        Write-Info "构建：npm run build"
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "npm run build 失败（exit=$LASTEXITCODE）：$name" }
    } finally { Pop-Location }

    # 2) 校验 dist
    $dist = Join-Path $siteDir "dist"
    if (-not (Test-Path $dist)) { throw "构建后仍无 dist 目录：$dist" }

    # 3) 把 dist「内容」压到仓库根 dist-zip\<name>.zip（zip 根即文件、无外层目录、条目分隔符为正斜杠）
    $zipDir  = Join-Path $PROJECT_ROOT "dist-zip"
    if (-not (Test-Path $zipDir)) { New-Item -ItemType Directory -Path $zipDir | Out-Null }
    $zipPath = Join-Path $zipDir "$name.zip"
    Write-Info "压缩：$dist -> $zipPath"
    New-ZipFromDir $dist $zipPath

    # 4) 上传 zip 到 web 父目录
    Send-Scp $zipPath "$REMOTE_WEB_DIR/"

    # 5) 远程解包：保留 <站> 目录本身、只清内容，再把 zip 解进去（zip 根即文件 ⇒ 进目录直接可见）
    $siteRemote  = "$REMOTE_WEB_DIR/$name"
    $zipRemote   = "$REMOTE_WEB_DIR/$name.zip"
    Invoke-Remote "command -v unzip >/dev/null || { echo '服务器缺 unzip，请执行: apt-get install -y unzip' >&2; exit 3; }"
    Invoke-Remote "mkdir -p $siteRemote && rm -rf $siteRemote/* && cd $siteRemote && unzip -oq $zipRemote && rm -f $zipRemote"
    Remove-Item $zipPath -Force   # 清本地临时 zip
    Write-Ok "$name 已更新到 $siteRemote（覆盖即生效，无需重启 nginx；浏览器 Ctrl+F5 硬刷）"
}

# ------------------------------------------------------------------ 菜单与调度
function Resolve-Selection([string]$raw) {
    $raw = $raw -replace "，", "," -replace "\s", ","
    $tokens = $raw.Split(",") | Where-Object { $_ -ne "" } | Select-Object -Unique
    foreach ($t in $tokens) { if ($t -notin @("1","2","3","4","5")) { throw "无效编号：$t（只认 1~5）" } }
    if (-not $tokens) { throw "未选择任何项" }

    $doJar = $false; $sites = @()
    if ($tokens -contains "5") {
        $doJar = $true; $sites = @("im-ui", "im-admin-ui", "im-website")
    } else {
        if ($tokens -contains "1") { $doJar = $true }
        if ($tokens -contains "2") { $sites += "im-ui" }
        if ($tokens -contains "3") { $sites += "im-admin-ui" }
        if ($tokens -contains "4") { $sites += "im-website" }
    }
    return @{ Jar = $doJar; Sites = $sites }
}

Write-Host ""
Write-Host "  选择要上传的内容（可多选，逗号分隔，如 1,3 或 2,4）：" -ForegroundColor White
Write-Host "    1) 仅 jar 包（后端 im-server.jar）"
Write-Host "    2) 仅 im-ui          3) 仅 im-admin-ui"
Write-Host "    4) 仅 im-website     5) 全部上传"
Write-Host "  目标服务器：$remote  （端口 $SSH_PORT）" -ForegroundColor DarkGray
$raw = Read-Host "  请输入编号"

$sel = Resolve-Selection $raw
Write-Host ""
Write-Host "  将执行：jar=$($sel.Jar)  站点=$($sel.Sites -join ', ')" -ForegroundColor Magenta

$started = Get-Date
if ($sel.Jar)   { Deploy-Jar }
foreach ($s in $sel.Sites) { Deploy-Site $s }
$elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)

Write-Step "全部完成（用时 $elapsed 秒）"
