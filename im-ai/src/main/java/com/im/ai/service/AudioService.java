package com.im.ai.service;

import com.im.ai.config.AiProperties;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试语音链路：ASR（语音转文字）与 TTS（文字合成朗读），SDK-free。
 *
 * <p>与 {@link DashScopeChatClient} 同一个 baseUrl、同一把 ALI_BABA_API_KEY，
 * 只是换模型——ASR 用 qwen3-asr-flash（chat/completions 携带 input_audio，
 * 可非流式），TTS 用 omni 系列（modalities 要 text+audio，官方要求必须流式，
 * 这里聚合完再整段返回，不给前端加second SSE）。都是「一次 POST + 读响应」，
 * 不引任何厂商 SDK，理由与主管线一致：依赖面最小、与 Boot 4 无版本纠缠。
 *
 * <p>前端约定：面试「按住说话」的 ASR 输入是 16k 单声道 16bit WAV（im-ui utils/audioRec.js
 * 自编码），不依赖 MediaRecorder 容器——webm/opus 在国产浏览器上行为不齐，而 WAV 头是
 * 自己写的，模型侧 format 声明永远成立。聊天语音消息走同一个模型，但存的是用户选中的
 * 原始音频（可能是 mp3/m4a…），所以容器名要能传进来，见
 * {@link #transcribe(byte[], String)}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioService {

    /** ASR 输入音频上限：60s 的 16k 单声道 16bit WAV ≈ 1.9MB，4MB 已宽到装不下异常大文件 */
    private static final int MAX_WAV_BYTES = 4 * 1024 * 1024;

    /**
     * DashScope ASR 认的音频容器名。前端自录的一律是 wav，只有用户把本地音频文件
     * 当语音消息发进来时才会命中其它项（转文字时按文件记录的 ext 声明）。
     */
    private static final Set<String> SUPPORTED_ASR_FORMATS =
            Set.of("wav", "mp3", "ogg", "opus", "aac", "amr", "m4a");

    private final AiProperties properties;
    private final JsonUtil jsonUtil;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 前端据此点亮/置灰语音入口；与面试主管线同一把 key，不单独设开关 */
    public boolean available() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * 语音转文字：整段 WAV 进、纯文本出（非流式，一问一答）。
     *
     * <p>面试「按住说话」用的就是这一条：前端编码出来的永远是 16k 单声道 16bit WAV。
     *
     * @param wavBytes 16k 单声道 16bit WAV 原始字节
     * @return 转写文本（已剥掉模型偶尔包裹的 Markdown 围栏）；空音频返回空串
     */
    public String transcribe(byte[] wavBytes) {
        return transcribe(wavBytes, "wav");
    }

    /**
     * 同上，但允许声明音频容器：聊天语音消息存的是用户上传的原始文件（mp3/m4a 都可能），
     * 拿它去转文字时不能再谎称是 WAV。
     *
     * @param format 容器名，取 {@link #SUPPORTED_ASR_FORMATS}；不认识的一律按 wav 送并记一条 warn
     */
    public String transcribe(byte[] audioBytes, String format) {
        requireAvailable();
        if (audioBytes == null || audioBytes.length == 0) {
            // 自定义文案走 (code, message) 构造：枚举文案里没有 {} 占位，传 varargs 会被 format 丢弃
            throw new BusinessException(ResultCode.AI_ASR_FAILED.getCode(), "音频为空，请重新录制");
        }
        if (audioBytes.length > MAX_WAV_BYTES) {
            throw new BusinessException(ResultCode.AI_ASR_FAILED.getCode(), "音频过大（上限 4MB），请缩短录音时长");
        }

        AiProperties.Audio audio = properties.getAudio();
        String audioFormat = normalizeAsrFormat(format);
        String dataUri = "data:;base64," + Base64.getEncoder().encodeToString(audioBytes);

        // ★ content 里只能有 input_audio 这一项，不能再塞 text：qwen3-asr-flash 是 dedicated task
        // 模型，实测多带一条转写指令会被厂商直接拒 —— 400
        // "InternalError.Algo.InvalidParameter: The dedicated task `asr` corresponding to the
        // current service does not support this input."，聊天转文字与面试「按住说话」因此全废。
        // 原先那句「不加指令模型会回答音频内容」的护栏假设是从对话模型（omni）抄来的，
        // 对本模型不成立：它只会转写，不需要也不接受额外指令。
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "input_audio",
                "input_audio", Map.of("data", dataUri, "format", audioFormat)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", audio.getAsrModel());
        body.put("messages", List.of(Map.of("role", "user", "content", content)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(chatCompletionsUri())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .timeout(audio.getAsrTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(jsonUtil.toJson(body)))
                .build();

        String responseBody = send(request, ResultCode.AI_ASR_FAILED, "语音转文字");
        JsonNode node = jsonUtil.mapper().readTree(responseBody);
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            log.warn("ASR 模型返回错误: {}", error.path("message").asText(""));
            throw new BusinessException(ResultCode.AI_ASR_FAILED);
        }
        JsonNode message = node.at("/choices/0/message/content");
        if (message.isMissingNode() || message.isNull()) {
            log.warn("ASR 响应缺少 choices[0].message.content: {}", abbreviate(responseBody));
            throw new BusinessException(ResultCode.AI_ASR_FAILED);
        }
        return stripFences(message.asText());
    }

    /**
     * 容器名收敛：不认识的一律按 wav 声明。
     *
     * <p>不做成错：这里宁可用一个错误的 format 试一次（DashScope 对不匹配的容器最多回一句
     * 模型错误），也不想把一个本来能识别的语音直接拒掉。
     */
    private String normalizeAsrFormat(String format) {
        String name = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return "wav";
        }
        if (SUPPORTED_ASR_FORMATS.contains(name)) {
            return name;
        }
        log.warn("ASR 收到不认识的音频容器 [{}]，改按 wav 声明送给模型", name);
        return "wav";
    }

    /**
     * 文字合成朗读：整段文本进、完整 WAV 字节出。
     *
     * <p>omni 强制流式（官方文档要求 stream=true），这里在服务端把 base64 音频
     * 分片聚合完再返回，前端拿到的始终是一次性的 audio/wav——v1 不做分片透传，
     * 面试回答朗读 2~10 秒、聚合后 <1MB，简单可靠优先。
     */
    public byte[] synthesize(String text) {
        requireAvailable();
        if (text == null || text.isBlank()) {
            throw new BusinessException(ResultCode.AI_TTS_FAILED.getCode(), "朗读内容为空");
        }

        AiProperties.Audio audio = properties.getAudio();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", audio.getTtsModel());
        // 逐字朗读指令：omni 是对话模型，不加约束它会把内容「改写着念」甚至反问
        body.put("messages", List.of(Map.of("role", "user", "content",
                "请逐字朗读下面这段内容，不要增删、改写或添加任何解释，直接开始朗读：\n" + text)));
        body.put("modalities", List.of("text", "audio"));
        body.put("audio", Map.of("voice", audio.getTtsVoice(), "format", "wav"));
        body.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(chatCompletionsUri())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                // HttpRequest 超时只约束到响应头到达；分片总时长靠下面聚合循环里的自查兜底
                .timeout(audio.getTtsTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(jsonUtil.toJson(body)))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            log.warn("TTS 服务请求失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.AI_TTS_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.AI_TTS_FAILED);
        }
        if (response.statusCode() != 200) {
            String detail = response.body().limit(5).reduce("", (a, b) -> a + b);
            log.warn("TTS 服务返回 {}: {}", response.statusCode(), abbreviate(detail));
            throw new BusinessException(ResultCode.AI_TTS_FAILED);
        }

        StringBuilder audioB64 = new StringBuilder();
        long deadline = System.nanoTime() + audio.getTtsTimeout().toNanos();
        try (java.util.stream.Stream<String> lines = response.body()) {
            var it = lines.iterator();
            while (it.hasNext()) {
                if (System.nanoTime() > deadline) {
                    throw new BusinessException(ResultCode.AI_TTS_FAILED.getCode(), "语音合成超时，请缩短朗读内容");
                }
                String line = it.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode node = jsonUtil.mapper().readTree(payload);
                JsonNode error = node.get("error");
                if (error != null && !error.isNull()) {
                    log.warn("TTS 模型返回错误: {}", error.path("message").asText(""));
                    throw new BusinessException(ResultCode.AI_TTS_FAILED);
                }
                JsonNode chunk = node.at("/choices/0/delta/audio/data");
                if (!chunk.isMissingNode() && !chunk.isNull()) {
                    audioB64.append(chunk.asText());
                }
            }
        } finally {
            response.body().close();
        }

        if (audioB64.isEmpty()) {
            log.warn("TTS 流内无音频分片（模型可能只回了文本），检查 {} 是否支持 audio 输出", audio.getTtsModel());
            throw new BusinessException(ResultCode.AI_TTS_FAILED);
        }
        byte[] decoded = Base64.getDecoder().decode(audioB64.toString());
        return ensureWavHeader(decoded);
    }

    // ------------------------------------------------------------------ 内部

    private void requireAvailable() {
        if (!available()) {
            throw new BusinessException(ResultCode.AI_AUDIO_DISABLED);
        }
    }

    private URI chatCompletionsUri() {
        return URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions");
    }

    private String send(HttpRequest request, ResultCode code, String what) {
        String responseBody;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("{} 服务返回 {}: {}", what, response.statusCode(), abbreviate(response.body()));
                throw new BusinessException(code);
            }
            responseBody = response.body();
        } catch (IOException e) {
            log.warn("{} 服务请求失败: {}", what, e.getMessage());
            throw new BusinessException(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(code);
        }
        return responseBody;
    }

    /** 剥掉模型偶尔包裹的 ``` 围栏与首尾空白；只处理外壳，不动正文 */
    private static String stripFences(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                text = text.substring(firstBreak + 1, lastFence).strip();
            }
        }
        return text;
    }

    /**
     * 兜底封 WAV 头：官方示例里分片解码出来是 24k/mono/16bit 裸 PCM，
     * 但 format=wav 时部分版本会直接带 RIFF 头——两种都被这里吸收：
     * 已带头原样透传，裸 PCM 补 44 字节标准头。
     */
    private static byte[] ensureWavHeader(byte[] decoded) {
        if (decoded.length > 4 && decoded[0] == 'R' && decoded[1] == 'I' && decoded[2] == 'F' && decoded[3] == 'F') {
            return decoded;
        }
        int sampleRate = 24000;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        byte[] wav = new byte[44 + decoded.length];
        int i = 0;
        i = writeAscii(wav, i, "RIFF");
        i = writeIntLE(wav, i, 36 + decoded.length);
        i = writeAscii(wav, i, "WAVE");
        i = writeAscii(wav, i, "fmt ");
        i = writeIntLE(wav, i, 16);
        i = writeShortLE(wav, i, (short) 1);
        i = writeShortLE(wav, i, (short) channels);
        i = writeIntLE(wav, i, sampleRate);
        i = writeIntLE(wav, i, byteRate);
        i = writeShortLE(wav, i, (short) (channels * bitsPerSample / 8));
        i = writeShortLE(wav, i, (short) bitsPerSample);
        i = writeAscii(wav, i, "data");
        i = writeIntLE(wav, i, decoded.length);
        System.arraycopy(decoded, 0, wav, i, decoded.length);
        return wav;
    }

    private static int writeAscii(byte[] buf, int pos, String s) {
        for (int j = 0; j < s.length(); j++) {
            buf[pos + j] = (byte) s.charAt(j);
        }
        return pos + s.length();
    }

    private static int writeIntLE(byte[] buf, int pos, int v) {
        buf[pos] = (byte) (v & 0xFF);
        buf[pos + 1] = (byte) ((v >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((v >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((v >> 24) & 0xFF);
        return pos + 4;
    }

    private static int writeShortLE(byte[] buf, int pos, short v) {
        buf[pos] = (byte) (v & 0xFF);
        buf[pos + 1] = (byte) ((v >> 8) & 0xFF);
        return pos + 2;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
