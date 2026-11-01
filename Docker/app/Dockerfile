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

# 非 root 运行。运行身份固定为数字 uid/gid 1000，与宿主机 ./data 下各写目录的 chown 对齐
# （scripts/bootstrap.sh 里做的那步）。uid 不固定就会「日志写不进卷」，而 logback 不拦启动，静默丢日志。
#
# ★★ 本段被真实构建打挂两次才想通，根因是一条容易忽略的认知：需要的只是「镜像里存在 uid/gid 这两个数字」，
#    跟数字背后叫什么名字无关 —— USER 1000:1000、chown -R 1000:1000、COPY --chown=1000:1000 全是数字，
#    不查 /etc/passwd。而 eclipse-temurin:21-jre 的底（Ubuntu 24.04）本身就自带一个 ubuntu 用户
#    （uid 1000）和它的同名组（gid 1000）。所以**绝不要再新建一个叫 im 的用户去抢 1000 这个数字**：
#      · `groupadd --gid 1000 im` → 退出码 4（GID 已存在；占用者未必是网传 adduser 的 gro 占位组，
#        实测这个镜像里就是 ubuntu 组，所以早前「改名让位 gro」那条分支根本没触发）
#      · 侥幸跳过建组、接着 `useradd --uid 1000 im` → `useradd: UID 1000 is not unique`
#    正解只有一句：数字 1000 已存在就复用，不存在才补建（getent 对 passwd/group 支持按数字查，glibc 特性）。
ARG IM_UID=1000
ARG IM_GID=1000
RUN set -eu; \
    if getent group "${IM_GID}" >/dev/null 2>&1; then \
      echo "[im] gid ${IM_GID} 已存在（$(getent group "${IM_GID}" | head -1 | cut -d: -f1)），复用这个数字"; \
    else \
      groupadd --system --gid "${IM_GID}" im; \
    fi; \
    if getent passwd "${IM_UID}" >/dev/null 2>&1; then \
      echo "[im] uid ${IM_UID} 已存在（$(getent passwd "${IM_UID}" | head -1 | cut -d: -f1)），复用这个数字，不建 im 用户"; \
    else \
      useradd --system --uid "${IM_UID}" --gid "${IM_GID}" --home-dir /app im; \
    fi; \
    mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra; \
    chown -R "${IM_UID}:${IM_GID}" /app

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
