package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.player.ExoMediaPlayerFactory;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.player.thirdparty.VlcPlayer;
import com.github.tvbox.osc.util.KV;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import tv.danmaku.ijk.media.player.IjkLibLoader;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        updateCfg(videoView,playerCfg,-1);
    }
    public static void updateCfg(VideoView videoView, JSONObject playerCfg,int forcePlayerType) {
        int playerType = KV.get(HawkConfig.PLAY_TYPE, 2);
        int renderType = KV.get(HawkConfig.PLAY_RENDER, 1);
        String ijkCode = KV.get(HawkConfig.IJK_CODEC, "硬解码");
        int scale = KV.get(HawkConfig.PLAY_SCALE, 0);
        try {
            playerType = playerCfg.getInt("pl");
            renderType = playerCfg.getInt("pr");
            ijkCode = playerCfg.getString("ijk");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if(forcePlayerType>=0)playerType = forcePlayerType;
        IJKCode codec = ApiConfig.get().getIJKCodec(ijkCode);
        PlayerFactory playerFactory;
        if (playerType == 1) {
            playerFactory = new PlayerFactory<IjkMediaPlayer>() {
                @Override
                public IjkMediaPlayer createPlayer(Context context) {
                    return new IjkMediaPlayer(context, codec);
                }
            };
            try {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override
                    public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                        try {
                            System.loadLibrary(s);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (playerType == 2) {
            playerFactory = ExoMediaPlayerFactory.create();
        } else {
            playerFactory = ExoMediaPlayerFactory.create();
        }
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        if(videoView!=null){
            videoView.setPlayerFactory(playerFactory);
            if (videoView instanceof MyVideoView) {
                ((MyVideoView) videoView).saveConfiguredFactory(playerFactory);
            }
            // 复用中的 IJK 内核同步解码配置(2026-09-15):换集/换线/换源走 replay 复用同一实例,
            // 不会按新工厂重建 —— 不推的话新解码方式要等换片/换源释放内核才生效
            applyIjkCodecToLivePlayer(videoView, playerType, codec);
            videoView.setRenderViewFactory(renderViewFactory);
            videoView.setScreenScaleType(scale);
        }
    }

    /**
     * 把解码配置推给**正在复用**的 IJK 内核(2026-09-15)。
     *
     * <p>背景:{@code IjkMediaPlayer} 的 codec 在构造时固化,解码 options 只在 reset/prepare 时由
     * {@code setOptions()} 应用;而换集/换线/换源/自动换线都不重建实例(复用路径见 fork `VideoView.replay`),
     * 只更新工厂等于没生效 —— 用户在设置里把硬解改成软解,继续换集仍是硬解。
     *
     * <p>本方法在每次起播前被调用({@code PlaybackController.goPlayUrl → applyPlayerConfigToView}),
     * 推送后紧接着的 reset 起播即按新解码方式走。解码方式没变时 {@code ApiConfig.getIJKCodec} 返回同一缓存
     * 对象,推的是同一个引用 —— 与修改前行为完全一致,零开销。
     */
    private static void applyIjkCodecToLivePlayer(VideoView videoView, int playerType, IJKCode codec) {
        if (playerType != 1 || codec == null || !(videoView instanceof MyVideoView)) return;
        AbstractPlayer live = ((MyVideoView) videoView).getMediaPlayer();
        if (live instanceof IjkMediaPlayer) {
            ((IjkMediaPlayer) live).setCodec(codec);
        }
    }

    public static void updateCfg(VideoView videoView) {
        int playType = KV.get(HawkConfig.PLAY_TYPE, 2);
        PlayerFactory playerFactory;
        if (playType == 1) {
            playerFactory = new PlayerFactory<IjkMediaPlayer>() {
                @Override
                public IjkMediaPlayer createPlayer(Context context) {
                    return new IjkMediaPlayer(context, null);
                }
            };
            try {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override
                    public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                        try {
                            System.loadLibrary(s);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (playType == 2) {
            playerFactory = ExoMediaPlayerFactory.create();
        } else {
            playerFactory = ExoMediaPlayerFactory.create();
        }
        int renderType = KV.get(HawkConfig.PLAY_RENDER, 1);
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        videoView.setPlayerFactory(playerFactory);
        if (videoView instanceof MyVideoView) {
            ((MyVideoView) videoView).saveConfiguredFactory(playerFactory);
        }
        videoView.setRenderViewFactory(renderViewFactory);
    }


    public static void init() {
        try {
            tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                @Override
                public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                    try {
                        System.loadLibrary(s);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * rtmp 协议仅 ijk 引擎支持(media3 已移除 rtmp 扩展):
     * rtmp 源强制切换 ijk,其他协议恢复用户配置的引擎,避免直播切台时状态泄漏
     */
    public static void applyRtmpSchemeOverride(VideoView videoView, String url) {
        if (!(videoView instanceof MyVideoView)) return;
        MyVideoView view = (MyVideoView) videoView;
        boolean rtmp = url != null && url.trim().toLowerCase().startsWith("rtmp://");
        if (!rtmp) {
            view.restoreConfiguredFactory();
            return;
        }
        if (view.isRtmpForced()) return;
        LOG.i("echo-rtmp-force-ijk: " + url);
        IJKCode codec = ApiConfig.get().getIJKCodec(KV.get(HawkConfig.IJK_CODEC, "硬解码"));
        view.forceIjkFactory(new PlayerFactory<IjkMediaPlayer>() {
            @Override
            public IjkMediaPlayer createPlayer(Context context) {
                return new IjkMediaPlayer(context, codec);
            }
        });
        // rtmp 强制 IJK 的工厂用全局解码设置(与播放侧 playerCfg.ijk 不同源):复用中的实例也要跟上,
        // 否则 rtmp 换集同样停在旧解码(2026-09-15,与 updateCfg 同一处理)
        applyIjkCodecToLivePlayer(view, 1, codec);
    }

    /**
     * 本地代理 URL 判定(2026-09-13):spider 自建代理(网盘)/M3U8 净化/DASH 代理都是
     * 127.0.0.1 上 App 内服务的地址,不是稳定的可随机访问 HTTP 文件源。
     * 边播缓存的 CacheDataSource 与这类 URL 的区间读取语义不兼容 —— 实测夸克 4K mp4 源
     * 需跳读文件尾 moov 时抛 ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,EXO 直接无法起播
     * (关掉边播缓存即恢复正常);直连 URL(可随机访问)不受影响。
     * 故这类 URL 跳过磁盘缓存,与预载侧 PreloadCoordinator 的排除口径一致。
     */
    public static boolean isLocalProxyUrl(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost");
    }

    /**
     * 从 getPlay 结果 JSON 提取请求头(header/headers 字段,兼容 JSONObject 与 JSON 文本两种形态)。
     *
     * <p>2026-09-13 修复:预载({@code PreloadCoordinator.extractHeaders})与播放
     * ({@code PlayContainer.getHeaders})必须共用本方法 —— 此前预载侧只认 JSONObject、
     * 播放侧还认 String,源返回 {@code "header":"{\"User-Agent\":\"...\"}"} 时两侧的
     * {@code keyOf(url,headers)} 不一致,预载内存数据永不命中(仅剩磁盘兜底)。
     *
     * @return 提取到的请求头(键值均原样保留,不 trim);无任何头时返回 null(与旧实现语义一致)
     */
    public static HashMap<String, String> extractPlayHeaders(JSONObject playResult) {
        if (playResult == null) return null;
        HashMap<String, String> headers = new HashMap<>();
        appendJsonHeaders(headers, playResult.opt("header"));
        appendJsonHeaders(headers, playResult.opt("headers"));
        return headers.isEmpty() ? null : headers;
    }

    /** 合并单个 header(s) 字段:接受 JSONObject 或 JSON 文本;非法内容静默跳过(保持旧行为) */
    public static void appendJsonHeaders(HashMap<String, String> headers, Object rawHeaders) {
        if (headers == null || rawHeaders == null || rawHeaders == JSONObject.NULL) return;
        try {
            JSONObject json = null;
            if (rawHeaders instanceof JSONObject) {
                json = (JSONObject) rawHeaders;
            } else if (rawHeaders instanceof String) {
                String text = ((String) rawHeaders).trim();
                if (!TextUtils.isEmpty(text)) {
                    json = new JSONObject(text);
                }
            }
            if (json == null) return;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!TextUtils.isEmpty(key)) {
                    headers.put(key, json.optString(key, ""));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String getPlayerName(int playType) {
        HashMap<Integer, String> playersInfo = getPlayersInfo();
        if (playersInfo.containsKey(playType)) {
            return playersInfo.get(playType);
        } else {
            return "EXO播放器";
        }
    }

    private static HashMap<Integer, String> mPlayersInfo = null;
    public static HashMap<Integer, String> getPlayersInfo() {
        if (mPlayersInfo == null) {
            HashMap<Integer, String> playersInfo = new HashMap<>();
            playersInfo.put(1, "IJK播放器");
            playersInfo.put(2, "EXO播放器");
            playersInfo.put(10, "MX播放器");
            playersInfo.put(11, "Reex播放器");
            playersInfo.put(12, "Kodi播放器");
            playersInfo.put(13, "附近TVBox");
            playersInfo.put(14, "VLC播放器");
            mPlayersInfo = playersInfo;
        }
        return mPlayersInfo;
    }

    private static HashMap<Integer, Boolean> mPlayersExistInfo = null;

    /**
     * 作废"可用播放器"缓存(2026-09-13)。
     * ⚠️ 该表是**进程级缓存**(首次调用后不再重算),而 13 号 RemoteTVBox 的可用性取决于
     * `HawkConfig.REMOTE_TVBOX` —— 投屏扫描/投屏成功时才写入。不重置缓存的话,
     * 「RemoteTVBox 播放器」选项在本次进程内永远不会出现。
     */
    public static void invalidatePlayersExistInfo() {
        mPlayersExistInfo = null;
    }

    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        if (mPlayersExistInfo == null) {
            HashMap<Integer, Boolean> playersExist = new HashMap<>();
            playersExist.put(1, true);
            playersExist.put(2, true);
            playersExist.put(10, MXPlayer.getPackageInfo() != null);
            playersExist.put(11, ReexPlayer.getPackageInfo() != null);
            playersExist.put(12, Kodi.getPackageInfo() != null);
            playersExist.put(13, RemoteTVBox.getAvalible() != null);
            playersExist.put(14, VlcPlayer.getPackageInfo() != null);
            mPlayersExistInfo = playersExist;
        }
        return mPlayersExistInfo;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    public static ArrayList<Integer> getExistPlayerTypes() {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        ArrayList<Integer> existPlayers = new ArrayList<>();
        for(Integer playerType : playersExistInfo.keySet()) {
            if (playersExistInfo.get(playerType)) {
                existPlayers.add(playerType);
            }
        }
        return existPlayers;
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = Kodi.run(activity, url, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers);
                break;
            }
            case 14: {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
        }
        return callResult;
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    public static String getScaleName(int screenScaleType) {
        String scaleText = "默认";
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
                scaleText = "默认";
                break;
            case VideoView.SCREEN_SCALE_16_9:
                scaleText = "16:9";
                break;
            case VideoView.SCREEN_SCALE_4_3:
                scaleText = "4:3";
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                scaleText = "填充";
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                scaleText = "原始";
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                scaleText = "裁剪";
                break;
        }
        return scaleText;
    }

    public static String getDisplaySpeed(long speed,boolean show) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "Mb/s";
        else if(speed > 1024)
            return (speed / 1024) + "Kb/s";
        else
            return speed > 0?speed + "B/s":(show?"0B/s":"");
    }
    public static String getDisplaySpeedBps(long speed, boolean show) {
        long bitSpeed = speed * 8; // 字节转比特
        if (bitSpeed >= 1_000_000_000) {
            return new DecimalFormat("0.00").format(bitSpeed / 1_000_000_000d) + "Gbps";
        } else if (bitSpeed >= 1_000_000) {
            return new DecimalFormat("0.0").format(bitSpeed / 1_000_000d) + "Mbps";
        } else if (bitSpeed >= 1_000) {
            return new DecimalFormat("0.0").format(bitSpeed / 1_000d) + "Kbps";
        } else {
            return bitSpeed > 0 ? bitSpeed + "bps" : (show ? "0bps" : "");
        }
    }
}
