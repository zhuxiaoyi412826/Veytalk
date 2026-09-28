package com.im.common.log;

import ch.qos.logback.core.rolling.RollingFileAppender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动时先把活动日志文件清空的 {@code RollingFileAppender}。
 *
 * <p>存在的唯一理由是 logback 的一条硬编码限制：{@code RollingFileAppender.start()} 无条件把
 * {@code append} 改回 true 并打出
 * {@code "Append mode is mandatory for RollingFileAppender. Defaulting to append=true."}，
 * 跟有没有写 {@code <file>} 无关（实测与字节码都确认过）。所以光在 XML 里写
 * {@code <append>false</append>} 表达不了「这个文件只装本次运行的内容」。
 *
 * <p>另外两条路都不划算：换成非滚动的 {@code FileAppender} 确实认 append=false，
 * 但就没有 {@code maxFileSize / maxHistory / totalSizeCap} 三道护栏了，而控制台镜像恰恰是
 * 体积最大的一份文件；改成「每次启动写一个新文件名」则会让目录里堆满历代文件、也没有固定名字可读。
 * 于是只能在这里补一小段。
 *
 * <p>做法：仍然用 XML 里的 {@code append=false} 作为「本次运行独占此文件」的信号，
 * 启动时先按这个信号删掉活动文件，再把 append 置回 true，让父类走正常流程——
 * 文件已不存在，创建出来自然就是空的。
 *
 * <p>为什么是删除而不是截断：同机多实例共用一个日志目录时，另一个实例正持有这个文件，
 * Windows 上删除会直接失败并在这里报一条 status 警告。失败反而是对的——宁可保住别人的现场，
 * 也不要把两次运行的日志搅在一起；而截断会静默吃掉另一个实例已经写进去的内容。
 */
public class TruncatingRollingFileAppender<E> extends RollingFileAppender<E> {

    @Override
    public void start() {
        if (!isAppend()) {
            clearActiveFile();
            // 已经手动清过了，这里置回 true 只是让父类不再走那条「append 被强制改成 true」的分支
            setAppend(true);
        }
        super.start();
    }

    private void clearActiveFile() {
        String path = getFile();
        if (path == null || path.isBlank()) {
            addWarn("未配置 <file>，无法在启动时清空活动文件，本次运行会继续追加");
            return;
        }
        try {
            if (Files.deleteIfExists(Path.of(path))) {
                addInfo("启动时已清空 " + path);
            }
        } catch (IOException e) {
            addWarn("启动时清空 " + path + " 失败（通常是另一个实例正占用它）：" + e.getMessage());
        }
    }
}
