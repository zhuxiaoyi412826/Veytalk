package com.im.remote.agent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被控端文件操作：列目录 / 上传 / 下载 / 删除 / 重命名 / 建目录。
 *
 * <p>所有路径先 normalize 再校验「必须位于配置的允许盘符根之下」——
 * {@code ..} 逃逸与符号链接（用 toRealPath 二次校验已存在的路径）都在此拦下，
 * 这是被控方“只放行我愿意给的目录”的最后一道闸。
 *
 * <p>删除属于高危操作，受本地 allowDanger 开关约束并全部上报审计。
 */
public class FileOps {

    /** 目录列表最多返回条数，防止误点超大目录时 JSON 帧爆掉 */
    private static final int MAX_LISTING = 2000;
    /** 分块大小：64KB 在 1MB 单帧上限内留足元数据与加密膨胀余量 */
    static final int CHUNK_SIZE = 64 * 1024;
    /** 上传文件大小上限 */
    private static final long MAX_UPLOAD_BYTES = 512L * 1024 * 1024;

    private final AgentClient client;
    private final AgentConfig config;

    /** 进行中的上传：transferId -> 上下文 */
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    private static final class Upload {
        final Path target;
        final Path part;
        final OutputStream out;
        final long total;
        final String name;
        int index;
        long received;

        Upload(Path target, Path part, OutputStream out, long total, String name) {
            this.target = target;
            this.part = part;
            this.out = out;
            this.total = total;
            this.name = name;
        }
    }

    public FileOps(AgentClient client, AgentConfig config) {
        this.client = client;
        this.config = config;
    }

    /* ==================== 查询 ==================== */

    public Map<String, Object> listDir(String rawPath) throws IOException {
        List<Object> roots = new ArrayList<>();
        for (Path root : allowedRoots()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", root.toString());
            item.put("dir", true);
            item.put("path", root.toString());
            roots.add(item);
        }
        if (rawPath == null || rawPath.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", "");
            data.put("roots", roots);
            data.put("items", new ArrayList<>());
            return data;
        }
        Path dir = checkPath(rawPath);
        if (!Files.isDirectory(dir)) {
            throw new IOException("不是目录: " + rawPath);
        }
        List<Object> items = new ArrayList<>();
        boolean truncated = false;
        try (var stream = Files.list(dir)) {
            List<Path> children = stream.sorted(Comparator
                    .comparing((Path p) -> !isDirectory(p)).thenComparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path child : children) {
                if (items.size() >= MAX_LISTING) {
                    truncated = true;
                    break;
                }
                try {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", child.getFileName().toString());
                    item.put("path", child.toAbsolutePath().toString());
                    item.put("dir", isDirectory(child));
                    item.put("size", isDirectory(child) ? 0 : Files.size(child));
                    item.put("mtime", Files.getLastModifiedTime(child).toMillis());
                    items.add(item);
                } catch (IOException ignored) {
                    // 单个条目的属性读取失败（权限/竞态删除）不影响整体列表
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", dir.toAbsolutePath().toString());
        data.put("roots", roots);
        data.put("items", items);
        data.put("truncated", truncated);
        return data;
    }

    private boolean isDirectory(Path path) {
        try {
            return Files.isDirectory(path) && !Files.isSymbolicLink(path);
        } catch (Exception e) {
            return false;
        }
    }

    /* ==================== 变更 ==================== */

    public Map<String, Object> makeDir(String rawPath) throws IOException {
        requireDanger("mkdir " + rawPath);
        Path path = checkPath(rawPath);
        Files.createDirectories(path);
        return Map.of("path", path.toString());
    }

    public Map<String, Object> rename(String from, String to) throws IOException {
        requireDanger("rename " + from + " -> " + to);
        Files.move(checkPath(from), checkPath(to));
        return Map.of("from", from, "to", to);
    }

    public Map<String, Object> remove(String rawPath) throws IOException {
        requireDanger("rm " + rawPath);
        Path path = checkPath(rawPath);
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                List<Path> all = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path p : all) {
                    Files.deleteIfExists(p);
                }
            }
        } else {
            Files.delete(path);
        }
        return Map.of("path", path.toString());
    }

    /* ==================== 下载 ==================== */

