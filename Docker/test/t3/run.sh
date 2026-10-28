#!/bin/bash
# 三场景跑候选 RUN 体（newblock.sh），用 dash 解释以贴近 Docker /bin/sh
BASE="$(cd "$(dirname "$0")" && pwd)"
STUB="$BASE/stub"
BLOCK="$BASE/newblock.sh"
SH="$(command -v dash || echo bash)"
echo "解释器: $SH"

seed() { # $1=场景名
  rm -rf "$BASE/state"; mkdir -p "$BASE/state/etc"
  export ST="$BASE/state"
  case "$1" in
    ubuntu)   # Ubuntu 24.04 真实底座：ubuntu 用户 uid1000 + ubuntu 组 gid1000，外加一堆系统组
      printf 'root:x:0:\ndaemon:x:1:\nadm:x:4:\nstaff:x:50:\nubuntu:x:1000:\n' > "$ST/etc/group"
      printf 'root:x:0:0::/root:/bin/bash\ndaemon:x:1:1::/usr/sbin:/usr/sbin/nologin\nubuntu:x:1000:1000::/home/ubuntu:/bin/bash\n' > "$ST/etc/passwd" ;;
    clean)    # 干净底座：无任何 1000
      printf 'root:x:0:\ndaemon:x:1:\nadm:x:4:\nstaff:x:50:\n' > "$ST/etc/group"
      printf 'root:x:0:0::/root:/bin/bash\ndaemon:x:1:1::/usr/sbin:/usr/sbin/nologin\n' > "$ST/etc/passwd" ;;
    gro)      # 只有占位组 gro:1000，没有 uid1000 用户
      printf 'root:x:0:\ndaemon:x:1:\ngro:x:1000:\n' > "$ST/etc/group"
      printf 'root:x:0:0::/root:/bin/bash\ndaemon:x:1:1::/usr/sbin:/usr/sbin/nologin\n' > "$ST/etc/passwd" ;;
  esac
}

export PATH="$STUB:$PATH"
export IM_UID=1000 IM_GID=1000
for s in ubuntu clean gro; do
  seed "$s"
  echo "================= 场景 [$s] ================="
  "$SH" "$BLOCK"
  echo "---> exit=$? ; 建组后 group: $(grep -c . "$ST/etc/group") 行, passwd: $(grep -c . "$ST/etc/passwd") 行"
done
