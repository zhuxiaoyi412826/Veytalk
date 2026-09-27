package com.im.remote.agent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 增量截屏推流（VNC 思路的纯 JDK 实现）。
 *
 * <p>三档推流模式（控制端 screen-start 的 full 参数选择，默认自适应）：
 * <ul>
 *   <li>{@code MODE_TILE} 省流量：64x64 网格脏块，只发变化的块；</li>
 *   <li>{@code MODE_FULL} 最清晰：画面有变化就发一整屏（静态仍零流量）；</li>
 *   <li>{@code MODE_ADAPTIVE} 自适应：变化面积占比小于 {@link #DIRTY_RATIO_THRESHOLD}
 *       走脏块，超过就发整帧。拖窗口/切屏/放视频这种「全屏都在动」的时刻，
 *       几十个脏块各自带一次编码和帧头，总量往往高过一整帧，而且拼装期间画面是
 *       半新半旧的——这时候整帧既省带宽又观感更好；只改了几行字、动了个光标时
 *       脏块又能省一个数量级，所以按变化面积来回切换是唯一两边都占的选择。</li>
 * </ul>
 *
 * <p>块级编码按内容选格式：平色/文字块走 PNG（DEFLATE 对大片同色 + 锐利边缘的
 * 压缩率远高于 JPEG 的 DCT，还没有振铃伪影，正好治文字发虚），照片/渐变块走 JPEG。
 *
 * <p>推流编码两档：{@code jpeg}（默认）是上面这套纯 JDK 自适应分块；{@code h264} 在控制端
 * 点名要且本机具备条件（Windows + 可用 ffmpeg + 探到编码器）时走 {@link H264Capturer}。
 * 以前这里写着「刻意不做视频编码」，理由是单 jar 部署不能被外部依赖绑住；现在依然成立，
 * 所以 H.264 是纯可选增益：缺任一条件就静默回到 JPEG，实际用哪个编码由
 * {@code screen-codec} 帧回执给控制端，不靠双方各自假设。
 */
public class ScreenCapturer {

    /** 推流编码：纯 JDK 自适应 JPEG/PNG 分块（永远可用）/ ffmpeg 硬件 H.264（有条件才用） */
    public static final String CODEC_JPEG = "jpeg";
    public static final String CODEC_H264 = "h264";

    /** 脏块网格边长（像素） */
    private static final int BLOCK = 64;
    /** 纯脏块模式下全屏关键帧间隔（毫秒）：控制端丢块/重连后的自愈兜底 */
    private static final long KEYFRAME_INTERVAL_MS = 5000;
    /**
     * 自适应模式的关键帧间隔（毫秒）：比脏块档放宽一倍。
     * 自适应在「变化不大全程脏块」时本来就不会花屏（脏块只覆盖真变化的区域），
     * 周期性整帧纯粹是给丢块上保险；公网按 10s 一次足够，省下的都是白花花的带宽。
     */
    private static final long ADAPTIVE_KEYFRAME_MS = 10000;
    /** 变化块占比超过这个线就改发整帧（详见类注释的自适应说明） */
    private static final float DIRTY_RATIO_THRESHOLD = 0.30f;
    /** 块内采样颜色数超过这个值就判定为照片类内容，退回 JPEG */
    private static final int FLAT_MAX_COLORS = 24;
    /**
     * 全帧/脏块统一的传输分辨率长边上限：超过则等比缩小。
     * 纯 JDK 的 JPEG 编码是这里最贵的一步，1080p 整屏一帧要几十毫秒；压到 1280 以内
     * 编码更快、体积更小（同一画面 1280 比 1600 少约 36% 像素），控制在 canvas 按此
     * 尺寸建立再由 CSS 铺满，手机屏幕上清晰度足够操控。
     */
    private static final int MAX_TRANSMIT_SIDE = 1280;
    /** 推流模式：纯脏块 / 纯整帧 / 自适应（三档由控制端 full 参数指定） */
    public static final int MODE_TILE = 0;
    public static final int MODE_FULL = 1;
    public static final int MODE_ADAPTIVE = 2;

    private final AgentClient client;
    private final H264Capturer h264;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;
    /** 控制端在 screen-start 里要的编码，实际用哪个由 {@link #currentCodec()} 说话 */
    private volatile String wantedCodec = CODEC_JPEG;
    /** 本会话内 H.264 已判不可用/已中断：不再重试。反复重启 ffmpeg 只会让画面反复黑一下 */
    private volatile boolean h264Degraded;
    /** 推流途中改了 fps/显示器：H.264 的这两个参数写在命令行里，置位后由推流线程重启进程 */
    private volatile boolean h264Restart;

    private volatile int fps = 10;
    private volatile float quality = 0.75f;
    private volatile int monitorIndex = 0;
    /**
     * 当前推流模式，取值见 {@link #MODE_TILE}/{@link #MODE_FULL}/{@link #MODE_ADAPTIVE}。
     * 默认自适应：既拿回脏块的带宽优势，又不让大变化时出现「刷好几下才拼齐」。
     */
    private volatile int pushMode = MODE_ADAPTIVE;
    /**
     * 编码复用 writer：ImageIO 每次 createWriter 都要走 SPI 查找，20fps 下每帧几十个块
     * 就是上百次查找，白捡的开销。只在截屏线程内使用（captureLoop 单线程），
     * dispose 也在同一线程的 finally 里做，不跨线程也就不存在并发问题。
     */
    private ImageWriter jpegWriter;
    private ImageWriter pngWriter;

    public ScreenCapturer(AgentClient client, AgentConfig config) {
        this.client = client;
        this.h264 = new H264Capturer(client, config);
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 当前推流显示器的边界，输入坐标映射也依赖它 */
    public synchronized Rectangle currentBounds() {
        Rectangle[] monitors = monitors();
        int index = Math.min(Math.max(monitorIndex, 0), monitors.length - 1);
        return monitors[index];
    }

    public static Rectangle[] monitors() {
        return java.util.Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getScreenDevices())
                .map(device -> device.getDefaultConfiguration().getBounds())
                .toArray(Rectangle[]::new);
    }

    public void configure(int fps, int qualityPercent, Integer monitor) {
        int previousFps = this.fps;
        int previousMonitor = this.monitorIndex;
        if (fps > 0) {
            this.fps = Math.min(fps, 30);
        }
        if (qualityPercent > 0) {
            this.quality = Math.min(Math.max(qualityPercent / 100f, 0.2f), 0.95f);
        }
        if (monitor != null) {
            this.monitorIndex = monitor;
        }
        // 参数变更强制下一帧发关键帧，避免新旧参数混用的花屏
        this.lastKeyframeMs = 0;
        // quality 不参与重启：H.264 档的码率由 h264.bitrate 决定，拉 JPEG 质量滑块对它无意义
        if (h264.isRunning() && (this.fps != previousFps || this.monitorIndex != previousMonitor)) {
            this.h264Restart = true;
        }
    }

    /** 切换推流模式；未知取值一律归到自适应，避免旧版控制端传来 0/1 之外的值时行为漂移 */
    public void setPushMode(int mode) {
        int next = switch (mode) {
            case MODE_TILE, MODE_FULL, MODE_ADAPTIVE -> mode;
            default -> MODE_ADAPTIVE;
        };
        if (next != this.pushMode) {
            this.pushMode = next;
            // 模式切换后上一帧的比较基准不再成立，强制下一帧重发完整画面
            this.lastKeyframeMs = 0;
        }
    }

    /** 兼容旧签名：true=整帧 false=脏块，其余值走自适应 */
    public void setFullFrame(boolean full) {
        setPushMode(full ? MODE_FULL : MODE_TILE);
    }

    /**
     * 选择推流编码。控制端可以要 h264，但拿到的不一定是 h264：条件不齐时本端仍发 JPEG，
     * 真实结果由 {@code screen-codec} 帧回执，控制端据此决定建不建 VideoDecoder。
     */
    public void setCodec(String codec) {
        String next = CODEC_H264.equalsIgnoreCase(codec) ? CODEC_H264 : CODEC_JPEG;
        if (next.equals(this.wantedCodec)) {
            return;
        }
        this.wantedCodec = next;
        // 改档视为一次新请求，给 H.264 重新试一次的机会（上一轮可能只是 ffmpeg 还没装好）
        this.h264Degraded = false;
        if (!running.get()) {
            return;
        }
        long session = client.currentSid();
        if (session == 0) {
            return;
        }
        /*
         * 推流途中改档只能重开线程：JPEG 是「自己抓屏做差分块」，H.264 是「读 ffmpeg 管道分帧」，
         * 两套状态（previous 基准帧 / NAL 组包器）放进同一个循环里热切换，换来的只是互相踩。
         */
        stop();
        start(session, this.fps, (int) (this.quality * 100), this.monitorIndex);
        client.log("推流编码切换为: " + next);
    }

    /** 当前实际推流编码，供 UI 与日志显示 */
    public String currentCodec() {
        return h264.isRunning() ? CODEC_H264 : CODEC_JPEG;
    }

    /** 把实际编码回执给控制端（永远走中继，它属于控制面） */
    private void reportCodec(long sid, String codec, String info) {
        client.sendEnvelope("screen-codec", sid,
                Map.of("codec", codec, "info", info == null ? "" : info, "fps", fps));
    }

    /** 开始推流；已在推流时只更新参数 */
    public void start(long sid, int fps, int qualityPercent, int monitor) {
        configure(fps, qualityPercent, monitor);
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> captureLoop(sid), "screen-capturer");
        worker.setDaemon(true);
        worker.start();
        client.log("屏幕推流已开启: codec=" + wantedCodec + ", fps=" + this.fps + ", quality=" + qualityPercent);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        // 不顺手停掉 ffmpeg，它会一直编到下一个会话把 GPU 占着；放这里是因为 stop 是幂等的
        h264.stop();
        client.log("屏幕推流已停止");
    }

    /**
     * 推流线程入口：先试 H.264（只在条件齐备时真跑），不可用/中途断了就接上原有 JPEG 循环。
     *
     * <p>写成「先试再落」而不是给两档各开一个线程，是为了让回落发生在同一个线程上——
     * JPEG 循环的 previous 基准帧、复用的 writer 都是单线程假设，多一个线程就得加锁。
     */
    private void captureLoop(long sid) {
        if (h264Session(sid)) {
            return;
        }
        jpegLoop(sid);
    }

    /**
     * H.264 推流阶段。
     *
     * @return true 表示本轮推流到此为止（会话结束或被停），false 表示需要接上 JPEG 循环
     */
    private boolean h264Session(long sid) {
        if (!CODEC_H264.equals(wantedCodec) || h264Degraded) {
            return false;
        }
        if (!h264.available()) {
            h264Degraded = true;
            client.log("H.264 不可用，改走 JPEG 推流: " + h264.unavailableReason());
            reportCodec(sid, CODEC_JPEG, h264.unavailableReason());
            return false;
        }
        reportCodec(sid, CODEC_H264, h264.encoderName());
        while (running.get() && client.currentSid() == sid) {
            h264Restart = false;
            if (!h264.start(sid, fps, currentBounds(), MAX_TRANSMIT_SIDE)) {
                h264Degraded = true;
                reportCodec(sid, CODEC_JPEG, h264.lastError());
                return false;
            }
            while (!h264Restart && h264.isRunning() && running.get() && client.currentSid() == sid) {
                sleep(80);
            }
            h264.stop();
            if (!running.get() || client.currentSid() != sid) {
                return true;
            }
            if (!h264Restart) {
                // 没收到改档请求却退出了，就是 ffmpeg 自己挂了（驱动重置、锁屏、被占用）。
                // 本会话不再重试：反复拉进程只会让画面反复黑，而 JPEG 档是稳定能跑的
                h264Degraded = true;
                client.log("H.264 编码中断，回落 JPEG: " + h264.lastError());
                reportCodec(sid, CODEC_JPEG, h264.lastError());
                return false;
            }
            client.log("H.264 按新参数重启编码进程");
        }
        return true;
    }

    private void jpegLoop(long sid) {
        BufferedImage previous = null;
        this.lastKeyframeMs = 0;
        try {
            while (running.get() && client.currentSid() == sid) {
                long frameStart = System.currentTimeMillis();
                try {
                    BufferedImage captured = captureRegion(currentBounds());
                    if (captured == null) {
                        sleep(200);
                        continue;
                    }
                    // 整帧与脏块统一在「传输分辨率」空间里比较和编码：两者共用同一坐标系，
                    // 自适应模式下来回切换才不会把脏块画到错位的位置上
                    BufferedImage frame = toRgb(scaleDown(captured, MAX_TRANSMIT_SIDE));
                    boolean resized = previous == null
                            || previous.getWidth() != frame.getWidth()
                            || previous.getHeight() != frame.getHeight();
                    long keyInterval = pushMode == MODE_ADAPTIVE ? ADAPTIVE_KEYFRAME_MS : KEYFRAME_INTERVAL_MS;
                    boolean delivered;
                    if (resized) {
                        // 切显示器/改分辨率：上一帧的比较基准已无意义，先给一帧完整画面
                        delivered = sendKeyFrame(sid, frame);
                    } else if (pushMode == MODE_FULL) {
                        // 最清晰档：有变化就发一整帧，静态画面仍然零流量
                        delivered = !frameChanged(previous, frame) || sendKeyFrame(sid, frame);
                    } else if (frameStart - lastKeyframeMs > keyInterval) {
                        // 周期性关键帧：控制端丢块/重连后靠它自愈，不能省
                        delivered = sendKeyFrame(sid, frame);
                    } else {
                        Dirty dirty = dirtyBlocks(previous, frame);
                        if (dirty.blocks().isEmpty()) {
                            delivered = true;
                        } else if (pushMode == MODE_TILE || dirty.ratio() >= DIRTY_RATIO_THRESHOLD) {
                            delivered = sendKeyFrame(sid, frame);
                        } else {
                            sendBlocks(sid, frame, dirty.blocks());
                            delivered = true;
                        }
                    }
                    // 只有真发出去的帧才能当下一帧的比较基准：整帧因超限被跳过时，如果照样推进 previous，
                    // 后续脏块就是「拿控制端从没见过的画面做差」，那块区域会一直错位
                    if (delivered) {
                        previous = frame;
                    }
                } catch (Exception e) {
                    if (!running.get()) {
                        return;
                    }
                    client.log("截帧异常: " + e.getMessage());
                    sleep(500);
                }
                long cost = System.currentTimeMillis() - frameStart;
                sleep(Math.max(5, 1000L / Math.max(fps, 1) - cost));
            }
        } finally {
            releaseWriters();
        }
        running.set(false);
    }

    /**
     * 发一整屏关键帧（full=1）：控制端据此一次刷出完整画面，同时充当其后脏块的合成底图。
     * 元数据的 w/h/screenW/screenH 全部等于传输帧尺寸，控制端据此建画布并 1:1 铺满。
     * 超单帧上限时只降质量、不缩尺寸，保证永远铺满整屏。
     *
     * @return 是否真的发了；降到底仍超限时返回 false，调用方据此不推进比较基准
     */
    private boolean sendKeyFrame(long sid, BufferedImage frame) {
        frame = toRgb(frame);
        byte[] jpeg = toJpeg(frame, quality);
        float q = quality;
        while (jpeg.length + 64 > client.maxFrameBytes() && q > 0.3f) {
            q -= 0.15f;
            jpeg = toJpeg(frame, q);
        }
        if (jpeg.length + 64 > client.maxFrameBytes()) {
            client.log("全屏帧超限，跳过: " + jpeg.length);
            return false;
        }
        int w = frame.getWidth();
        int h = frame.getHeight();
        // full=1 告诉控制端「这是一整屏完整帧」：走最新帧优先的单路解码，
        // 并把此前排队、尚未画完的脏块一律作废，避免旧块覆盖在新帧之上
        String meta = MiniJson.write(Map.of(
                "x", 0, "y", 0,
                "w", w, "h", h,
                "screenW", w, "screenH", h,
                "key", true, "full", 1, "fmt", "jpeg"));
        client.sendBinaryFrame(AgentClient.FRAME_SCREEN, sid, meta, jpeg);
        this.lastKeyframeMs = System.currentTimeMillis();
        return true;
    }

    /** 等比缩小到长边不超过 maxSide；本就不超则原样返回（避免无谓拷贝） */
    private BufferedImage scaleDown(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxSide) {
            return src;
        }
        double ratio = (double) maxSide / longest;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** 整屏粗采样比对（每 16px 一点）：完全没变化就不发帧，静态画面零流量 */
    private boolean frameChanged(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return true;
        }
        int w = a.getWidth();
        int h = a.getHeight();
        for (int y = 0; y < h; y += 16) {
            for (int x = 0; x < w; x += 16) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 变化块集合及其在整屏网格中所占比例，供自适应模式决定走脏块还是整帧 */
    private record Dirty(List<int[]> blocks, int total) {
        float ratio() {
            return total == 0 ? 1f : (float) blocks.size() / total;
        }
    }

    /** 逐块比对收集变化块（坐标均处于传输分辨率空间，与关键帧一致） */
    private Dirty dirtyBlocks(BufferedImage previous, BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        List<int[]> blocks = new ArrayList<>();
        int total = 0;
        for (int by = 0; by < height; by += BLOCK) {
            for (int bx = 0; bx < width; bx += BLOCK) {
                int bw = Math.min(BLOCK, width - bx);
                int bh = Math.min(BLOCK, height - by);
                total++;
                if (blockChanged(previous, frame, bx, by, bw, bh)) {
                    blocks.add(new int[]{bx, by, bw, bh});
                }
            }
        }
        return new Dirty(blocks, total);
    }

    /** 发一组变化块：每块按内容选 PNG 或 JPEG，块坐标随元数据交给控制端 */
    private void sendBlocks(long sid, BufferedImage frame, List<int[]> blocks) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        for (int[] block : blocks) {
            int bx = block[0];
            int by = block[1];
            BufferedImage sub = toRgb(frame.getSubimage(bx, by, block[2], block[3]));
            boolean flat = isFlatBlock(sub);
            byte[] encoded = flat ? toPng(sub) : toJpeg(sub, quality);
            if (encoded.length == 0 || encoded.length + 96 > client.maxFrameBytes()) {
                // 单块超限（只可能是照片块还碰上极低质量）：跳过，周期性关键帧会补上画面
                continue;
            }
            String meta = MiniJson.write(Map.of(
                    "x", bx, "y", by,
                    "w", sub.getWidth(), "h", sub.getHeight(),
                    "screenW", width, "screenH", height,
                    "key", false, "full", 0, "fmt", flat ? "png" : "jpeg"));
            client.sendBinaryFrame(AgentClient.FRAME_SCREEN, sid, meta, encoded);
        }
    }

    /**
     * 平色/文字块判定：采样统计不同颜色数，超阈值就归为照片/渐变类内容。
     * 平色块走 PNG——DEFLATE 对「一大片同色 + 锐利边缘」的压缩率远高于 JPEG 的 DCT，
     * 而且没有振铃伪影；64x64 的 PNG 编码耗时微秒级，不抢帧率。
     */
    private boolean isFlatBlock(BufferedImage block) {
        Set<Integer> colors = new HashSet<>(64);
        for (int y = 0; y < block.getHeight(); y += 4) {
            for (int x = 0; x < block.getWidth(); x += 4) {
                if (colors.add(block.getRGB(x, y) & 0xFFFFFF) && colors.size() > FLAT_MAX_COLORS) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 块内抽样比对（每 8px 一个采样点）：全像素比对在高分屏上不划算 */
    private boolean blockChanged(BufferedImage a, BufferedImage b, int x, int y, int w, int h) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return true;
        }
        for (int py = y; py < y + h; py += 8) {
            for (int px = x; px < x + w; px += 8) {
                if (a.getRGB(px, py) != b.getRGB(px, py)) {
                    return true;
                }
            }
        }
        // 边缘再验一次，避免 8px 采样恰好错开细线变化
        int lastX = x + w - 1;
        int lastY = y + h - 1;
        return a.getRGB(lastX, y) != b.getRGB(lastX, y)
                || a.getRGB(x, lastY) != b.getRGB(x, lastY)
                || a.getRGB(lastX, lastY) != b.getRGB(lastX, lastY);
    }

    private BufferedImage captureRegion(Rectangle bounds) {
        try {
            return new java.awt.Robot().createScreenCapture(bounds);
        } catch (Exception e) {
            // 远程桌面会话/锁屏状态下 Robot 抓图可能失败，返回 null 由主循环退避重试
            return null;
        }
    }

    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private byte[] toJpeg(BufferedImage image, float qualityValue) {
        return writeImage(writerFor("jpeg"), image, qualityValue, "jpeg");
    }

    /** PNG 无损、不接受质量参数（传 -1 让 writeImage 跳过质量设置） */
    private byte[] toPng(BufferedImage image) {
        return writeImage(writerFor("png"), image, -1f, "png");
    }

    /** 懒建并复用 writer：SPI 查找只做一次，仅在截屏线程内独占使用 */
    private ImageWriter writerFor(String formatName) {
        try {
            if ("jpeg".equals(formatName)) {
                if (jpegWriter == null) {
                    jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next();
                }
                return jpegWriter;
            }
            if (pngWriter == null) {
                pngWriter = ImageIO.getImageWritersByFormatName("png").next();
            }
            return pngWriter;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] writeImage(ImageWriter writer, BufferedImage image, float qualityValue, String formatName) {
        if (writer == null || image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(buffer)) {
            // reset() 会连输出一起清掉，所以必须 reset 在前、setOutput 在后
            writer.reset();
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("jpeg".equals(formatName) && param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(qualityValue);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            // 必须在关流前 flush，否则尾部数据可尚留在 MemoryCacheImageOutputStream 的缓冲里
            out.flush();
            return buffer.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 截屏线程退出时释放 writer（与使用同线程，不存在竞态） */
    private void releaseWriters() {
        if (jpegWriter != null) {
            jpegWriter.dispose();
            jpegWriter = null;
        }
        if (pngWriter != null) {
            pngWriter.dispose();
            pngWriter = null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private volatile long lastKeyframeMs;
}
