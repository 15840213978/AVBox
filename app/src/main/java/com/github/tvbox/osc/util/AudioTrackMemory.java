package com.github.tvbox.osc.util;

import android.util.Pair;

/**
 * 音轨记忆(2026-09-15 由独立 SharedPreferences `audio_track_prefs` 迁入全局 KV/MMKV)。
 *
 * <p>键按剧动态生成(见 {@link #KEY_PREFIX}),值恒为 int;动态键无法逐键登记 KVKeySpec,
 * 但调用侧一律带具体默认值(-1),KV 按默认值类型还原即可,无需登记。
 *
 * <p>无状态工具类:迁移后不再需要 Context 与单例(原 SharedPreferences 实例的持有者已删除)。
 */
public final class AudioTrackMemory {

    /** 动态键族前缀:替代旧 SP 文件名,避免与其它 KV 键撞名 */
    private static final String KEY_PREFIX = "audio_track_";
    private static final String KEY_GROUP_SUFFIX = "_group";
    private static final String KEY_TRACK_SUFFIX = "_track";

    private AudioTrackMemory() {
    }

    public static void save(String playKey, int groupIndex, int trackIndex) {
        LOG.i("echo-AudioTrackMemory save playKey:" + playKey);
        String key = KEY_PREFIX + playKey + "_exo";
        KV.put(key + KEY_GROUP_SUFFIX, groupIndex);
        KV.put(key + KEY_TRACK_SUFFIX, trackIndex);
    }

    public static void save(String playKey, int trackIndex) {
        LOG.i("echo-AudioTrackMemory save playKey:" + playKey);
        KV.put(KEY_PREFIX + playKey + "_ijk" + KEY_TRACK_SUFFIX, trackIndex);
    }

    public static Pair<Integer, Integer> exoLoad(String playKey) {
        String key = KEY_PREFIX + playKey + "_exo";
        int group = KV.get(key + KEY_GROUP_SUFFIX, -1);
        int track = KV.get(key + KEY_TRACK_SUFFIX, -1);
        if (group >= 0 && track >= 0) {
            return Pair.create(group, track);
        }
        return null;
    }

    public static Integer ijkLoad(String playKey) {
        return KV.get(KEY_PREFIX + playKey + "_ijk" + KEY_TRACK_SUFFIX, -1);
    }
}
