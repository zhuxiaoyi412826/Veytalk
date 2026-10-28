# ==============================================================================
# 路线 A（推荐）：jar 由构建机 / CI 产出，本镜像只做运行时。
# 对应 Docker部署指南.md §4.1。构建上下文 = Docker/app 这一级目录（compose 里 context: ./app），
# 所以本文件只能看见 ./im-server.jar 与 ./extra/，看不见仓库源码。
#
#   docker build -t local/im-server:1.0.0 Docker/app
#   或随 compose：docker compose build im-server
#
# 需要「在服务器上从源码构建」时改用同目录的 Dockerfile.multistage（上下文是仓库根）。
# ==============================================================================

# ★ 别换成 eclipse-temurin:21-jre-alpine：那是 busybox sh，
#   docker-compose.yml 里 im-server 的健康检查用 bash 的 /dev/tcp，busybox 不支持，
#   表现是容器永远 unhealthy → nginx 的 depends_on: service_healthy 卡死，服务起不来还看不出原因。
# ★ 同理，健康检查也不能写回 CMD-SHELL：CMD-SHELL 走 /bin/sh，而 Ubuntu 底镜像的 /bin/sh 是 dash，
#   dash 一样不认 /dev/tcp —— compose 里那条必须明写 CMD ["bash", "-c", …]（bash 由 Ubuntu 基础镜像自带，不另装包）。
FROM eclipse-temurin:21-jre

WORKDIR /app

# 非 root 运行。uid/gid 固定 1000，与宿主机 ./data/{logs,upload-tmp,live} 的 chown 对齐
# （scripts/bootstrap.sh 里做的那步）。uid 不固定就会「日志写不进卷」，而 logback 不拦启动，静默丢日志。
#
# ★ 踩过的坑（Actions 首次构建真的挂在这，groupadd 退出码 4 = GID 已存在）：不能写裸的
#   `groupadd --system --gid 1000 im` —— Ubuntu 23.04+/Debian 12+（也就是 24.04，本镜像的底）在
#   /etc/group 里预置了一行 `gro:x:1000:`，那是 adduser 包用来占住 1000 这段 GID 的，
#   所以指定 gid 必失败。下面是一条四级阶梯，四条路径都本机实测过：
#     ① 组已存在 → 复用；② 直接建 gid=1000；③ 占用者是 gro → 把它改名让位（gro 不挂任何
#     文件，改名安全，结果仍是 1000）；④ 被别的组占着 → 自动分配 gid 兜底，不拦构建。
#   注：RUN 的续行里不能投行内注释 —— 行尾反斜杠会把换行吃掉，注释会把它后面那行一起注掉。
ARG IM_UID=1000
ARG IM_GID=1000
RUN set -eu; \
    if getent group im >/dev/null 2>&1; then \
      echo "[im] group im 已存在，复用"; \
    elif groupadd --system --gid "${IM_GID}" im 2>/dev/null; then \
      echo "[im] group im gid=${IM_GID}"; \
    elif occ=$(getent group "${IM_GID}" | head -1 | cut -d: -f1); [ -n "$occ" ] && [ "$occ" = gro ] \
         && groupmod -n im gro; then \
      echo "[im] GID ${IM_GID} 被占位组 gro 拿着，改名让位"; \
    elif groupadd --system im; then \
      echo "[im] !! GID ${IM_GID} 被其它组拿着，im 改用自动分配的 gid（不影响运行，只是组属主不再等于宿主机目录的 1000）"; \
    else \
      echo "[im] !! 建组失败，上面有 groupadd 的原始报错"; exit 1; \
    fi; \
    gid=$(getent group im | head -1 | cut -d: -f3); \
    if id -u im >/dev/null 2>&1; then \
      echo "[im] user im 已存在，复用"; \
    elif useradd --system --uid "${IM_UID}" --gid "${gid:-0}" --create-home --home-dir /app im; then \
      echo "[im] user im uid=${IM_UID}"; \
    else \
      echo "[im] !! 建用户失败（多半是 UID ${IM_UID} 已被别的用户占用，比如带 ubuntu 用户的底座镜像）"; exit 1; \
    fi; \
    mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra; \
    chown -R "$(id -u im)":"$(id -g im)" /app

# 这几个 mkdir 的目录都会被 compose 的 bind mount 覆盖，留着是为了「不挂卷时也能起来」
# （调试场景常见），以及给 IM_FILE_STORAGE=local 一个落点。

# 由 CI（.github/workflows/build-and-package.yml）或构建机放进 Docker/app/ 的产物；
# 名字来自 im-bootstrap/pom.xml 的 <finalName>im-server</finalName>，不是 xxx-1.0.0.jar
COPY --chown=1000:1000 im-server.jar /app/im-server.jar

# JAVA_OPTS 里的参数一定拼在 -jar 之前（见下面 ENTRYPOINT）。
# MaxRAMPercentage 与 compose 的 deploy.resources.limits.memory: 2g 配套 → 堆上限约 1.5g。
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
USER 1000:1000

# ★ ENTRYPOINT（不是 CMD）+ exec：exec 让 java 顶替 sh 成为 PID 1，
#   否则 docker stop 的 SIGTERM 只打到 sh 上，java 等 10s 后被 SIGKILL——
#   Hikari 连接池与 WebSocket 会话都没有优雅关闭的机会（客户端表现为「掉线没有重连提示」）。
# ★ 这里不写死 --spring.profiles.active：profile 由 conf/im.env 的 SPRING_PROFILES_ACTIVE 控制，
#   命令行参数优先级更高，写死了会让人改 env 改了个寂寞。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/im-server.jar"]
