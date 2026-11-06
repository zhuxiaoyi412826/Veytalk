#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接口文档覆盖率自查：找出「写了注解但没进 knife4j 分组」的接口。

为什么要有这个脚本：Knife4j 的左侧分组不是按 @Tag 自动生成的，而是由
im-common 的 Knife4jConfig 里每个 GroupedOpenApi Bean 的 pathsToMatch 前缀切出来的。
只补 @Tag / @Operation 而不补前缀，接口在 doc.html 上就是**不存在，而且一声不吭**
（编译通过、启动正常、Swagger 原始 JSON 里能看到它，唯独 UI 点不到）。
这种事靠人眼审 47 个控制器审不出来，只能拿 /v3/api-docs 的全量路径去减各分组的命中集。

用法（服务必须是 dev profile——prod 把 knife4j 与 springdoc 全关，/v3/api-docs 回 404）：
    python scripts/api-doc-check.py                      # 默认 http://localhost:8080
    python scripts/api-doc-check.py http://127.0.0.1:8081  # 端口不是 8080 时直接传

退出码：0 = 全部覆盖；1 = 有接口没进任何分组（正文会逐条列 MISSING）；2 = 服务没起 / 文档被关。
所以它能并进 CI，也能被别的脚本直接 call。
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# 优先级：命令行参数 > 环境变量 IM_DOC_BASE > 默认 8080
BASE = (sys.argv[1] if len(sys.argv) > 1
        else os.environ.get("IM_DOC_BASE") or "http://localhost:8080").rstrip("/")


def get(path):
    """取一份 OpenAPI JSON。404 通常意味着跑的是 prod profile（文档被关）。"""
    try:
        with urllib.request.urlopen(BASE + path, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            sys.exit("[2] %s%s 回 404 —— 服务多半跑的是 prod（knife4j/springdoc 全关），"
                     "请用 dev profile 重启后端再跑" % (BASE, path))
        sys.exit("[2] %s%s 回 HTTP %s" % (BASE, path, e.code))
    except Exception as e:  # 连接被拒 / 超时
        sys.exit("[2] 连不上 %s%s：%s —— 后端起来了吗？" % (BASE, path, e))


def paths_of(doc):
    return set(doc.get("paths", {}))


full = paths_of(get("/v3/api-docs"))
cfg = get("/v3/api-docs/swagger-config")
groups = cfg.get("urls", [])

covered = set()
print("分组 %d 个（swagger-config.urls）：" % len(groups))
for u in sorted(groups, key=lambda x: x["name"]):
    hit = paths_of(get("/v3/api-docs/" + urllib.parse.quote(u["name"])))
    covered |= hit
    print("  %-28s paths=%3d" % (u["name"], len(hit)))

missing = sorted(full - covered)
extra = sorted(covered - full)  # 分组里出现了全量文档没有的路径，正常应为 0

print("\n全量路径 /v3/api-docs : %d" % len(full))
print("被分组覆盖            : %d" % len(covered & full))
print("未进任何分组          : %d" % len(missing))
for m in missing:
    print("   MISSING %s" % m)
if extra:
    print("★ 异常：分组里有 %d 条路径不在全量文档中（多半是分组名撞了默认分组）" % len(extra))
    for x in extra:
        print("   EXTRA   %s" % x)

if missing:
    print("\n[1] 有接口没进任何分组：去 im-common/src/main/java/com/im/common/config/Knife4jConfig.java"
          " 给对应分组补 pathsToMatch 前缀，或新开一个 GroupedOpenApi Bean（分组名前缀编号接着排）。")
    sys.exit(1)
print("\n[0] 覆盖率 100%，接口文档没有漏网的接口。")
