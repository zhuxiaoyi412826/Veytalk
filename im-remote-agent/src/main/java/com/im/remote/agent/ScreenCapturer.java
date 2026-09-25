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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 增量截屏推流（VNC 思路的纯 JDK 实现）。
 *
 * <p>64x64 网格脏块检测：整屏抓取后与上一帧逐块比对（块内抽样像素），
 * 只有变化的块 JPEG 编码发送；每 5 秒或参数变更时发一帧全屏关键帧，
 * 保证控制端在丢块 / 重连后能自愈，不会永久花屏。
 *
 * <p>刻意不做视频编码：H.264 需要引入 ffmpeg/编码器依赖，与被控端
 * 「单 jar 拷走就能跑」的前提冲突；办公场景 10~20fps 的 JPEG 分块够用。
 */
public class ScreenCapturer {

    /** 脏块网格边长（像素） */
    private static final int BLOCK = 64;
    /** 全屏关键帧间隔（毫秒） */
    private static final long KEYFRAME_INTERVAL_MS = 5000;
    /**
     * 全帧模式传输分辨率长边上限：超过则等比缩小。
     * 纯 JDK 的 JPEG 编码是这里最贵的一步，1080p 整屏一帧要几十毫秒；先把长边压到
     * 1600 以内，编码更快、体积更小，才能在 LAN 上跑到 15~25fps 的「整屏完整刷新」。
     * 前端 canvas 会按此尺寸建、再 CSS 缩放铺满手机屏，清晰度足够操控。
     */
    private static final int MAX_TRANSMIT_SIDE = 1600;

    private final AgentClient client;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    private volatile int fps = 10;
    private volatile float quality = 0.75f;
    private volatile int monitorIndex = 0;
    /**
     * true=全帧模式（默认）：画面有变化就发一整屏完整帧，控制端每帧都是完整画面，
     * 不会「刷好几下才拼齐」。false=旧的 64x64 脏块增量模式（省带宽，但一次大变化要
     * 多帧才收敛，观感割裂）。由控制端 screen-start 的 full 参数切换。
     */
    private volatile boolean fullFrameMode = true;

    public ScreenCapturer(AgentClient client) {
        this.client = client;
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
    }

    /** 切换全帧/脏块推流模式；切换后强制下一帧重发完整画面 */
    public void setFullFrame(boolean full) {
        if (full != this.fullFrameMode) {
            this.fullFrameMode = full;
            this.lastKeyframeMs = 0;
        }
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
        client.log("屏幕推流已开启: fps=" + this.fps + ", quality=" + qualityPercent);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        client.log("屏幕推流已停止");
    }

