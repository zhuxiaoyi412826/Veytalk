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

    private final AgentClient client;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    private volatile int fps = 10;
    private volatile float quality = 0.75f;
    private volatile int monitorIndex = 0;

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
