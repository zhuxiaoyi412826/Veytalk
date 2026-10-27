package com.im.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 头像：微信风方形小圆角（约 10% 圆角，非圆形）。
 * 有 URL 走 Coil（全局 ImageLoader 已带鉴权头），无 URL 用首字符色块兜底。
 */
@Composable
fun Avatar(url: String?, name: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    // 微信风：方形 + 约 10% 小圆角（尺寸自适应，大头像圆角等比放大）
    val shape = RoundedCornerShape(size * 0.10f)
    if (!url.isNullOrEmpty()) {
        AsyncImage(
            model = url,
            contentDescription = name,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * 会话列表/消息时间格式化（对齐 IM 惯例）：
 * 今天 → HH:mm；昨天 → 昨天 HH:mm；今年 → M-d HH:mm；更早 → yyyy-M-d。
 * 后端时间是 ISO_LOCAL_DATE_TIME 字符串（"2026-10-26T15:30:00" 或带毫秒）。
 */
fun formatImTime(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    val t = parseIso(iso) ?: return iso.take(16).replace('T', ' ')
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        today.minusDays(1) -> "昨天 " + t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> if (t.year == now.year) {
            t.format(DateTimeFormatter.ofPattern("M-d HH:mm"))
        } else {
            t.format(DateTimeFormatter.ofPattern("yyyy-M-d"))
        }
    }
}

/** 消息气泡内的时间：今天 HH:mm，否则 M-d HH:mm */
fun formatBubbleTime(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    val t = parseIso(iso) ?: return ""
    val today = LocalDate.now()
    return if (t.toLocalDate() == today) {
        t.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        t.format(DateTimeFormatter.ofPattern("M-d HH:mm"))
    }
}

private fun parseIso(iso: String): LocalDateTime? = runCatching {
    LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}.getOrNull() ?: runCatching {
    // 兼容 "yyyy-MM-dd HH:mm:ss" 形态
    LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrNull()
