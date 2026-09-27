package com.im.remote.agent;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ffmpeg 硬件 H.264 屏幕编码（可选增强路径）。
 *
 * <h2>为什么让 ffmpeg 自己抓屏，而不是 Java 喂帧</h2>
 *
 * <p>1280x720@20fps 的 rawvideo 是每秒 55MB 的管道拷贝——Java 侧 {@code Robot} 抓屏 +
 * 转字节 + 写管道，CPU 全烧在搬运上，编码器反而闲着。{@code gdigrab} 是 ffmpeg 的原生
 * 采集设备，直接从 GDI 拿帧进编码器，Java 只做一件事：读 stdout 解析 Annex-B。
 * 代价是多一个外部可执行文件依赖，所以本类的定位是「有就用、没有就当不存在」，
 * 被控端「单 jar 拷走就能跑」的前提不因它改变。
 *
 * <h2>可用性判定一次到位</h2>
 *
 * <p>非 Windows（没有 gdigrab）、找不到 ffmpeg、编译时没带任何 h264 编码器——
 * 任一条件不满足就 {@link #available()} 返回 false，{@code ScreenCapturer} 全程走原来的
 * JPEG/PNG 分块推流。探测要 spawn 一次 ffmpeg（约 200ms），结果连同失败原因缓存在
 * 静态字段里，整个进程只探一次；反复 screen-start 不能再三 spawn。
 *
 * <h2>编码器顺序与参数</h2>
 *
 * <p>{@code nvenc → qsv → amf → libx264}，与 Electron 侧视频压缩用的是同一套顺序
 * （{@code electron/main.js#detectEncoder}）。nvenc 刻意保留<b>旧式</b>参数串
 * {@code -preset medium -rc vbr -cq N -b:v 0}：新式 p1~p7 预置在 Maxwell(GTX900)/Pascal
 * 老卡的驱动上会被拒绝，而这台机器恰恰最需要硬件编码。同理，编码相关的通用参数只留
 * {@code -pix_fmt/-g/-bf 0}——{@code -refs}、{@code -tune} 之类不是所有编码器都认，
 * ffmpeg 遇到不认的私有选项会直接开不了编码器，宁可少调优也不能起不来。
 */
public final class H264Capturer {

    /** 一帧最多攒这么多字节仍没遇到帧边界，判定码流读飞了，丢弃重同步 */
    private static final int MAX_ACCESS_UNIT = 4 * 1024 * 1024;
    /** stderr 里的进度行刷屏太快（ffmpeg 默认 0.5s 一条），按这个间隔挑着记日志 */
    private static final long PROGRESS_LOG_INTERVAL_MS = 10_000;
    private static final long PROBE_TIMEOUT_MS = 8000;

    /** 探测结果进程级缓存：probed 为真时，ffmpegPath/encoderName 就是最终答案（null 表示不可用） */
    private static volatile boolean probed;
    private static volatile String ffmpegPath;
    private static volatile String encoderName;
    private static volatile String probeFailure = "";

    private final AgentClient client;
    private final AgentConfig config;

    private final AtomicBoolean alive = new AtomicBoolean();
    private volatile Process process;
    private volatile long sid;
    private volatile int outWidth;
    private volatile int outHeight;
    private volatile int screenWidth;
    private volatile int screenHeight;
    /** 读线程看到的最后一次异常/ffmpeg 报错，用于回落时给出人话 */
    private volatile String lastError = "";
    private volatile long lastProgressLogMs;

    public H264Capturer(AgentClient client, AgentConfig config) {
        this.client = client;
        this.config = config;
    }

    /* ==================== 可用性 ==================== */

    /** 本机是否具备 H.264 推流条件（本地开关 + 平台 + ffmpeg + 编码器，四项齐全） */
    public boolean available() {
        if (!config.h264Enabled()) {
            return false;
        }
        probe();
        return encoderName != null;
    }

    /** 探测出来的编码器名，未探测/不可用时为空（随 screen-codec 告诉控制端） */
    public String encoderName() {
        probe();
        return encoderName == null ? "" : encoderName;
    }

    /** 不可用的原因，用于日志与控制端提示 */
    public String unavailableReason() {
        probe();
        return probeFailure;
    }

    private synchronized void probe() {
        if (probed) {
            return;
        }
        try {
            doProbe();
        } catch (Exception e) {
            probeFailure = "探测异常: " + e.getMessage();
            ffmpegPath = null;
            encoderName = null;
        }
        probed = true;
    }

    private void doProbe() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            // 只做了 gdigrab。Linux 的 x11grab / macOS 的 avfoundation 参数完全不同，
            // 且演示环境全在 Windows，硬凑一份没验证过的命令不如明确不支持
            probeFailure = "非 Windows 平台（采集设备 gdigrab 不可用）";
            return;
        }
        String ffmpeg = locateFfmpeg();
        if (ffmpeg == null) {
            probeFailure = "找不到 ffmpeg（可配 ffmpeg.path 或环境变量 IM_FFMPEG_PATH）";
            return;
        }
        String encoders = runQuiet(ffmpeg, "-hide_banner", "-encoders");
        if (encoders == null) {
            probeFailure = "ffmpeg 无法执行: " + ffmpeg;
            return;
        }
        String encoder = pickEncoder(encoders);
        if (encoder == null) {
            probeFailure = "该 ffmpeg 构建里没有可用的 h264 编码器";
            return;
        }
        ffmpegPath = ffmpeg;
        encoderName = encoder;
    }

    /** 优先硬件（nvenc > qsv > amf），全都没有退回 libx264 软编 */
    private static String pickEncoder(String encoderList) {
        if (encoderList.contains("h264_nvenc")) {
            return "h264_nvenc";
        }
        if (encoderList.contains("h264_qsv")) {
            return "h264_qsv";
        }
        if (encoderList.contains("h264_amf")) {
            return "h264_amf";
        }
        return encoderList.contains("libx264") ? "libx264" : null;
    }

    /**
     * ffmpeg 定位：配置项 → 工作目录上级的 ffmpeg 目录（打包态）→ 环境变量 → PATH。
     *
     * <p>最后一档返回裸名 {@code "ffmpeg"}，交给 PATH 解析：真找不到时 spawn 抛
     * IOException，由 {@link #doProbe()} 归入「无法执行」，不需要在这里提前判定。
     */
    private String locateFfmpeg() {
        String configured = config.ffmpegPath();
        if (!configured.isEmpty() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        Path base = Path.of(System.getProperty("user.dir"));
        String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        for (String relative : new String[]{"../ffmpeg/" + exe, "ffmpeg/" + exe, exe}) {
            Path candidate = base.resolve(relative).normalize();
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        String fromEnv = System.getenv("IM_FFMPEG_PATH");
        if (fromEnv != null && !fromEnv.isBlank() && Files.isExecutable(Path.of(fromEnv))) {
            return fromEnv;
        }
        return isWindows() ? null : "ffmpeg";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /* ==================== 生命周期 ==================== */

    public boolean isRunning() {
        return alive.get();
    }

    /**
     * 拉起 ffmpeg 并开始推流。
     *
     * @param maxSide 传输分辨率长边上限，与 JPEG 档同一口径（控制端按此尺寸建画布）
     * @return false 表示起不来，调用方应当回落 JPEG
     */
    public boolean start(long sessionId, int fps, Rectangle bounds, int maxSide) {
        if (!available() || alive.get()) {
            return false;
        }
        this.sid = sessionId;
        this.screenWidth = bounds.width;
        this.screenHeight = bounds.height;
        this.outWidth = even(Math.min(bounds.width, maxSide));
        this.outHeight = even((int) Math.round((double) bounds.height * outWidth / bounds.width));
        this.lastError = "";
        List<String> command = command(fps, bounds);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir")));
            Process proc = builder.start();
            this.process = proc;
            alive.set(true);
            Thread out = new Thread(() -> readStdout(proc), "h264-stdout");
            out.setDaemon(true);
            out.start();
            Thread err = new Thread(() -> readStderr(proc), "h264-stderr");
            err.setDaemon(true);
            err.start();
            client.log("H.264 推流启动: " + encoderName + " " + outWidth + "x" + outHeight
                    + " @" + fps + "fps");
            return true;
        } catch (Exception e) {
            alive.set(false);
            lastError = String.valueOf(e.getMessage());
            client.log("ffmpeg 启动失败: " + lastError);
            return false;
        }
    }

    /** 幂等停止：先好聚好散，超时就强制——常驻的 ffmpeg 不能留成孤儿进程占着 GPU */
    public void stop() {
        alive.set(false);
        Process proc = process;
        this.process = null;
        if (proc == null) {
            return;
        }
        try {
            proc.destroy();
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        } catch (Exception ignored) {
            // 进程已经没了，destroy 抛什么都不影响收尾
        }
    }

    public String lastError() {
        return lastError;
    }

    private List<String> command(int fps, Rectangle bounds) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-hide_banner");
        // info 而不是 warning：进度行（frame= ... bitrate=）是这里唯一的运行观测手段，
        // 由 readStderr 过滤后再决定是否入日志
        cmd.add("-loglevel");
        cmd.add("info");
        cmd.add("-f");
        cmd.add("gdigrab");
        cmd.add("-framerate");
        cmd.add(String.valueOf(Math.max(1, Math.min(fps, 30))));
        cmd.add("-draw_cursor");
        cmd.add("1");
        cmd.add("-offset_x");
        cmd.add(String.valueOf(bounds.x));
        cmd.add("-offset_y");
        cmd.add(String.valueOf(bounds.y));
        cmd.add("-video_width");
        cmd.add(String.valueOf(bounds.width));
        cmd.add("-video_height");
        cmd.add(String.valueOf(bounds.height));
        cmd.add("-i");
        cmd.add("desktop");
        cmd.addAll(encoderArgs(fps));
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        // 尺寸写死为已算好的偶数：4:2:0 要求宽高皆为偶数，交给 ffmpeg 的 -2 自动取整
        // 会让我这边 meta 里的 w/h 和真实码流差 2 像素，画布对不上就是持续错位
        cmd.add("-vf");
        cmd.add("scale=" + outWidth + ":" + outHeight + ":flags=bicubic");
        cmd.add("-g");
        cmd.add(String.valueOf(Math.max(1, Math.min(fps, 30)) * 2));
        // 无 B 帧 = 无重排延迟，控制端拿到一帧就能立刻画一帧
        cmd.add("-bf");
        cmd.add("0");
        cmd.add("-an");
        cmd.add("-sn");
        cmd.add("-f");
        cmd.add("h264");
        cmd.add("pipe:1");
        return cmd;
    }

    /**
     * 各编码器的参数串。硬件档用「质量上限 + 码率封顶」的组合：屏幕内容（大量静止 +
     * 偶发文字变化）按固定码率编要么糊要么浪费，恒定质量更贴合实际带宽。
     */
    private List<String> encoderArgs(int fps) {
        int bitrate = Math.max(200_000, config.h264Bitrate());
        List<String> args = new ArrayList<>();
        switch (encoderName == null ? "" : encoderName) {
            case "h264_nvenc" -> {
                args.add("-c:v");
                args.add("h264_nvenc");
                args.add("-preset");
                args.add("medium");
                args.add("-rc");
                args.add("vbr");
                args.add("-cq");
                args.add("28");
                args.add("-b:v");
                args.add("0");
                args.add("-maxrate");
                args.add(String.valueOf(bitrate));
                args.add("-bufsize");
                args.add(String.valueOf(bitrate * 2L));
            }
            case "h264_qsv" -> {
                args.add("-c:v");
                args.add("h264_qsv");
                args.add("-global_quality");
                args.add("28");
                args.add("-maxrate");
                args.add(String.valueOf(bitrate));
            }
            case "h264_amf" -> {
                args.add("-c:v");
                args.add("h264_amf");
                args.add("-quality");
                args.add("balanced");
                args.add("-maxrate");
                args.add(String.valueOf(bitrate));
            }
            default -> {
                args.add("-c:v");
                args.add("libx264");
                args.add("-preset");
                args.add("veryfast");
                args.add("-tune");
                args.add("zerolatency");
                args.add("-b:v");
                args.add(String.valueOf(bitrate));
                args.add("-maxrate");
                args.add(String.valueOf(bitrate * 3 / 2));
                args.add("-bufsize");
                args.add(String.valueOf(bitrate * 2L));
            }
        }
        // 屏推流不需要 B 帧 lookahead，qsv/amf 的预读缓冲会额外加延迟
        if ("h264_qsv".equals(encoderName)) {
            args.add("-look_ahead");
            args.add("0");
        }
        return args;
    }

    /** 4:2:0 要求宽高皆为偶数，奇数会让 ffmpeg 直接开不了编码器 */
    private static int even(int value) {
        int v = Math.max(2, value);
        return v % 2 == 0 ? v : v - 1;
    }

    /* ==================== 码流读取 ==================== */

    private void readStdout(Process proc) {
        byte[] chunk = new byte[64 * 1024];
        AuSplitter splitter = new AuSplitter();
        try (InputStream in = proc.getInputStream()) {
            int n;
            while (alive.get() && (n = in.read(chunk)) > 0) {
                splitter.feed(chunk, n, this::emit);
            }
        } catch (IOException e) {
            if (alive.get()) {
                lastError = "读取 ffmpeg 输出失败: " + e.getMessage();
            }
        } finally {
            // 半帧没有解码价值，直接丢；下一个 IDR 会让控制端重新对齐
            splitter.discard();
            alive.set(false);
        }
    }

    private void emit(byte[] annexB, boolean key) {
        if (!alive.get() || sid == 0) {
            return;
        }
        if (annexB.length + 64 > client.maxFrameBytes()) {
            // 单帧超限：跳过这一帧（H.264 允许丢非 IDR），并留一条线索，否则现象只是「画面卡住」
            client.log("H.264 帧超限，跳过: " + annexB.length + " > " + client.maxFrameBytes());
            return;
        }
        String meta = MiniJson.write(java.util.Map.of(
                "x", 0, "y", 0,
                "w", outWidth, "h", outHeight,
                "screenW", screenWidth, "screenH", screenHeight,
                "key", key, "full", 1, "fmt", "h264", "codec", "h264"));
        client.sendBinaryFrame(AgentClient.FRAME_SCREEN, sid, meta, annexB);
    }

    private void readStderr(Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("frame=")) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressLogMs >= PROGRESS_LOG_INTERVAL_MS) {
                        lastProgressLogMs = now;
                        client.log("H.264 " + text.replaceAll("\\s+", " "));
                    }
                } else if (text.startsWith("Error") || text.startsWith("error")
                        || text.contains("Invalid") || text.contains("not found")
                        || text.contains("Unknown encoder") || text.contains("failed")) {
                    // 只留最后一条：真正致命的那条一定在最下面，而 ffmpeg 报错后会立刻退出
                    lastError = text;
                }
            }
        } catch (IOException ignored) {
            // 进程被 destroy 时管道必然断开，这是停流的正常路径
        }
    }

    /** 跑一条一次性命令（探测用），超时或失败返回 null */
    private static String runQuiet(String... command) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
            try (InputStream in = proc.getInputStream()) {
                int n;
                while (System.currentTimeMillis() < deadline && (n = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > 2 * 1024 * 1024) {
                        break;
                    }
                }
            }
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                return null;
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
    }

    /* ==================== Annex-B 分帧 ==================== */

    interface AuSink {
        /** 一帧完整访问单元（含其前面的 SPS/PPS/SEI），起始码原样保留 */
        void accept(byte[] accessUnit, boolean key);
    }

    /**
     * 流式把 NAL 拼成访问单元（一帧）。
     *
     * <p>边界判定：遇到「参数集/SEI」或「又一个条带」且当前单元已含条带，就说明上一帧到此为止。
     * 这里对多条带帧（一帧切成好几个 slice NAL）是会误判的，但四个候选编码器在没有显式
     * {@code -slices} 参数时都只出一帧一条带；真出现误判，症状是控制端解码失败，
     * 由前端连续失败三次回落 JPEG 的机制兜住。
     *
     * <p>起始码里的前导 0 归属处理是这类解析器最容易错的地方：一个 NAL 的结尾本身可能就是
     * 好几个 0（CABAC 终止位），所以「攒到的 0」只有在遇到 {@code 01} 且前面至少两个 0 时
     * 才算起始码，否则必须原样退回载荷。
     */
    private static final class AuSplitter {

        private static final int NAL_SLICE = 1;
        private static final int NAL_IDR = 5;
        private static final int NAL_SEI = 6;
        private static final int NAL_SPS = 7;
        private static final int NAL_PPS = 8;

        private final ByteArrayOutputStream acc = new ByteArrayOutputStream(128 * 1024);
        private final List<byte[]> parts = new ArrayList<>(4);
        private int zeroRun;
        private int size;
        private boolean hasSlice;
        private boolean key;

        void feed(byte[] data, int length, AuSink sink) {
            for (int i = 0; i < length; i++) {
                int b = data[i] & 0xFF;
                if (b == 0) {
                    zeroRun++;
                    acc.write(0);
                } else if (b == 1 && zeroRun >= 2) {
                    seal(sink);
                    acc.reset();
                    for (int z = 0; z < zeroRun; z++) {
                        acc.write(0);
                    }
                    acc.write(1);
                    zeroRun = 0;
                } else {
                    zeroRun = 0;
                    acc.write(b);
                }
            }
        }

        private void seal(AuSink sink) {
            byte[] all = acc.toByteArray();
            int length = all.length - zeroRun;
            if (length < 5) {
                return;
            }
            int type = nalType(all, length);
            boolean slice = type == NAL_SLICE || type == NAL_IDR;
            boolean newPictureHint = slice || type == NAL_SPS || type == NAL_PPS || type == NAL_SEI;
            if (newPictureHint && hasSlice) {
                flush(sink);
            }
            byte[] nal = new byte[length];
            System.arraycopy(all, 0, nal, 0, length);
            parts.add(nal);
            size += length;
            if (slice) {
                hasSlice = true;
                if (type == NAL_IDR) {
                    key = true;
                }
            }
            if (size > MAX_ACCESS_UNIT) {
                // 从没遇到过帧边界：多半是码流读飞了（管道错位），丢干净重同步比继续攒强
                discard();
            }
        }

        /** 起始码长度 = 前导 0 的个数 + 1，其后第一字节高 5 位是 nal_unit_type */
        private static int nalType(byte[] nal, int length) {
            int index = 0;
            while (index < length && nal[index] == 0) {
                index++;
            }
            return index + 1 < length ? (nal[index + 1] & 0x1F) : -1;
        }

        private void flush(AuSink sink) {
            if (!hasSlice) {
                return;
            }
            byte[] au = new byte[size];
            int offset = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, au, offset, part.length);
                offset += part.length;
            }
            sink.accept(au, key);
            discard();
        }

        void discard() {
            parts.clear();
            size = 0;
            hasSlice = false;
            key = false;
        }
    }
}
