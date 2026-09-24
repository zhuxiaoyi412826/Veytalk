package com.im.remote.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 被控系统操作：进程列表 / 结束进程 / 启动程序 / cmd 执行 / 电源。
 *
 * <p>高危等级从低到高：ps-list（只读）→ clip/exec 之外的查询 → kill/run/exec → power。
 * 除只读查询外全部受本地 allowDanger 开关约束，并逐条上报审计——
 * 「高危操作可禁用」的承诺在被控端本地生效，服务端配置只是二道防线。
 */
public class SystemOps {

    /** cmd 输出截断上限：超过部分丢弃并在结果中标记 truncated */
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final long EXEC_TIMEOUT_SECONDS = 30;

    private final AgentClient client;
    private final AgentConfig config;

    public SystemOps(AgentClient client, AgentConfig config) {
        this.client = client;
        this.config = config;
    }

    /* ==================== 进程 ==================== */

    public Map<String, Object> processList() {
        List<Object> items = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pid", handle.pid());
            handle.info().command().ifPresent(c -> item.put("command", c));
            handle.info().user().ifPresent(u -> item.put("user", u));
            handle.info().arguments().ifPresent(a -> item.put("args", String.join(" ", a)));
            items.add(item);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return data;
    }

    public Map<String, Object> kill(long pid) throws SecurityException {
        requireDanger("ps-kill pid=" + pid);
        boolean ok = ProcessHandle.of(pid)
                .map(ProcessHandle::destroyForcibly)
                .orElse(false);
        client.sendAudit("ps-kill", "pid=" + pid + ", ok=" + ok);
        if (!ok) {
            throw new IllegalStateException("进程不存在或无权结束: " + pid);
        }
        return Map.of("pid", pid);
    }

    public Map<String, Object> run(String command) throws Exception {
        requireDanger("ps-run " + command);
        // 刻意不解析引号：整条命令交给系统 shell，语义与用户在本机命令行输入一致
        new ProcessBuilder(shellArgs(command)).start();
        client.sendAudit("ps-run", command);
        return Map.of("started", true);
    }

    /* ==================== 命令执行 ==================== */

    public Map<String, Object> exec(String command) throws Exception {
        requireDanger("exec " + command);
        Process process = new ProcessBuilder(shellArgs(command)).redirectErrorStream(true).start();
        String output;
        try {
            byte[] raw = readLimited(process.getInputStream());
            if (!process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output = new String(raw, outputCharset()) + "\n[超时 " + EXEC_TIMEOUT_SECONDS + "s，已强制结束]";
            } else {
                output = new String(raw, outputCharset()) + "\n[exit=" + process.exitValue() + "]";
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        client.sendAudit("exec", command);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("command", command);
        data.put("output", output);
        return data;
    }

    private byte[] readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while (total < MAX_OUTPUT_BYTES && (read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            total += read;
        }
        if (total >= MAX_OUTPUT_BYTES) {
            buffer.write("\n[输出超长已截断]".getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    /** Windows 简中系统 cmd 输出是 GBK，误按 UTF-8 解码会得到乱码 */
    private Charset outputCharset() {
        if (isWindows()) {
            try {
                return Charset.forName("GBK");
            } catch (Exception ignored) {
                return Charset.defaultCharset();
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String[] shellArgs(String command) {
        return isWindows()
                ? new String[]{"cmd.exe", "/c", command}
                : new String[]{"/bin/sh", "-c", command};
    }

    /* ==================== 电源 ==================== */

    public Map<String, Object> power(String action) throws Exception {
        requireDanger("power " + action);
        String command = switch (action == null ? "" : action) {
            case "shutdown" -> isWindows() ? "shutdown /s /t 5" : "shutdown -h +0";
            case "reboot" -> isWindows() ? "shutdown /r /t 5" : "shutdown -r +0";
            case "lock" -> isWindows() ? "rundll32.exe user32.dll,LockWorkStation" : "loginctl lock-session";
            default -> throw new IllegalArgumentException("未知电源指令: " + action);
        };
        client.sendAudit("power", action);
        // Windows 的 shutdown 带 5 秒延迟，给审计帧先走出发射线程的机会
        new ProcessBuilder(command.split(" ")).start();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("scheduled", true);
        return data;
    }

    /* ==================== 剪贴板 ==================== */

    public void syncClipboard(String text) {
        if (text == null) {
            return;
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new java.awt.datatransfer.StringSelection(text), null);
        client.sendAudit("clip-sync", "len=" + text.length());
    }

    public String readClipboard() {
        try {
            Object content = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return content instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void requireDanger(String action) {
        if (!config.allowDanger()) {
            client.sendAudit("danger-denied", action);
            throw new IllegalStateException("被控端未开启高危系统操作开关: " + action);
        }
    }
}