    /** 分块回传整个文件；会话结束或异常时中止。在操作线程上执行（阻塞式 IO）。 */
    public void download(String transferId, String rawPath) throws IOException {
        Path file = checkPath(rawPath);
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在: " + rawPath);
        }
        long size = Files.size(file);
        int total = (int) Math.max(1, (size + CHUNK_SIZE - 1) / CHUNK_SIZE);
        String name = file.getFileName().toString();
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[CHUNK_SIZE];
            for (int index = 0; index < total; index++) {
                if (client.currentSid() == 0) {
                    return;
                }
                int read = in.readNBytes(buffer, 0, buffer.length);
                if (read <= 0) {
                    break;
                }
                Map<String, Object> metaMap = new LinkedHashMap<>();
                metaMap.put("transferId", transferId);
                metaMap.put("name", name);
                metaMap.put("index", index);
                metaMap.put("total", total);
                metaMap.put("size", size);
                client.sendBinaryFrame(AgentClient.FRAME_FILE, client.currentSid(),
                        MiniJson.write(metaMap), java.util.Arrays.copyOf(buffer, read));
            }
        }
        client.log("文件已回传: " + name + " (" + size + " B)");
        client.sendAudit("file-get", "path=" + rawPath + ", size=" + size);
    }

    /* ==================== 上传 ==================== */

    /** file-put 元数据帧：登记一次上传，后续二进制块经 handleChunk 写入 */
    public void beginUpload(String transferId, String dir, String name, long size) throws IOException {
        requireDanger("upload " + name);
        if (size > MAX_UPLOAD_BYTES) {
            throw new IOException("文件超过大小上限 " + MAX_UPLOAD_BYTES + " 字节");
        }
        Path target = checkPath(Path.of(dir, name).toString());
        Path part = target.resolveSibling(name + ".part");
        Files.createDirectories(target.getParent());
        Upload upload = new Upload(target, part,
                new BufferedOutputStream(Files.newOutputStream(part)), size, name);
        Upload old = uploads.put(transferId, upload);
        if (old != null) {
            closeQuietly(old);
        }
    }

    /** 二进制文件块：index 必须连续（乱序即协议违例，丢弃进行中的传输） */
    @SuppressWarnings("unchecked")
    public void handleChunk(String metaJson, byte[] payload) {
        Map<String, Object> meta;
        try {
            meta = (Map<String, Object>) MiniJson.parse(metaJson);
        } catch (RuntimeException e) {
            return;
        }
        String transferId = MiniJson.str(meta, "transferId");
        Upload upload = transferId == null ? null : uploads.get(transferId);
        if (upload == null) {
            return;
        }
        int index = (int) MiniJson.lng(meta, "index", -1);
        int total = (int) MiniJson.lng(meta, "total", -1);
        try {
            if (index != upload.index) {
                abortUpload(transferId, "块序号乱序: 期望 " + upload.index + " 收到 " + index);
                return;
            }
            byte[] plain = client.decryptPayload(payload);
            upload.out.write(plain);
            upload.received += plain.length;
            upload.index++;
            if (upload.index >= total) {
                finishUpload(transferId, upload);
            }
        } catch (IOException e) {
            abortUpload(transferId, e.getMessage());
        }
    }

    private void finishUpload(String transferId, Upload upload) throws IOException {
        upload.out.flush();
        upload.out.close();
        Files.move(upload.part, upload.target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        uploads.remove(transferId);
        client.log("文件已接收: " + upload.target + " (" + upload.received + " B)");
        client.sendAudit("file-put", "path=" + upload.target + ", bytes=" + upload.received);
    }

    private void abortUpload(String transferId, String reason) {
        Upload upload = uploads.remove(transferId);
        if (upload != null) {
            closeQuietly(upload);
            try {
                Files.deleteIfExists(upload.part);
            } catch (IOException ignored) {
                // 临时文件残留无碍
            }
            client.log("上传中止: " + reason);
        }
    }

    /** 会话结束：所有进行中的上传作废 */
    public void abortAll() {
        for (String id : new ArrayList<>(uploads.keySet())) {
            abortUpload(id, "会话结束");
        }
    }

    private void closeQuietly(Upload upload) {
        try {
            upload.out.close();
        } catch (IOException ignored) {
            // 关闭失败无副作用
        }
    }

    /* ==================== 路径与开关 ==================== */

    public List<Path> allowedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String raw : config.allowRoots().split(";")) {
            if (!raw.isBlank()) {
                roots.add(Path.of(raw.trim()));
            }
        }
        return roots;
    }

    /** 归一化 + 盘符根校验 + （路径存在时）realPath 二次校验，防符号链接逃逸 */
    Path checkPath(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("路径不能为空");
        }
        Path candidate = Path.of(raw).toAbsolutePath().normalize();
        List<Path> roots = allowedRoots();
        boolean allowed = false;
        for (Path root : roots) {
            if (candidate.startsWith(root.toAbsolutePath().normalize())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IOException("路径不在允许范围内: " + raw);
        }
        // 符号链接逃逸检测：最近的已存在祖先解析 realPath 后仍须落在允许范围内
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            Path real = existing.toRealPath();
            boolean realAllowed = false;
            for (Path root : roots) {
                if (real.startsWith(root.toAbsolutePath().normalize())) {
                    realAllowed = true;
                    break;
                }
            }
            if (!realAllowed) {
                throw new IOException("路径解析到允许范围之外: " + raw);
            }
        }
        return candidate;
    }

    private void requireDanger(String action) throws IOException {
        if (!config.allowDanger()) {
            throw new IOException("被控端未开启高危文件操作开关: " + action);
        }
    }
}