    private void captureLoop(long sid) {
        BufferedImage previous = null;
        this.lastKeyframeMs = 0;
        while (running.get() && client.currentSid() == sid) {
            long frameStart = System.currentTimeMillis();
            try {
                Rectangle bounds = currentBounds();
                BufferedImage frame = captureRegion(bounds);
                if (frame == null) {
                    sleep(200);
                    continue;
                }
                if (fullFrameMode) {
                    // 整屏推流：先等比缩小到传输分辨率，画面有变化才发一整帧（静态画面零流量）。
                    // 每一帧都是完整画面，控制端一次就能刷出完整图像，不存在“拼块”过程。
                    BufferedImage scaled = scaleDown(frame, MAX_TRANSMIT_SIDE);
                    if (previous == null || frameChanged(previous, scaled)) {
                        sendScaledFrame(sid, scaled);
                        previous = scaled;
                    }
                } else {
                    boolean keyRequired = frame.getWidth() != (previous == null ? -1 : previous.getWidth())
                            || frame.getHeight() != (previous == null ? -1 : previous.getHeight())
                            || frameStart - lastKeyframeMs > KEYFRAME_INTERVAL_MS;
                    if (keyRequired) {
                        sendFullFrame(sid, frame, bounds);
                        previous = copy(frame);
                    } else {
                        sendDirtyBlocks(sid, previous, frame, bounds);
                        previous = frame;
                    }
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
        running.set(false);
    }

    /**
     * 全帧模式发送：整屏（可能已等比缩小）编成一张 JPEG 发出，元数据的
     * w/h/screenW/screenH 全部等于传输帧尺寸，控制端据此建画布并 1:1 铺满——
     * 这样「缩小后的帧只覆盖画布一部分、要几帧才拼齐」的割裂问题从根上消失。
     * 超单帧上限时只降质量、不缩尺寸，保证永远铺满整屏。
     */
    private void sendScaledFrame(long sid, BufferedImage frame) {
        frame = toRgb(frame);
        byte[] jpeg = toJpeg(frame);
        float q = quality;
        while (jpeg.length + 64 > client.maxFrameBytes() && q > 0.3f) {
            q -= 0.15f;
            jpeg = toJpeg(frame, q);
        }
        if (jpeg.length + 64 > client.maxFrameBytes()) {
            client.log("全屏帧超限，跳过: " + jpeg.length);
            return;
        }
        int w = frame.getWidth();
        int h = frame.getHeight();
        // full=1 明确告诉控制端「这是一整屏完整帧」，走最新帧优先的直绘路径，
        // 与脏块模式的关键帧(key=true 但 full 缺省)区分开，避免两套渲染逻辑互相干扰。
        String meta = MiniJson.write(Map.of(
                "x", 0, "y", 0,
                "w", w, "h", h,
                "screenW", w, "screenH", h,
                "key", true, "full", 1));
        client.sendBinaryFrame(AgentClient.FRAME_SCREEN, sid, meta, jpeg);
        this.lastKeyframeMs = System.currentTimeMillis();
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

    private void sendFullFrame(long sid, BufferedImage frame, Rectangle bounds) {
        byte[] jpeg = toJpeg(frame);
        // 整屏帧超过单帧上限时按更低质量重编，保证关键帧总能发出
        int retryQuality = (int) (quality * 100) - 15;
        while (jpeg.length + 64 > client.maxFrameBytes() && retryQuality >= 30) {
            frame = resizeForQuality(frame);
            jpeg = toJpeg(frame, retryQuality / 100f);
            retryQuality -= 15;
        }
        if (jpeg.length + 64 > client.maxFrameBytes()) {
            client.log("全屏关键帧超限，跳过: " + jpeg.length);
            return;
        }
        String meta = MiniJson.write(Map.of(
                "x", 0, "y", 0,
                "w", frame.getWidth(), "h", frame.getHeight(),
                "screenW", bounds.width, "screenH", bounds.height,
                "key", true));
        client.sendBinaryFrame(AgentClient.FRAME_SCREEN, sid, meta, jpeg);
        this.lastKeyframeMs = System.currentTimeMillis();
    }

    /** 关键帧缩到 3/4 尺寸，配合降质量把体积压回上限内 */
    private BufferedImage resizeForQuality(BufferedImage src) {
        int w = src.getWidth() * 3 / 4;
        int h = src.getHeight() * 3 / 4;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private void sendDirtyBlocks(long sid, BufferedImage previous, BufferedImage frame, Rectangle bounds) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        for (int by = 0; by < height; by += BLOCK) {
            for (int bx = 0; bx < width; bx += BLOCK) {
                int bw = Math.min(BLOCK, width - bx);
                int bh = Math.min(BLOCK, height - by);
                if (previous != null && !blockChanged(previous, frame, bx, by, bw, bh)) {
                    continue;
                }
                BufferedImage block = frame.getSubimage(bx, by, bw, bh);
                byte[] jpeg = toJpeg(toRgb(block));
                String meta = MiniJson.write(Map.of(
                        "x", bx, "y", by,
                        "w", bw, "h", bh,
                        "screenW", width, "screenH", height,
                        "key", false));
                client.sendBinaryFrame(AgentClient.FRAME_SCREEN, sid, meta, jpeg);
            }
        }
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

    private BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private byte[] toJpeg(BufferedImage image) {
        return toJpeg(image, quality);
    }

    private byte[] toJpeg(BufferedImage image, float qualityValue) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(qualityValue);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(buffer)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            return new byte[0];
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
