package com.im.common.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 地理位置工具：geohash 编码、两点球面距离、距离模糊化文案。
 *
 * <p>放在 im-common 而不是 im-user：写侧（保存资料、上报定位）在 im-user，
 * 读侧（附近的人、滑动匹配的距离展示）在 im-friend，两边必须用<b>同一套</b>编码与算法，
 * 否则会出现「自己存的 geohash 自己圈不进去」这种查起来最费劲的问题。
 *
 * <p>距离模糊化是<b>展示口径</b>而不是存储口径：{@code im_user_profile.lng/lat} 全精度存
 * （DECIMAL(10,6) 约 0.1 m），服务端算完真实距离再在这里收口，精确坐标绝不下发给陌生人。
 * 直接把坐标给前端，等于把「某人每天几点在哪」交给每一个刷到他的人——这不是隐私开关能补救的。
 */
public final class GeoUtil {

    /** base32 字母表（去掉 i/l/o/u 以避免与 1/0 混淆），标准 geohash 编码表 */
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    /** 6 位约 ±0.6 km，正好是「附近」的粒度；更长的前缀会把人圈到某栋楼，那是跟踪不是找对象 */
    public static final int DEFAULT_PRECISION = 6;

    /** 地球平均半径（m） */
    private static final double EARTH_RADIUS_METERS = 6371000d;

    private GeoUtil() {
    }

    /**
     * 经纬度是否可用。允许为 {@code null}（未授权定位），其余按取值范围与「非零点」判。
     *
     * <p>刻意不把 (0,0) 当成合法值：它是数据库默认值最常见的落点，位于大西洋，
     * 放进「附近的人」候选池会让所有沿海用户都看到一个在非洲湾里的人。
     */
    public static boolean isValid(Double lng, Double lat) {
        if (lng == null || lat == null) {
            return false;
        }
        double lngValue = lng;
        double latValue = lat;
        if (lngValue < -180 || lngValue > 180 || latValue < -90 || latValue > 90) {
            return false;
        }
        return !(lngValue == 0d && latValue == 0d);
    }

    /**
     * 标准 geohash 编码：每个字符 5 位，经度位与纬度位从经度开始逐位交错（3 经 2 纬）。
     *
     * <p>已用公开参照点核对过实现：(116.4074, 39.9042) → {@code wx4g0b}、
     * (121.4737, 31.2304) → {@code wtw3sj}、(120.1551, 30.2741) → {@code wtmknp}。
     * 改动这段位运算必须重新对一遍这三个值，编错的 geohash 不会报错，只会让人永远刷不到附近的人。
     *
     * @param lng       经度
     * @param lat       纬度
     * @param precision 结果长度，越界会被钳到 1~12
     */
    public static String geohash(Double lng, Double lat, int precision) {
        if (!isValid(lng, lat)) {
            return null;
        }
        int length = Math.max(1, Math.min(12, precision));
        double minLat = -90d;
        double maxLat = 90d;
        double minLng = -180d;
        double maxLng = 180d;
        StringBuilder builder = new StringBuilder(length);
        boolean lngBit = true;
        int bit = 0;
        int ch = 0;
        while (builder.length() < length) {
            if (lngBit) {
                double mid = (minLng + maxLng) / 2;
                if (lng >= mid) {
                    ch |= (1 << (4 - bit));
                    minLng = mid;
                } else {
                    maxLng = mid;
                }
            } else {
                double mid = (minLat + maxLat) / 2;
                if (lat >= mid) {
                    ch |= (1 << (4 - bit));
                    minLat = mid;
                } else {
                    maxLat = mid;
                }
            }
            lngBit = !lngBit;
            if (bit < 4) {
                bit++;
                continue;
            }
            builder.append(BASE32.charAt(ch));
            bit = 0;
            ch = 0;
        }
        return builder.toString();
    }

    /** 用默认精度（6 位）编码。 */
    public static String geohash(Double lng, Double lat) {
        return geohash(lng, lat, DEFAULT_PRECISION);
    }

    /**
     * 两点球面距离（haversine），返回米。
     *
     * <p>不用 MySQL 空间函数：候选池由 {@code geohash} 前缀在索引里圈出来，
     * 精确距离在内存里对几十行现算，把计算下推给数据库只是多一次函数调用与更难排查的 SQL。
     */
    public static double distanceMeters(double lng1, double lat1, double lng2, double lat2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double dLat = radLat2 - radLat1;
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 距离模糊化文案：同城社交展示用的唯一出口，四档粗粒度。
     *
     * <ul>
     *   <li>500 m 以内统一「500 m 内」——近到这一档，精确数字等于报出「我们在同一间咖啡店」；</li>
     *   <li>1 km 以内「1 km 内」；</li>
     *   <li>100 km 以内取整公里；</li>
     *   <li>再远就只是「100 km 以上」，此时距离已经没有区分意义。</li>
     * </ul>
     *
     * @return 传入负数（未取到距离）时返回 {@code null}，由调用方决定这一行是否展示
     */
    public static String fuzzyDistance(Double meters) {
        if (meters == null || meters < 0) {
            return null;
        }
        if (meters < 500) {
            return "500 m 内";
        }
        if (meters < 1000) {
            return "1 km 内";
        }
        if (meters < 100000) {
            return Math.round(meters / 1000d) + " km";
        }
        return "100 km 以上";
    }

    /**
     * 先算距离再模糊化，调用方不必自己拼两步——这一步走错（把精确米数丢给前端）是本模块最贵的错误。
     *
     * @return 任一坐标缺失或非法时返回 {@code null}
     */
    public static String fuzzyDistanceBetween(Double myLng, Double myLat, Double otherLng, Double otherLat) {
        if (!isValid(myLng, myLat) || !isValid(otherLng, otherLat)) {
            return null;
        }
        return fuzzyDistance(distanceMeters(myLng, myLat, otherLng, otherLat));
    }

    /**
     * 按当天日期算周岁年龄。
     *
     * <p>年龄不落库：存下来的年龄会在生日那天集体过期，而没有任何任务会去修它。
     */
    public static Integer age(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        if (birthday.isAfter(today)) {
            return null;
        }
        return (int) ChronoUnit.YEARS.between(birthday, today);
    }
}
