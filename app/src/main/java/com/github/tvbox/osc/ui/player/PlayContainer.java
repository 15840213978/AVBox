package com.github.tvbox.osc.ui.player;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.github.catvod.crawler.Spider;
import com.google.gson.JsonObject;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.base.App;
import android.widget.FrameLayout;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.dlna.CastVideo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.MusicPlaybackService;
import com.github.tvbox.osc.player.PreloadManagerHolder;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.ComposeVideoController;
import com.github.tvbox.osc.player.controller.PlayerControlApi;
import com.github.tvbox.osc.player.controller.VodControlListener;
import com.github.tvbox.osc.player.danmu.DanmuLoadController;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.player.state.CastSheetState;
import com.github.tvbox.osc.player.state.DanmuSearchSheetState;
import com.github.tvbox.osc.player.state.DanmuSettingSheetState;
import com.github.tvbox.osc.player.state.EpisodeSheetState;
import com.github.tvbox.osc.player.state.PlayerUiState;
import com.github.tvbox.osc.player.state.SelectDialogState;
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState;
import com.github.tvbox.osc.player.state.SubtitleSheetState;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import me.jessyan.autosize.internal.CustomAdapt;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.parser.SuperParse;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.github.tvbox.osc.util.KV;
import androidx.media3.common.text.Cue;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.jessyan.autosize.AutoSize;
import master.flame.danmaku.ui.widget.DanmakuView;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayContainer extends FrameLayout implements CustomAdapt {

    /** BugReview #17:轨道切换延迟恢复序号;窗口期内切内核/连续切换/页面销毁后旧回调作废 */
    private final AtomicInteger trackSwitchSeq = new AtomicInteger(0);
    private Activity mActivity;
    private final Context mContext;

    public PlayContainer(@NonNull Activity activity) {
        super(activity);
        mActivity = activity;
        mContext = activity;
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth());
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true);
        // 新容器创建即清掉全局桥里上一个页面实例的提示残留(如源站错误文案)
        PlayerTipBridge.hide();
        init();
    }

    private boolean isAttached() {
        return mActivity != null && !mActivity.isFinishing();
    }

    /**
     * 生命周期自动暂停标记：退后台时若在播放中由 hostPause 自动暂停，回前台 hostResume 需恢复；
     * 用户手动暂停（isPlaying=false）退后台时不动，回前台保持暂停直到用户手动播放。
     */
    private boolean lifecyclePaused;

    /** 由 Compose 宿主在页面可见时调用(对应旧 Fragment onResume/onHiddenChanged(false)) */
    public void hostResume() {
        exitingPreview = false;
        if (mController != null) mController.setLifecyclePaused(false);
        if (mVideoView != null && lifecyclePaused) {
            lifecyclePaused = false;
            mVideoView.resume();
        }
    }

    /** 由 Compose 宿主在页面不可见时调用(对应旧 Fragment onPause/onHiddenChanged(true)) */
    public void hostPause() {
        // 只有**确定是纯音频**(TRUE)才不退后台暂停;影视(FALSE)与「轨道信息未知(null)」都按既有行为暂停 ——
        // 迁移前 `!Boolean.TRUE.equals(getAudioOnlyPlayback())` 正是这个语义(null 落到 pause 分支);
        // 迁移中改写成 `!hasAudioOnlyPlayback()` 后 **null 变成了「不暂停」**:起播瞬间 getTrackInfo()
        // 返回 null 时影视会被留在后台继续出声(行为回归,但只在极窄的时间窗内可观测)。本次恢复三态判定。
        if (mVideoView != null && !exitingPreview && !Boolean.TRUE.equals(isAudioOnlyPlayback())) {
            lifecyclePaused = mVideoView.isPlaying();
            if (mController != null) mController.setLifecyclePaused(true);
            mVideoView.pause();
        }
    }

    /** 由 Compose 宿主在页面销毁时调用(对应旧 Fragment onDestroyView) */
    public void hostDestroy() {
        audioPlayback = false;
        switchingPlayback = false;
        MusicPlaybackService.stop(getContext(), this);
        if (sourceViewModel != null && playResultObserver != null) {
            sourceViewModel.playResult.removeObserver(playResultObserver);
            playResultObserver = null;
        }
        ApiConfig.get().setCurrentPlaySourceKey("");
        cancelPlayTimeout();
        if (preloadCoordinator != null) {
            preloadCoordinator.destroy();
            preloadCoordinator = null;
        }
        // 预载第二期:注销就绪回调 + 撤下 Toast(防页面销毁后回调/Toast 残留;仅当回调仍是本实例时清)
        PreloadManagerHolder.clearReadyListener(preloadReadyListener);
        preloadReadyListener = null;
        cancelPreloadToast();
        EventBus.getDefault().unregister(this);
        // BugReview #17:作废在途的轨道切换延迟回调,防对已释放播放器实例 seekTo/start
        trackSwitchSeq.incrementAndGet();
        if (danmuLoadController != null) {
            danmuLoadController.destroy();
            danmuLoadController = null;
        }
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        stopLoadWebView(true);
        stopParse();
        if (mController != null) mController.stopOther();
        // 置空须在 stopLoadWebView 之后:其内部以 mActivity 判空决定是否销毁 WebView
        mActivity = null;
    }

    @Override
    public float getSizeInDp() {
        return (mActivity instanceof CustomAdapt) ? ((CustomAdapt) mActivity).getSizeInDp() : 0;
    }

    @Override
    public boolean isBaseOnWidth() {
        return !(mActivity instanceof CustomAdapt) || ((CustomAdapt) mActivity).isBaseOnWidth();
    }

    private static final int MSG_PARSE_TIMEOUT = 100;
    private static final int MSG_RESOLVE_PLAY_URL_TIMEOUT = 101;
    private static final int MSG_SWITCH_LINE_PLAY_TIMEOUT = 102;
    private static final long RESOLVE_PLAY_URL_TIMEOUT_MS = 15 * 1000L;
    private static final long SWITCH_LINE_PLAY_TIMEOUT_MS = 20 * 1000L;
    /** 「下一集已就绪」Toast 续期延迟(预载方案第二期,2026-09-12 定稿 Toast+5s):1.5s 时续一次,LENGTH_LONG(≈3.5s)+1.5s≈5s */
    private static final long PRELOAD_TOAST_REFRESH_DELAY_MS = 1500L;
    private MyVideoView mVideoView;
    private PlayerControlApi mController;
    private SourceViewModel sourceViewModel;
    private Observer<JSONObject> playResultObserver;
    /** 下一集预载协调器(预载方案第一期,见 skill/avbox-preload-next-episode-spec.md) */
    private PreloadCoordinator preloadCoordinator;
    /** 预载完成回调实例(第二期 UI 提示;销毁时按实例注销,防误清其他容器的回调) */
    private PreloadManagerHolder.ReadyListener preloadReadyListener;
    /** 「下一集已就绪」Toast 实例(第二期;切集/销毁时 cancel) */
    private Toast preloadReadyToast;
    private JSONObject qualityResult;
    private Handler mHandler;
    private boolean exitingPreview = false;
    private boolean audioPlayback;
    private boolean switchingPlayback;
    private boolean reusePlayerOnSwitch;
    private boolean releasePlayerOnSwitch;
    /**
     * 换源点击即停标记:[stopForSourceSwitch] 置位,抑制在途取流结果/超时/嗅探回调把已停的旧源重新拉起;
     * 下一次 [play] 清除(那次播放是用户显式请求)。
     */
    private boolean switchStopPending;
    /** 换源停播时记下的进度(键+毫秒):新源 progressKey 不同,取流后写进新键缓存以接着看(见 play) */
    private String pendingInheritKey;
    private long pendingInheritProgress;
    private boolean previewMode;
    private String playLyric;
    private String lyricCacheKey;
    private String playArtwork;
    private DanmakuView mDanmuView;
    private DanmuLoadController danmuLoadController;
    private final List<Cue> exoCues = new ArrayList<>();
    private boolean exoInternalSubtitle;

    private final long videoDuration = -1;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            mController.getSubtitleView().setTextSize((int) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            setDanmuViewSettings(event.obj instanceof Boolean && (Boolean) event.obj);
        } else if (event.type == RefreshEvent.TYPE_DANMU_REFRESH) {
            checkDanmu(event.obj instanceof String ? (String) event.obj : "");
        }
    }

    private void init() {
        initView();
        initDanmuView();
        initViewModel();
    }

    private void initDanmuView() {
        mDanmuView = findViewById(R.id.danmaku);
        danmuLoadController = new DanmuLoadController(mVideoView, mController, mDanmuView);
    }

    private void setDanmuViewSettings(boolean reload) {
        if (danmuLoadController != null) danmuLoadController.applySettings(reload);
    }

    private void checkDanmu(String danmu) {
        checkDanmu(danmu, null);
    }

    private void checkDanmu(String danmu, DanmuLoadController.LoadCallback callback) {
        if (danmuLoadController != null) {
            VodInfo.VodSeries series = mVodInfo == null ? null : getCurrentSeries(mVodInfo.playFlag, mVodInfo.playIndex);
            danmuLoadController.check(danmu, mVodInfo == null ? "" : mVodInfo.name, series == null ? "" : series.name, callback);
        }
    }

    private void startDanmuIfReady() {
        if (danmuLoadController != null) danmuLoadController.startIfReady();
    }

    private void resetDanmuState() {
        if (danmuLoadController != null) danmuLoadController.reset();
    }

    private void reloadDanmuForPlayback() {
        if (danmuLoadController != null) danmuLoadController.reloadForPlayback();
    }

    public long getSavedProgress(String url) {
        int st = 0;
        try {
            st = mVodPlayerCfg.getInt("st");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        long skip = st * 1000L;
        Object theCache=CacheManager.getCache(MD5.string2MD5(url));
        if (theCache == null) {
            return skip;
        }
        long rec = 0;
        if (theCache instanceof Long) {
            rec = (Long) theCache;
        } else if (theCache instanceof String) {
            try {
                rec = Long.parseLong((String) theCache);
            } catch (NumberFormatException e) {
                LOG.i("echo-String value is not a valid long.");
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.");
        }
        return Math.max(rec, skip);
    }

    private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_PARSE_TIMEOUT:
                        stopParse();
                        errorWithRetry("嗅探错误", false);
                        break;
                    case MSG_RESOLVE_PLAY_URL_TIMEOUT:
                        handleResolvePlayUrlTimeout();
                        break;
                    case MSG_SWITCH_LINE_PLAY_TIMEOUT:
                        handleSwitchLinePlayTimeout();
                        break;
                }
                return false;
            }
        });
        mVideoView = findViewById(R.id.mVideoView);
        // 点播磁盘缓存标记(第二期扩展「边播边缓存」):本容器为点播 → Exo 侧普通集也启用 cache 数据源;
        // 直播页(LivePlayActivity)不设置,保持 false 天然排除
        mVideoView.setExoDiskCacheEnabled(true);
        mController = new ComposeVideoController(mActivity);
        // 预载就绪提示(预载方案第二期,2026-09-12 定稿 Toast+5s):预载完成 → Toast「下一集已就绪」
        preloadReadyListener = new PreloadManagerHolder.ReadyListener() {
            @Override
            public void onPreloadReady(String url) {
                final Activity activity = mActivity;
                if (activity == null || !isAttached()) return;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showPreloadReady();
                    }
                });
            }
        };
        PreloadManagerHolder.setReadyListener(preloadReadyListener);
        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        ProgressManager progressManager = new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                CacheManager.save(MD5.string2MD5(url), progress);
                if (webPlayUrl != null && progress > 0) {
                    markPlaybackStarted();
                    hideTipOnUiThread();
                }
            }

            @Override
            public long getSavedProgress(String url) {
                return PlayContainer.this.getSavedProgress(url);
            }
        };
        mVideoView.setProgressManager(progressManager);
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PLAYING && mVideoView != null) {
                    mVideoView.showVideoFrame();
                    // 纯音频渲染兜底(2026-09-13):URL 预判漏网(无后缀音乐直链)时,轨道信息就绪后补切
                    ensureAudioOnlyRender();
                    // 正片稳定播放 → 延迟评估下一集预载(预载方案第一期)
                    if (preloadCoordinator != null) {
                        preloadCoordinator.scheduleEvaluate(buildPreloadSnapshot());
                    }
                }
                // 正片缓冲(弱网) → 预载让路:清预载数据并进入冷却期,稳定后重新评估(规格 §5.5)
                if (playState == VideoView.STATE_BUFFERING && preloadCoordinator != null) {
                    preloadCoordinator.onMainPlayerBuffering();
                }
                // 缓冲结束 → 补一次预载评估(2026-09-12 修复 Bug):dkplayer 的 STATE_PLAYING 只在首帧渲染时
                // 发一次(MEDIA_INFO_RENDERING_START),播放中 seek/再缓冲结束只到 STATE_BUFFERED,
                // 不补这一枪则一次拖动进度条就能让本集预载永久停摆;仍处冷却期时由 evaluate 按剩余时间重排
                if (playState == VideoView.STATE_BUFFERED && preloadCoordinator != null) {
                    preloadCoordinator.scheduleEvaluate(buildPreloadSnapshot());
                }
                if (webPlayUrl != null && isStartedPlayState(playState)) {
                    markPlaybackStarted();
                    if (mVideoView == null || !mVideoView.isVideoFrameCleared()) {
                        hideTipOnUiThread();
                    }
                }
                if (switchingPlayback) {
                    if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                        LOG.i("echo-music keep session while resolving next episode");
                        return;
                    } else if (playState == VideoView.STATE_ERROR) {
                        switchingPlayback = false;
                        audioPlayback = false;
                    } else if (isStartedPlayState(playState)) {
                        // 起播成功:有音频轨则维护会话/通知(影视同样,见 updateMusicSession 的语义拆分)
                        if (hasPlayableAudio() || audioPlayback) {
                            switchingPlayback = false;
                            audioPlayback = true;
                        }
                    }
                }
                if (!switchingPlayback) updateMusicSession();
                startDanmuIfReady();
            }
        });
        mController.setListener(new VodControlListener() {
            @Override
            public void showDanmuSetting() {
                if (!isAttached()) return;
                mController.getUiState().setDanmuSettingSheet(new DanmuSettingSheetState(() -> {
                    openDanmuSearchSheet();
                    return kotlin.Unit.INSTANCE;
                }));
            }

            @Override
            public boolean toggleDanmu() {
                return danmuLoadController != null && danmuLoadController.toggle();
            }

            @Override
            public void searchDanmuUi(boolean longClick) {
                VodInfo.VodSeries series = mVodInfo == null ? null : getCurrentSeries(mVodInfo.playFlag, mVodInfo.playIndex);
                ApiConfig.get().searchDanmuUi(mVodInfo == null ? "" : mVodInfo.name, series == null ? "" : series.name, longClick);
            }

            @Override
            public void playNext(boolean rmProgress) {
                String preProgressKey = progressKey;
                PlayContainer.this.playNext(rmProgress);
                if (rmProgress && preProgressKey != null)
                    CacheManager.delete(MD5.string2MD5(preProgressKey), 0);
            }

            @Override
            public void playPre() {
                PlayContainer.this.playPrevious();
            }

            @Override
            public void showEpisodeDialog() {
                PlayContainer.this.showEpisodeDialog();
            }

            @Override
            public void changeParse(ParseBean pb) {
                autoRetryCount = 0;
                hasAutoSwitchedPlayer = false;
                triedLineFlags.clear();
                doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                mVodInfo.playerCfg = mVodPlayerCfg.toString();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
            }

            @Override
            public void replay(boolean replay) {
                autoRetryCount = 0;
                hasAutoSwitchedPlayer = false;
                triedLineFlags.clear();
                if(replay){
                    play(true);
                }else {
                    reloadDanmuForPlayback();
                    if(webPlayUrl!=null && !webPlayUrl.isEmpty()) {
                        stopParse();
                        initParseLoadFound();
                        if(mVideoView!=null) mVideoView.release();
                        goPlayUrl(webPlayUrl,webHeaderMap);
                    }else {
                        play(false);
                    }
                }
            }

            @Override
            public void errReplay() {
                errorWithRetry("视频播放出错", false);
            }

            @Override
            public void selectSubtitle() {
                try {
                    selectMySubtitle();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void selectVideoTrack() {
                selectMyVideoTrack();
            }

            @Override
            public void prepared() {
                initSubtitleView();
                if (mVideoView != null) mVideoView.prepared();
                startDanmuIfReady();
            }
            @Override
            public void startPlayUrl(String url, HashMap<String, String> headers) {
                if (!TextUtils.isEmpty(m3u8SourceUrl) && !isM3u8ProxyUrl(url)) clearM3u8ProxyUrl();
                goPlayUrl(url, headers);
            }

            @Override
            public void onM3u8ProxyUrl(String proxyUrl, String sourceUrl) {
                m3u8ProxyUrl = proxyUrl;
                m3u8SourceUrl = sourceUrl;
            }

            @Override
            public void clickCast() {
                showCastDialog();
            }

            @Override
            public void setAllowSwitchPlayer(boolean isAllow){allowSwitchPlayer=isAllow;}
        });
        mVideoView.setVideoController((BaseVideoController) mController);
    }

    /**
     * 详情页投屏入口(2026-09-13):复用播放器底栏「投屏」的同一条链路 ——
     * 同一个投屏面板(CastSheet,Dialog)、同一套 DLNA/TVBox 扫描与投送逻辑。
     * 无可投地址时内部已有 Toast 提示。
     */
    public void showCast() {
        showCastDialog();
    }

    private void showCastDialog() {
        if (TextUtils.isEmpty(webPlayUrl)) {
            Toast.makeText(mContext, "暂无可投屏播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAttached()) return;
        HashMap<String, String> headers = webHeaderMap == null ? null : new HashMap<>(webHeaderMap);
        CastVideo video = new CastVideo(getCastUrl(webPlayUrl), getCastTitle(), headers, getCastPosition());
        PlayerUiState uiState = mController.getUiState();
        uiState.setCastSheet(new CastSheetState(video, () -> {
            if (mVideoView != null) mVideoView.pause();
            return kotlin.Unit.INSTANCE;
        }));
    }

    /** 弹幕搜索面板（Step 6：替代 View 版 SearchDanmuDialog，旧 openSearchDanmuDialog 内容） */
    private void openDanmuSearchSheet() {
        if (!isAttached()) return;
        VodInfo.VodSeries series = mVodInfo == null ? null : getCurrentSeries(mVodInfo.playFlag, mVodInfo.playIndex);
        PlayerUiState uiState = mController.getUiState();
        uiState.setDanmuSearchSheet(new DanmuSearchSheetState(
                series == null ? "" : series.name,
                mVodInfo == null ? "" : mVodInfo.name,
                danmu -> {
                    if (!isAttached()) return kotlin.Unit.INSTANCE;
                    checkDanmu(danmu);
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private String getCastTitle() {
        if (mVodInfo == null) return "TVBox";
        try {
            VodInfo.VodSeries series = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
            return mVodInfo.name + " " + series.name;
        } catch (Exception e) {
            return TextUtils.isEmpty(mVodInfo.name) ? "TVBox" : mVodInfo.name;
        }
    }

    private long getCastPosition() {
        try {
            return mVideoView == null ? 0 : mVideoView.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private String getCastUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (isM3u8ProxyUrl(url) && !TextUtils.isEmpty(m3u8SourceUrl)) return m3u8SourceUrl;
        String local = ControlManager.get().getAddress(true);
        String server = ControlManager.get().getAddress(false);
        if (!TextUtils.isEmpty(local) && !TextUtils.isEmpty(server) && url.startsWith(local)) {
            return server + url.substring(local.length());
        }
        return url;
    }

    private boolean isM3u8ProxyUrl(String url) {
        return !TextUtils.isEmpty(m3u8ProxyUrl) && url.equals(m3u8ProxyUrl);
    }

    private void clearM3u8ProxyUrl() {
        m3u8ProxyUrl = null;
        m3u8SourceUrl = null;
    }

    //设置字幕
    void setSubtitle(String path) {
        if (path != null && path .length() > 0) {
            hideExoInternalSubtitle();
            // 设置字幕
            mController.getSubtitleView().setVisibility(View.GONE);
            mController.getSubtitleView().setSubtitlePath(path);
            // 恢复用户选择的文字样式(样式一 白 / 样式二 粉,2026-09-12 补回)
            setSubtitleViewTextStyle(KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0));
            mController.getSubtitleView().setVisibility(View.VISIBLE);
        }
    }

    void selectMySubtitle() {
        try {
            if (!isAttached()) return;
            PlayerUiState uiState = mController.getUiState();
            AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
            boolean hasInternal = mController.getSubtitleView().hasInternal || hasExoInternalSubtitle(mediaPlayer);
            boolean exoInternal = mediaPlayer instanceof ExoPlayer && exoInternalSubtitle;
            uiState.setSubtitleSheet(new SubtitleSheetState(
                    exoInternal,
                    hasInternal,
                    () -> {
                        selectMyInternalSubtitle();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        openLocalSubtitleChooser();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        openSubtitleSearchSheet();
                        return kotlin.Unit.INSTANCE;
                    },
                    style -> {
                        // 样式一(0)/样式二(1):应用并持久化,下次挂载外挂字幕自动恢复
                        KV.put(HawkConfig.SUBTITLE_TEXT_STYLE, style);
                        setSubtitleViewTextStyle(style);
                        return kotlin.Unit.INSTANCE;
                    }));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 本地字幕文件选择:转发给宿主 Activity 的 SAF 系统选择器(2026-09-12)。
     * 旧 obsez ChooserDialog 自检 WRITE_EXTERNAL_STORAGE,Android 13+ 该权限被系统
     * 静默拒绝 → 永远弹 "You denied the Read/Write permissions on SDCard." 且无法打开。
     */
    private void openLocalSubtitleChooser() {
        if (mActivity instanceof DetailActivity) {
            ((DetailActivity) mActivity).launchLocalSubtitlePicker();
        }
    }

    /** SAF 选中回调:content:// 拷贝到缓存目录再按文件路径渲染(Exo/IJK 双内核兼容) */
    public void onLocalSubtitlePicked(android.net.Uri uri) {
        if (!isAttached()) return;
        new Thread(() -> {
            try {
                String name = queryDisplayName(uri);
                if (name == null || !name.contains(".")) name = "local_subtitle.srt";
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                File dst = new File(mActivity.getCacheDir(), "subtitle_" + System.currentTimeMillis() + "_" + name);
                try (java.io.InputStream in = mActivity.getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                String path = dst.getAbsolutePath();
                mActivity.runOnUiThread(() -> {
                    LOG.i("echo-Local Subtitle Path: " + path);
                    setSubtitle(path);
                });
            } catch (Exception e) {
                LOG.e("echo-Local Subtitle copy err: " + e);
                mActivity.runOnUiThread(() ->
                        android.widget.Toast.makeText(mActivity, "读取字幕文件失败", android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** SAF 文件显示名(用于保留字幕扩展名,渲染器按扩展名选解析器) */
    private String queryDisplayName(android.net.Uri uri) {
        try (android.database.Cursor c = mActivity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 在线字幕搜索面板（旧 SearchSubtitleListener 内容） */
    private void openSubtitleSearchSheet() {
        if (!isAttached()) return;
        String word = (mVodInfo.playFlag.contains("Ali") || mVodInfo.playFlag.contains("parse"))
                ? mVodInfo.playNote : mVodInfo.name;
        PlayerUiState uiState = mController.getUiState();
        uiState.setSubtitleSearchSheet(new SubtitleSearchSheetState(word == null ? "" : word, subtitle -> {
            if (!isAttached()) return kotlin.Unit.INSTANCE;
            mActivity.runOnUiThread(() -> {
                String zimuUrl = subtitle.getUrl();
                LOG.i("echo-Remote Subtitle Url: " + zimuUrl);
                setSubtitle(zimuUrl);
            });
            return kotlin.Unit.INSTANCE;
        }));
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    void setSubtitleViewTextStyle(int style) {
        if (style == 0) {
            mController.getSubtitleView().setTextColor(getContext().getResources().getColorStateList(R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.getSubtitleView().setTextColor(getContext().getResources().getColorStateList(R.color.color_FFB6C1));
        }
    }

    private boolean isSameTrack(TrackInfoBean left, TrackInfoBean right) {
        return left.renderId == right.renderId
                && left.trackGroupId == right.trackGroupId
                && left.trackId == right.trackId;
    }

    void selectMyAudioTrack() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer)mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer)mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : bean) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                "切换音轨",
                names,
                trackInfo.getAudioSelected(false),
                pos -> {
                    if (pos < 0 || pos >= bean.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = bean.get(pos);
                    try {
                        for (TrackInfoBean audio : bean) {
                            audio.selected = isSameTrack(audio, value);
                        }
                        mediaPlayer.pause();
                        long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                        if (mediaPlayer instanceof IjkMediaPlayer) ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId, progressKey);
                        if (mediaPlayer instanceof ExoPlayer) ((ExoPlayer) mediaPlayer).setTrack(value, progressKey);
                        // BugReview #17:序号防护,窗口期内切内核/连续切换/页面销毁后旧回调作废
                        final int seq = trackSwitchSeq.incrementAndGet();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != trackSwitchSeq.get()) return;
                                if (mediaPlayer instanceof IjkMediaPlayer) mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                            }
                        }, 200);
                    } catch (Exception e) {
                        LOG.e("切换音轨出错");
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    void selectMyVideoTrack() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
        } else if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null || trackInfo.getVideo().isEmpty()) {
            Toast.makeText(mContext, "没有视轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> tracks = trackInfo.getVideo();
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : tracks) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                "切换视轨",
                names,
                trackInfo.getVideoSelected(false),
                pos -> {
                    if (pos < 0 || pos >= tracks.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = tracks.get(pos);
                    try {
                        for (TrackInfoBean track : tracks) {
                            track.selected = isSameTrack(track, value);
                        }
                        mediaPlayer.pause();
                        long progress = mediaPlayer.getCurrentPosition();
                        if (mediaPlayer instanceof IjkMediaPlayer) {
                            ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId);
                        } else if (mediaPlayer instanceof ExoPlayer) {
                            ((ExoPlayer) mediaPlayer).setTrack(value, "");
                        }
                        // BugReview #17:序号防护,窗口期内切内核/连续切换/页面销毁后旧回调作废
                        final int seq = trackSwitchSeq.incrementAndGet();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != trackSwitchSeq.get()) return;
                                mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                            }
                        }, 200);
                    } catch (Exception e) {
                        LOG.e("echo-switch-video-track-error:" + e.getMessage());
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    void selectMyInternalSubtitle() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
        } else if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有内置字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : bean) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                "切换内置字幕",
                names,
                trackInfo.getSubtitleSelected(false),
                pos -> {
                    if (pos < 0 || pos >= bean.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = bean.get(pos);
                    try {
                        for (TrackInfoBean subtitle : bean) {
                            subtitle.selected = isSameTrack(subtitle, value);
                        }
                        if (mediaPlayer instanceof IjkMediaPlayer) {
                            mediaPlayer.pause();
                            long progress = mediaPlayer.getCurrentPosition();
                            mController.getSubtitleView().destroy();
                            mController.getSubtitleView().clearSubtitleCache();
                            mController.getSubtitleView().isInternal = true;
                            ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId);
                            // BugReview #17:序号防护,窗口期内切内核/连续切换/页面销毁后旧回调作废
                            final int seq = trackSwitchSeq.incrementAndGet();
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (seq != trackSwitchSeq.get()) return;
                                    mediaPlayer.seekTo(progress);
                                    mediaPlayer.start();
                                }
                            }, 800);
                        } else if (mediaPlayer instanceof ExoPlayer) {
                            mController.getSubtitleView().setVisibility(View.GONE);
                            mController.getSubtitleView().destroy();
                            mController.getSubtitleView().clearSubtitleCache();
                            mController.getSubtitleView().isInternal = false;
                            exoInternalSubtitle = true;
                            ((ExoPlayer) mediaPlayer).setTrack(value, "");
                            ((ExoPlayer) mediaPlayer).setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                            mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                            applyExoSubtitleSettings();
                        }
                    } catch (Exception e) {
                        LOG.e("echo-switch-internal-subtitle-error:" + e.getMessage());
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private boolean hasExoInternalSubtitle(AbstractPlayer mediaPlayer) {
        if (!(mediaPlayer instanceof ExoPlayer)) return false;
        TrackInfo trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        return trackInfo != null && !trackInfo.getSubtitle().isEmpty();
    }

    private void hideExoInternalSubtitle() {
        exoInternalSubtitle = false;
        exoCues.clear();
        if (mController != null && mController.getExoSubtitleView() != null) {
            mController.getExoSubtitleView().setCues(exoCues);
            mController.getExoSubtitleView().setVisibility(View.GONE);
        }
    }

    private void onExoCues(List<Cue> cues) {
        if (!isAttached() || !exoInternalSubtitle) return;
        exoCues.clear();
        if (cues != null) exoCues.addAll(cues);
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyExoSubtitleSettings();
            }
        });
    }

    private void applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || mController == null || mController.getExoSubtitleView() == null) return;
        float scale = SubtitleHelper.getExoSubtitleScale() / 100f;
        float position = SubtitleHelper.getExoSubtitlePosition();
        mController.getExoSubtitleView().setFractionalTextSize(0.0533f * scale);
        mController.getExoSubtitleView().setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f));

        List<Cue> displayCues = new ArrayList<>();
        for (Cue cue : exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue);
                continue;
            }
            Cue.Builder builder = cue.buildUpon();
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f));
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f));
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType);
            }
            displayCues.add(builder.build());
        }
        mController.getExoSubtitleView().setCues(displayCues);
    }

    private float limit(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 提示改走 Compose 桥(PlayerTipBridge,任意线程可写);View 版提示已随 view_play_container.xml 移除 */
    void setTip(String msg, boolean loading, boolean err) {
        if (!isAttached()) return;
        PlayerTipBridge.setTip(msg, loading, err);
    }

    void hideTip() {
        PlayerTipBridge.hide();
    }

    void hideTipOnUiThread() {
        if (!isAttached()) return;
        PlayerTipBridge.hide();
    }

    /**
     * 「下一集已就绪」Toast(预载方案第二期,2026-09-12 定稿:系统 Toast + 约 5s):
     * 预载完成回调触发,1.5s 续期一次凑足 ≈5s;切集/重播(play 入口)与页面销毁时立即撤下(见 hidePreloadReady)。
     */
    private void showPreloadReady() {
        final Activity activity = mActivity;
        if (activity == null || !isAttached() || mHandler == null) return;
        if (preloadReadyToast != null) preloadReadyToast.cancel();
        preloadReadyToast = Toast.makeText(activity, "下一集已就绪", Toast.LENGTH_LONG);
        preloadReadyToast.show();
        // LENGTH_LONG ≈3.5s,1.5s 时续一次凑足 ≈5s(同实例再次 show 会重置计时)
        mHandler.removeCallbacks(refreshPreloadToastRunnable);
        mHandler.postDelayed(refreshPreloadToastRunnable, PRELOAD_TOAST_REFRESH_DELAY_MS);
    }

    private final Runnable refreshPreloadToastRunnable = new Runnable() {
        @Override
        public void run() {
            if (preloadReadyToast != null) preloadReadyToast.show();
        }
    };

    /** 切集/重播(play 入口)与页面销毁时调用:立即撤下 Toast(未显示时为空操作) */
    private void hidePreloadReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPreloadToast();
        } else if (mActivity != null) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelPreloadToast();
                }
            });
        }
    }

    private void cancelPreloadToast() {
        if (mHandler != null) mHandler.removeCallbacks(refreshPreloadToastRunnable);
        if (preloadReadyToast != null) {
            preloadReadyToast.cancel();
            preloadReadyToast = null;
        }
    }

    void errorWithRetry(String err, boolean finish) {
        if (isPlaybackStarted()) {
            cancelPlayTimeout();
            hideTipOnUiThread();
            return;
        }
        if (!autoRetry()) {
            stopMusicSessionForFailedPlayback();
            if (!isAttached()) return;
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (finish) {
                        setTip(err, false, true);
                        Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show();
                    } else {
                        setTip(err, false, true);
                    }
                }
            });
        }
    }

    private void stopMusicSessionForFailedPlayback() {
        switchingPlayback = false;
        audioPlayback = false;
        MusicPlaybackService.stop(getContext(), this);
    }

    void playUrl(String url, HashMap<String, String> headers) {
        startSwitchLinePlayTimeout();
        url = attachProxySiteKey(url);
        if(!url.startsWith("data:application"))EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, url));//更新播放地址
        if (!KV.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(url,headers);
            return;
        }
        if (url.startsWith("http://127.0.0.1") || !url.contains(".m3u8")) {
            goPlayUrl(url,headers);
            return;
        }
        if(DefaultConfig.noAd(mVodInfo.playFlag)){
            goPlayUrl(url,headers);
            return;
        }
        LOG.i("echo-playM3u8:" + url);
        mController.playM3u8(url,headers);
    }
    public void goPlayUrl(String url, HashMap<String, String> headers) {
        LOG.i("echo-goPlayUrl:" + url);
        if (TextUtils.isEmpty(url)) {
            handleResolvePlayUrlFailed("获取播放地址为空");
            return;
        }
        if(autoRetryCount==0)webPlayUrl=url;
        if (mActivity == null) return;
        if (!isAttached()) return;
        final String finalUrl = url;
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (switchStopPending) {
                    // 换源点击即停后,已排队的取流结果(含嗅探/解析回调)不得再拉起播放
                    LOG.i("echo-ignore goPlayUrl while source switching");
                    return;
                }
                stopParse();
                if (mVideoView != null) {
                    if (finalUrl != null) {
                        String url = finalUrl;
                        try {
                            int playerType = mVodPlayerCfg.getInt("pl");
                            if (playerType >= 10) {
                                mVideoView.release();
                                // BugReview #33:历史恢复的线路在当前源不存在、或换源与切集交错时,
                                // seriesMap 链式取值可能 NPE,逐级判空后回退仅用片名
                                List<VodInfo.VodSeries> series = mVodInfo.seriesMap == null ? null : mVodInfo.seriesMap.get(mVodInfo.playFlag);
                                VodInfo.VodSeries vs = (series == null || mVodInfo.playIndex < 0 || mVodInfo.playIndex >= series.size()) ? null : series.get(mVodInfo.playIndex);
                                String playTitle = mVodInfo.name + (vs == null ? "" : " " + vs.name);
                                setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + "进行播放", true, false);
                                boolean callResult = false;
                                long progress = getSavedProgress(progressKey);
                                callResult = PlayerHelper.runExternalPlayer(playerType, mActivity, url, playTitle, playSubtitle, headers, progress);
                                setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + (callResult ? "成功" : "失败"), callResult, !callResult);
                                return;
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        playTimeoutBasePosition = getSavedProgress(progressKey);
                        boolean forceExoPlayer = url.startsWith("data:application/dash+xml;base64,")
                                || url.contains(".mpd") || url.contains("type=mpd");
                        if (url.startsWith("data:application/dash+xml;base64,")) {
                            PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg, 2);
                            App.getInstance().setDashData(url.split("base64,")[1]);
                            url = ControlManager.get().getAddress(true) + "dash/proxy.mpd";
                        } else if (url.contains(".mpd") || url.contains("type=mpd")) {
                            PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg, 2);
                        } else {
                            PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);
                        }
                        // 纯音频 URL 预判(2026-09-13):音乐直链没有视频帧,SurfaceView 渲染会"洞穿"应用窗口 ——
                        // 任务快照里播放器区域变白、回前台透视桌面(详见 MyVideoView.switchRenderToTexture)。
                        // 这里直接改用 TextureView 起播,补住「起播 → 轨道信息就绪」之间退后台的空窗;
                        // 误判(音频后缀实为视频)无功能损失,TextureView 照常渲染画面。
                        if (looksLikeAudioUrl(url)) {
                            mVideoView.setRenderViewFactory(TextureRenderViewFactory.create());
                        }
                        mController.hidePauseRoot();
                        boolean reusePlayer = !forceExoPlayer && mVideoView.getMediaPlayer() != null;
                        if (!reusePlayer) hideTip();
                        if (!reusePlayer && mVideoView.getMediaPlayer() != null) {
                            mVideoView.release();
                        }
                        mVideoView.setProgressKey(progressKey);
                        if (headers != null) {
                            mVideoView.setUrl(url, headers);
                        } else {
                            mVideoView.setUrl(url);
                        }
                        startSwitchLinePlayTimeout();
                        if (reusePlayer) {
                            mVideoView.skipPositionWhenPlay((int) playTimeoutBasePosition);
                            mVideoView.replay(false);
                        } else {
                            mVideoView.start();
                        }
                        mController.resetSpeed();
                    }
                }
            }
        });
    }

    private String attachProxySiteKey(String url) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sourceKey)) return url;
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url;
        if (url.contains("siteKey=")) return url;
        try {
            return url + (url.contains("?") ? "&" : "?") + "siteKey=" + URLEncoder.encode(sourceKey, "UTF-8");
        } catch (Throwable th) {
            return url + (url.contains("?") ? "&" : "?") + "siteKey=" + sourceKey;
        }
    }

    private void initSubtitleView() {
        TrackInfo trackInfo = null;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().reset();
        mController.getLyricView().bindToMediaPlayer(mediaPlayer);
        mController.getLyricView().setMergeSameTime(true);
        mController.getLyricView().setLyricMode(true);
        mController.getLyricView().setPlaySubtitleCacheKey(lyricCacheKey);
        mController.getSubtitleView().hasInternal = false;
        mController.getSubtitleView().isInternal = false;
        hideExoInternalSubtitle();
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer)mediaPlayer).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.getSubtitleView().hasInternal = true;
            }
            //默认选中第一个音轨 一般第一个音轨是国语 && 加载上一次选中的
            ((IjkMediaPlayer)mediaPlayer).loadDefaultTrack(trackInfo,progressKey);
            ((IjkMediaPlayer)mediaPlayer).setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
                @Override
                public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
                    if(text==null)return;
                    if (mController.getSubtitleView().isInternal) {
                        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                        subtitle.content = text.getText();
                        mController.getSubtitleView().onSubtitleChanged(subtitle);
                    }
                }
            });
        }
        if (mediaPlayer instanceof ExoPlayer) {
            ExoPlayer exoPlayer = (ExoPlayer) mediaPlayer;
            trackInfo = exoPlayer.getTrackInfo();
            if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                mController.getSubtitleView().hasInternal = true;
                exoInternalSubtitle = true;
                mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                exoPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                exoPlayer.setOnCuesListener(new ExoPlayer.OnCuesListener() {
                    @Override
                    public void onCues(List<Cue> cues) {
                        onExoCues(cues);
                    }
                });
                applyExoSubtitleSettings();
            }
            exoPlayer.loadDefaultTrack(progressKey);
        }
        if (!TextUtils.isEmpty(playLyric)) {
            mController.getLyricView().setSubtitlePath(playLyric);
            mController.getLyricView().setVisibility(View.VISIBLE);
        }
        mController.getSubtitleView().bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.getSubtitleView().setPlaySubtitleCacheKey(subtitleCacheKey);
        String subtitlePathCache = (String)CacheManager.getCache(MD5.string2MD5(subtitleCacheKey));
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setSubtitlePath(subtitlePathCache);
        } else {
            if (playSubtitle != null && playSubtitle .length() > 0) {
                hideExoInternalSubtitle();
                mController.getSubtitleView().setSubtitlePath(playSubtitle);
            } else {
                if (mController.getSubtitleView().hasInternal) {
                    if (mediaPlayer instanceof ExoPlayer) {
                        ((ExoPlayer) mediaPlayer).setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                        exoInternalSubtitle = true;
                        mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                        applyExoSubtitleSettings();
                    } else if (mediaPlayer instanceof IjkMediaPlayer && trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                        mController.getSubtitleView().isInternal = true;
                        List<TrackInfoBean> subtitleTrackList = trackInfo.getSubtitle();
                        int selectedIndex = trackInfo.getSubtitleSelected(true);
                        boolean hasMandarin = false;
                        for (TrackInfoBean subtitleTrackInfoBean : subtitleTrackList) {
                            if ("国语".equals(subtitleTrackInfoBean.language)) {
                                hasMandarin = true;
                                if (selectedIndex != subtitleTrackInfoBean.trackId) {
                                    ((IjkMediaPlayer) mediaPlayer).setTrack(subtitleTrackInfoBean.trackId);
                                    break;
                                }
                            }
                        }
                        if (!hasMandarin) {
                            ((IjkMediaPlayer) mediaPlayer).setTrack(subtitleTrackList.get(0).trackId);
                        }
                    }
                }
            }
        }
    }

    private String getSubtitleUrl(JSONObject object) {
        if (object == null) return "";
        String url = object.optString("url", "");
        if (!TextUtils.isEmpty(url) && !FileUtils.hasExtension(url)) {
            String format = object.optString("format", "");
            String name = object.optString("name", "字幕");
            String ext = ".srt";
            if ("text/x-ssa".equals(format)) {
                ext = ".ass";
            } else if ("text/vtt".equals(format)) {
                ext = ".vtt";
            } else if ("text/lrc".equals(format)) {
                ext = ".lrc";
            }
            String filename = name + (name.toLowerCase(Locale.ROOT).endsWith(ext) ? "" : ext);
            url += "#" + mController.encodeUrl(filename);
        }
        return url;
    }

    private boolean isLyricSubtitle(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("lyric") || value.contains("lrc") || name.contains("歌词");
    }

    private void clearLyricView() {
        if (mController == null || mController.getLyricView() == null) return;
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().destroy();
        mController.getLyricView().setText("");
    }

    private void initViewModel() {
        sourceViewModel = new SourceViewModel();
        preloadCoordinator = new PreloadCoordinator(sourceViewModel);
        playResultObserver = new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject info) {
                if (info == null) publishQuality(null);
                if (info != null) {
                    try {
                        if (isStalePlayResult(info)) {
                            LOG.i("echo-ignore stale play result");
                            return;
                        }
                        if (switchStopPending) {
                            // 换源点击即停后,旧源在途的取流结果不得再拉起播放
                            LOG.i("echo-ignore play result while source switching");
                            return;
                        }
                        mHandler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT);
                        publishQuality(info);
                        webPlayUrl = null;
                        progressKey = info.optString("proKey", null);
                        boolean parse = info.optString("parse", "1").equals("1");
                        boolean jx = info.optString("jx", "0").equals("1");
                        playSubtitle = info.optString("subt", "");
                        playLyric = info.optString("lyric", "");
                        lyricCacheKey = info.optString("lyricKey", null);
                        if (TextUtils.isEmpty(lyricCacheKey) && !TextUtils.isEmpty(progressKey)) {
                            lyricCacheKey = progressKey + "-lyric";
                        }
                        JSONArray lyrics = info.optJSONArray("lyrics");
                        if (lyrics != null && lyrics.length() > 0) {
                            playLyric = getSubtitleUrl(lyrics.optJSONObject(0));
                        }
                        JSONArray subtitles = info.optJSONArray("subs");
                        if (subtitles != null) {
                            for (int i = 0; i < subtitles.length(); i++) {
                                JSONObject obj = subtitles.optJSONObject(i);
                                if (obj == null) continue;
                                String url = getSubtitleUrl(obj);
                                String name = obj.optString("name", "");
                                if (isLyricSubtitle(name)) {
                                    if (TextUtils.isEmpty(playLyric)) playLyric = url;
                                } else if (TextUtils.isEmpty(playSubtitle)) {
                                    playSubtitle = url;
                                }
                            }
                        }
                        subtitleCacheKey = info.optString("subtKey", null);
                        String playUrl = info.optString("playUrl", "");
                        String flag = info.optString("flag");
                        Object rawUrl = info.opt("url");
                        String url = rawUrl instanceof JSONArray ? rawUrl.toString() : String.valueOf(rawUrl);
                        if(url.startsWith("[")){
                            url=mController.firstUrlByArray(url);
                        }
                        String artwork = info.optString("artwork", "");
                        if (TextUtils.isEmpty(artwork) && !TextUtils.isEmpty(playLyric) && mVodInfo != null) {
                            artwork = mVodInfo.pic;
                        }
                        playArtwork = artwork;
                        mVideoView.setArtwork(playArtwork);
                        String msg = info.optString("msg", "");
                        if (!TextUtils.isEmpty(msg)) {
                            handleResolvePlayUrlFailed(msg);
                            return;
                        }
                        // 取流成功,手动选线标记完成使命,后续失败恢复走正常自动策略
                        userPickedLine = false;
                        String danmaku = info.optString("danmaku", "").trim();
                        final String danmuProgressKey = progressKey;
                        HashMap<String, String> headers = null;
                        webUserAgent = null;
                        webHeaderMap = null;
                        headers = getHeaders(info);
                        if (headers != null) {
                            webHeaderMap = headers;
                            webUserAgent = getHeaderValue(headers, "user-agent");
                            if (webUserAgent != null) webUserAgent = webUserAgent.trim();
                        }
                        if (parse || jx) {
                            boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                            initParse(flag, userJxList, playUrl, url);
                        } else {
                            mController.showParse(false);
                            playUrl(playUrl + url, headers);
                        }
                        if (TextUtils.isEmpty(danmaku)) {
                            checkDanmu("");
                            searchDanmu("");
                        } else {
                            checkDanmu(danmaku, () -> {
                                if (TextUtils.equals(danmuProgressKey, progressKey)) {
                                    searchDanmu("");
                                }
                            });
                        }
                    } catch (Throwable th) {
                        handleResolvePlayUrlFailed("获取播放信息错误");
                    }
                } else {
//                    获取播放信息错误后只需再重试一次
                    handleResolvePlayUrlFailed("获取播放信息错误");
                }
            }
        };
        sourceViewModel.playResult.observeForever(playResultObserver);
    }

    public boolean selectQuality(int position) {
        if (qualityResult == null) return false;
        try {
            JSONArray urls = new JSONArray(qualityResult.optString("url"));
            String url = urls.optString(position * 2 + 1);
            if (TextUtils.isEmpty(url)) return false;
            String playUrl = qualityResult.optString("playUrl", "");
            String flag = qualityResult.optString("flag");
            boolean parse = qualityResult.optString("parse", "1").equals("1");
            boolean jx = qualityResult.optString("jx", "0").equals("1");
            HashMap<String, String> headers = getHeaders(qualityResult);
            if (parse || jx) {
                boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                initParse(flag, userJxList, playUrl, url);
            } else {
                mController.showParse(false);
                playUrl(playUrl + url, headers);
            }
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private void publishQuality(JSONObject info) {
        try {
            JSONArray urls = new JSONArray(info == null ? "" : info.optString("url"));
            if (urls.length() < 4 || urls.length() % 2 != 0) throw new JSONException("invalid quality urls");
            qualityResult = new JSONObject(info.toString());
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, qualityResult));
        } catch (Throwable th) {
            qualityResult = null;
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, null));
        }
    }

    private void searchDanmu(String danmaku) {
        if (!TextUtils.isEmpty(danmaku) || !DanmakuApi.canSearch() || mVodInfo == null) return;
        VodInfo.VodSeries series = getCurrentSeries(mVodInfo.playFlag, mVodInfo.playIndex);
        String key = progressKey;
        DanmakuApi.search(mVodInfo.name, series == null ? "" : series.name, new DanmakuApi.SearchCallback() {
            @Override
            public void onFound(String url) {
                if (!TextUtils.equals(key, progressKey)) return;
                checkDanmu(url);
            }

            @Override
            public void onNotFound() {
                if (!TextUtils.equals(key, progressKey)) return;
                checkDanmu("");
            }
        });
    }

    boolean isStalePlayResult(JSONObject info) {
        if (mVodInfo == null || mVodInfo.seriesMap == null || TextUtils.isEmpty(progressKey)) return false;
        String resultKey = info.optString("proKey", "");
        if (!TextUtils.isEmpty(resultKey) && !progressKey.equals(resultKey)) return true;
        String resultFlag = info.optString("flag", "");
        if (!TextUtils.isEmpty(resultFlag) && !resultFlag.equals(mVodInfo.playFlag)) return true;
        String sourceUrl = info.optString("key", "");
        if (!TextUtils.isEmpty(sourceUrl)) {
            VodInfo.VodSeries vs = getCurrentSeries(mVodInfo.playFlag, mVodInfo.playIndex);
            return vs != null && !sourceUrl.equals(vs.url);
        }
        return false;
    }

    public void setData(Bundle bundle) {
//        mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = App.getInstance().getVodInfo();
        sourceKey = bundle.getString("sourceKey");
        sourceBean = ApiConfig.get().getSource(sourceKey);
        ApiConfig.get().setCurrentPlaySourceKey(sourceKey);
        initPlayerCfg();
        triedLineFlags.clear();
        userPickedLine = bundle.getBoolean(EXTRA_USER_PICKED_LINE, false);
        play(false);
    }

    void initPlayerCfg() {
        try {
            mVodPlayerCfg = new JSONObject(mVodInfo.playerCfg);
        } catch (Throwable th) {
            mVodPlayerCfg = new JSONObject();
        }
        try {
            if (!mVodPlayerCfg.has("pl")) {
                // sourceBean 可能为空(切源窗口期 / 源被删,2026-09-13 补判空):
                // 原写法在这里 NPE,而本块 catch(Throwable) 是空的 —— 会静默跳过下面
                // pr/ijk/sc/sp/st/et 全部设置,播放器配置只剩半截。改为退回全局播放器设置。
                int sourcePlayerType = sourceBean == null ? -1 : sourceBean.getPlayerType();
                mVodPlayerCfg.put("pl", (sourcePlayerType == -1) ? (int) KV.get(HawkConfig.PLAY_TYPE, 2) : sourcePlayerType);
            }
            if (mVodPlayerCfg.optInt("pl", 2) == 0) {
                mVodPlayerCfg.put("pl", 2);
            }
            mVodPlayerCfg.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1));
            if (!mVodPlayerCfg.has("ijk")) {
                mVodPlayerCfg.put("ijk", KV.get(HawkConfig.IJK_CODEC, "硬解码"));
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", KV.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    public boolean onBackPressed() {
        int requestedOrientation = mActivity.getRequestedOrientation();
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            mController.setLandscapePortraitText("竖屏");
        }
        if (mController.onBackPressed()) {
            return true;
        }
        return false;
    }

    public void setExitingPreview(boolean exitingPreview) {
        this.exitingPreview = exitingPreview;
    }

    /**
     * 当前媒体是否有音频轨(2026-09-13 由"是否纯音频"拆出)。
     *
     * <p>拆分的理由:两件事被混在了一个判定里 ——
     * ① **要不要建 MediaSession / 前台服务通知**(用户要求播放影视也能下拉看到)→ 只要**有音频轨**即可;
     * ② **退后台是否保持播放**(见 [hostPause])→ 只有**纯音频**才保留,视频退后台仍按既有行为暂停。
     * 影视同样有音频轨,故通知对影视生效,而"退后台暂停视频"的行为不变。
     */
    private boolean hasPlayableAudio() {
        TrackInfo trackInfo = currentTrackInfo();
        return trackInfo != null && !trackInfo.getAudio().isEmpty();
    }

    /**
     * 是否为「纯音频」——**三态**:TRUE=有音轨且无视频轨、FALSE=确定是影视、**null=取不到轨道信息(未知)**。
     *
     * <p>「未知」必须与「假」分开,这是从旧的 `getAudioOnlyPlayback()`(返回可为 null 的 Boolean)继承的语义;
     * 2026-09-13 的 Hawk→KV 迁移把它抹平成 boolean,同一个概念在两处对「未知」给出了不同结论。各调用点的正确用法:
     * <ul>
     *   <li>[hostPause] 退后台是否保持播放:用 `!Boolean.TRUE.equals(...)` —— 只有确定是纯音频才不停,
     *       与迁移前一致(null 落到 pause 分支);</li>
     *   <li>播放器封面兜底(见 [updateMusicSession]):用 `Boolean.TRUE.equals(...)` —— 只有确定是纯音频才显示封面,
     *       未知时不显示(宁可不出封面,也不能冒把视频压成海报的风险)。</li>
     * </ul>
     */
    private Boolean isAudioOnlyPlayback() {
        TrackInfo trackInfo = currentTrackInfo();
        if (trackInfo == null || trackInfo.getAudio().isEmpty()) return null;
        return trackInfo.getVideo().isEmpty();
    }

    /**
     * 纯音频渲染兜底(2026-09-13):确认纯音频且当前是 SurfaceView 时热切换为 TextureView。
     * 动机与机制见 [MyVideoView.switchRenderToTexture];此处只处理 URL 预判([looksLikeAudioUrl])
     * 漏网的无后缀音乐直链 —— STATE_PLAYING 时轨道信息已就绪,判定与 [isAudioOnlyPlayback] 同源。
     * 换集/换源下一次起播 PlayerHelper.updateCfg 会按用户设置恢复渲染类型,影视不受影响。
     */
    private void ensureAudioOnlyRender() {
        if (mVideoView == null) return;
        if (!Boolean.TRUE.equals(isAudioOnlyPlayback())) return;
        if (mVideoView.isSurfaceRenderActive()) {
            mVideoView.switchRenderToTexture();
        }
    }

    /**
     * 常见纯音频直链后缀预判(仅用于起播前选渲染视图;误判无功能损失 —— TextureView 照常渲染视频)。
     * 注意只看去 query/fragment 后的后缀:音乐直链常带签名参数(.mp3?sign=...), playlist(m3u8) 绝不能命中。
     */
    private static boolean looksLikeAudioUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        int fragment = lower.indexOf('#');
        if (fragment >= 0) lower = lower.substring(0, fragment);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".opus") || lower.endsWith(".wma");
    }

    /** 取当前播放器的轨道信息;拿不到(未起播/不支持)返回 null */
    private TrackInfo currentTrackInfo() {
        if (mVideoView == null) return null;
        try {
            AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
            if (mediaPlayer instanceof IjkMediaPlayer) {
                return ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
            } else if (mediaPlayer instanceof ExoPlayer) {
                return ((ExoPlayer) mediaPlayer).getTrackInfo();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void updateMusicSession() {
        if (!MusicPlaybackService.isSupported(getContext())) return;
        if (switchingPlayback) return;
        // 有音频轨就维护会话与通知(影视/音乐一视同仁);拿不到轨道信息时沿用上一次的判定结果
        Boolean hasAudio = hasPlayableAudio();
        if (hasAudio) audioPlayback = true;
        // ⚠️ 封面(artworkView)盖在渲染 Surface 之上,只能给「纯音频」兜底,绝不能给影视占位:
        // 影视一旦在这里 setArtwork,视频就被压成一张海报 —— 现象「有声音、只有海报,EXO/IJK 一致、
        // 与分辨率无关」。三个条件缺一不可:
        //   ① Boolean.TRUE.equals(isAudioOnlyPlayback()):只有**确定**是纯音频才允许 —— 影视(FALSE)结构性杜绝;
        //      轨道信息未知(null)时也不放行(宁可不出封面,也不冒把视频压成海报的风险);
        //   ② 画面未就绪(!isStartedPlayState):兜底任何「画面已出却仍显示封面」的时序;
        //   ③ audioPlayback:沿用的既有门槛。
        // 2026-09-13 回归修复:此前条件只判 audioPlayback(只要有音轨就为 true)→ 压住所有视频。
        if (audioPlayback && Boolean.TRUE.equals(isAudioOnlyPlayback()) && mVideoView != null
                && !isStartedPlayState(mVideoView.getCurrentPlayState())
                && TextUtils.isEmpty(playArtwork) && mVodInfo != null && !TextUtils.isEmpty(mVodInfo.pic)) {
            playArtwork = mVodInfo.pic;
            mVideoView.setArtwork(playArtwork);
        }
        if (mVodInfo == null || mVideoView == null || !audioPlayback
                || mVideoView.getCurrentPlayState() == VideoView.STATE_ERROR
                || mVideoView.getCurrentPlayState() == VideoView.STATE_PLAYBACK_COMPLETED) {
            MusicPlaybackService.stop(getContext(), this);
            audioPlayback = false;
            return;
        }
        // 通知权限兜底(启动时已在 MainActivity 申请过一次):这里再调一次用于覆盖
        // "启动那次被拒、后来手动开启"的路径,已授权时 XXPermissions 秒回,无额外开销
        if (mActivity != null) PermissionHelper.requestNotificationIfNeeded(mActivity);
        VodInfo.VodSeries currentSeries = getCurrentSeries(mVodInfo.playFlag, mVodInfo.playIndex);
        String episode = currentSeries == null || TextUtils.isEmpty(currentSeries.name) ? "" : currentSeries.name;
        MusicPlaybackService.update(getContext(), this,
                TextUtils.isEmpty(mVodInfo.name) ? "TVBox" : mVodInfo.name,
                episode, mVodInfo.pic, mVideoView.getCurrentPosition(),
                mVideoView.getDuration(), mVideoView.isPlaying());
    }

    public void resumeFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.start();
            updateMusicSession();
        }
    }

    public void pauseFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.pause();
            updateMusicSession();
        }
    }

    public void stopFromMediaSession() {
        if (mVideoView != null) mVideoView.pause();
        MusicPlaybackService.stop(getContext(), this);
    }

    public void seekFromMediaSession(long position) {
        if (mVideoView != null) {
            mVideoView.seekTo(position);
            updateMusicSession();
        }
    }

    private VodInfo mVodInfo;
    private JSONObject mVodPlayerCfg;
    private String sourceKey;
    private SourceBean sourceBean;

    public void playNext(boolean isProgress) {
        triedLineFlags.clear();
        boolean hasNext;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = mVodInfo.playIndex + 1 < mVodInfo.seriesMap.get(mVodInfo.playFlag).size();
        }
        if (!hasNext) {
            Toast.makeText(mActivity, "已经是最后一集了!", Toast.LENGTH_SHORT).show();
            return;
        }else {
            mVodInfo.playIndex++;
        }
        reusePlayerOnSwitch = true;
        play(false);
    }

    public void playPrevious() {
        triedLineFlags.clear();
        boolean hasPre = true;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = mVodInfo.playIndex - 1 >= 0;
        }
        if (!hasPre) {
            Toast.makeText(mActivity, "已经是第一集了!", Toast.LENGTH_SHORT).show();
            return;
        }
        mVodInfo.playIndex--;
        reusePlayerOnSwitch = true;
        play(false);
    }

    private void showEpisodeDialog() {
        if (!isAttached() || mVodInfo == null || mVodInfo.seriesMap == null || TextUtils.isEmpty(mVodInfo.playFlag)) return;
        List<VodInfo.VodSeries> episodes = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (episodes == null || episodes.isEmpty()) return;
        String title = TextUtils.isEmpty(mVodInfo.name) ? "选集" : mVodInfo.name + " 选集";
        mController.getUiState().setEpisodeSheet(new EpisodeSheetState(
                title,
                episodes,
                mVodInfo.playIndex,
                position -> {
                    if (position < 0 || position >= episodes.size() || position == mVodInfo.playIndex) return kotlin.Unit.INSTANCE;
                    triedLineFlags.clear();
                    mVodInfo.playIndex = position;
                    reusePlayerOnSwitch = true;
                    play(false);
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private int autoRetryCount = 0;
    private long lastRetryTime = 0;  // 记录上次调用时间（毫秒）

    private boolean allowSwitchPlayer = true;
    private boolean hasAutoSwitchedPlayer = false;
    private int autoSwitchedPlayerType = -1;
    private boolean allowAutoSwitchLine = true;
    private boolean playbackStarted = false;
    private long playTimeoutBasePosition = 0;
    private java.util.Set<String> triedLineFlags = new java.util.HashSet<>();  // 记录已尝试过的线路
    /** 用户手动点选线路:本次取流失败/超时不自动换线换源,直接报错停留(避免覆盖用户选择) */
    private boolean userPickedLine = false;
    public static final String EXTRA_USER_PICKED_LINE = "userPickedLine";

    private void restoreAutoSwitchedPlayer() {
        if (autoSwitchedPlayerType < 0) return;
        releasePlayerOnSwitch = true;
        try {
            LOG.i("echo-autoRetry restore player: " + mVodPlayerCfg.optInt("pl", -1) + " -> " + autoSwitchedPlayerType);
            mVodPlayerCfg.put("pl", autoSwitchedPlayerType);
            mVodInfo.playerCfg = mVodPlayerCfg.toString();
            mController.setPlayerConfig(mVodPlayerCfg);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            autoSwitchedPlayerType = -1;
        }
    }

    boolean autoRetry() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRetryTime > 60_000){
            LOG.i("echo-reset-autoRetryCount");
            autoRetryCount = 0;
            allowSwitchPlayer = true;
            hasAutoSwitchedPlayer = false;
            triedLineFlags.clear();
        }

        lastRetryTime = currentTime;  // 更新上次调用时间
        if (loadFoundVideoUrls != null && loadFoundVideoUrls.size() > 0) {
            autoRetryFromLoadFoundVideoUrls();
            return true;
        }
        if (webPlayUrl != null) {
            if (allowSwitchPlayer && !hasAutoSwitchedPlayer) {
                LOG.i("echo-autoRetry switch player and replay current url");
                int playerType = mVodPlayerCfg.optInt("pl", -1);
                boolean switchSkipped = mController.switchPlayer();
                hasAutoSwitchedPlayer = true;
                allowSwitchPlayer = false;
                if (!switchSkipped) {
                    autoSwitchedPlayerType = playerType;
                    stopParse();
                    initParseLoadFound();
                    if(mVideoView!=null) mVideoView.release();
                    playUrl(webPlayUrl, webHeaderMap);
                    return true;
                }
            }
            LOG.i("echo-autoRetry current url failed after player switch, try next line");
            return tryNextLineIfEnabled();
        }
        return tryNextLineIfEnabled();
    }

    boolean tryNextLineIfEnabled() {
        restoreAutoSwitchedPlayer();
        if (allowAutoSwitchLine && KV.get(HawkConfig.AUTO_SWITCH_LINE, false)) return tryNextLine();
        LOG.i("echo-autoRetry line switching disabled");
        autoRetryCount = 0;
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        triedLineFlags.clear();
        return false;
    }

    boolean tryNextLine() {
        if (mVodInfo == null || mVodInfo.seriesMap == null || mVodInfo.seriesMap.isEmpty()) {
            autoRetryCount = 0;
            triedLineFlags.clear();
            return false;
        }
        // 将当前线路标记为已尝试
        String currentFlag = mVodInfo.playFlag;
        int currentIndex = Math.max(mVodInfo.playIndex, 0);
        VodInfo.VodSeries currentSeries = getCurrentSeries(currentFlag, currentIndex);
        if (!TextUtils.isEmpty(currentFlag)) {
            triedLineFlags.add(currentFlag);
        }
        List<String> lineFlags = getLineFlagsInDisplayOrder();
        int currentLineIndex = findLineFlagIndex(lineFlags, currentFlag);
        int startLineIndex = currentLineIndex >= 0 ? currentLineIndex + 1 : 0;
        // 查找下一条未尝试过的线路
        String nextFlag = null;
        int nextIndex = 0;
        for (int i = startLineIndex; i < lineFlags.size(); i++) {
            String flag = lineFlags.get(i);
            List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(flag);
            if (!triedLineFlags.contains(flag) && seriesList != null && !seriesList.isEmpty()) {
                nextFlag = flag;
                nextIndex = findSameEpisodeIndex(currentSeries, seriesList, currentIndex);
                break;
            }
        }
        if (nextFlag == null) {
            // 所有线路都已尝试过
            LOG.i("echo-autoRetry all lines exhausted");
            triedLineFlags.clear();
            autoRetryCount = 0;
            return requestDetailFallbackAfterLinesExhausted();
        }
        final String flagToSwitch = nextFlag;
        final String preProgressKey = progressKey;
        final long savedProgress = TextUtils.isEmpty(preProgressKey) ? 0 : getSavedProgress(preProgressKey);
        final long preProgress = Math.max(savedProgress, mVideoView == null ? 0 : mVideoView.getCurrentPosition());
        LOG.i("echo-autoRetry switch line: " + mVodInfo.playFlag + " -> " + flagToSwitch);
        // 显示切换线路提示
        if (isAttached()) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mContext, "线路切换至" + flagToSwitch, Toast.LENGTH_SHORT).show();
                }
            });
        }
        // 切换到新线路
        mVodInfo.playFlag = flagToSwitch;
        mVodInfo.playIndex = nextIndex;
        autoRetryCount = 0;
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        inheritProgressKey = preProgressKey;
        inheritProgress = preProgress;
        reusePlayerOnSwitch = true;
        play(false);
        return true;
    }

    private boolean requestDetailFallbackAfterLinesExhausted() {
        Activity activity = mActivity;
        if (!(activity instanceof DetailActivity)) {
            return false;
        }
        return ((DetailActivity) activity).startDetailFallbackAfterLinesExhausted();
    }

    private List<String> getLineFlagsInDisplayOrder() {
        List<String> lineFlags = new java.util.ArrayList<>();
        if (mVodInfo == null || mVodInfo.seriesMap == null) {
            return lineFlags;
        }
        if (mVodInfo.seriesFlags != null) {
            for (VodInfo.VodSeriesFlag flag : mVodInfo.seriesFlags) {
                if (flag != null && !TextUtils.isEmpty(flag.name) && mVodInfo.seriesMap.containsKey(flag.name) && !lineFlags.contains(flag.name)) {
                    lineFlags.add(flag.name);
                }
            }
        }
        for (String flag : mVodInfo.seriesMap.keySet()) {
            if (!TextUtils.isEmpty(flag) && !lineFlags.contains(flag)) {
                lineFlags.add(flag);
            }
        }
        return lineFlags;
    }

    private int findLineFlagIndex(List<String> lineFlags, String currentFlag) {
        if (lineFlags == null || TextUtils.isEmpty(currentFlag)) {
            return -1;
        }
        for (int i = 0; i < lineFlags.size(); i++) {
            if (currentFlag.equals(lineFlags.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private VodInfo.VodSeries getCurrentSeries(String flag, int index) {
        if (flag == null || mVodInfo == null || mVodInfo.seriesMap == null) {
            return null;
        }
        List<VodInfo.VodSeries> currentList = mVodInfo.seriesMap.get(flag);
        if (currentList == null || currentList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(index, currentList.size() - 1));
        return currentList.get(safeIndex);
    }

    private int findSameEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList, int fallbackIndex) {
        if (targetList == null || targetList.isEmpty()) {
            return 0;
        }
        if (targetList.size() == 1) {
            return 0;
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
        }
        int currentEpisode = extractEpisodeNumber(currentSeries.name);
        int matchedIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < targetList.size(); i++) {
            VodInfo.VodSeries targetSeries = targetList.get(i);
            int score = getEpisodeMatchScore(currentSeries.name, currentEpisode, targetSeries == null ? null : targetSeries.name);
            if (score > bestScore) {
                bestScore = score;
                matchedIndex = i;
            }
        }
        if (matchedIndex >= 0) {
            return matchedIndex;
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
    }

    private int getEpisodeMatchScore(String currentName, int currentEpisode, String targetName) {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(targetName)) {
            return 0;
        }
        if (targetName.equalsIgnoreCase(currentName)) {
            return 100;
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80;
        }
        String currentLower = currentName.toLowerCase(Locale.ROOT);
        String targetLower = targetName.toLowerCase(Locale.ROOT);
        if (currentEpisode < 0 && currentName.length() >= 2 && targetLower.contains(currentLower)) {
            return 70;
        }
        if (currentEpisode < 0 && targetName.length() >= 2 && currentLower.contains(targetLower)) {
            return 60;
        }
        return 0;
    }

    private int extractEpisodeNumber(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        try {
            String text = name.replaceAll("\\[.*?\\]|\\(.*?\\)", "");
            text = text.replaceAll("\\b(19|20)\\d{2}\\b", "");
            text = text.toLowerCase(Locale.ROOT).replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");
            Matcher matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            String number = text.replaceAll("\\D+", "");
            if (!TextUtils.isEmpty(number)) {
                return Integer.parseInt(number);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = loadFoundVideoUrls.poll();
        HashMap<String,String> header = loadFoundVideoUrlsHeader.get(videoUrl);
        playUrl(videoUrl, header);
    }

    void initParseLoadFound() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new LinkedList<String>();
        loadFoundVideoUrlsHeader = new HashMap<String, HashMap<String, String>>();
    }

    public void setPlayTitle(boolean show)
    {
        if(show){
            String playTitleInfo= "";
            if(mVodInfo!=null){
                playTitleInfo = mVodInfo.name + " " + mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex).name;
            }
            mController.setTitle(playTitleInfo);
        }else {
            mController.setTitle("");
        }
    }

    public void play(boolean reset) {
        // 新播放是用户显式请求(换源落地/回滚重播):解除换源停播抑制。
        // 置于 mVodInfo 判空之前,避免异常态下 play 早退把抑制永久留在置位状态
        switchStopPending = false;
        // 预载失效事件(切集/换线/换源/重播):作废在途预解析与预载数据,稳定播放后重新评估(规格 §6)
        if (preloadCoordinator != null) preloadCoordinator.invalidate();
        hidePreloadReady();
        if(mVodInfo==null)return;
        boolean reusePlayer = reusePlayerOnSwitch && !releasePlayerOnSwitch;
        reusePlayerOnSwitch = false;
        releasePlayerOnSwitch = false;
        switchingPlayback = true;
        audioPlayback = false;
        playArtwork = "";
        exitingPreview = false;
        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodInfo));
        if (reusePlayer) {
            PlayerTipBridge.setTip("", true, false);
        } else {
            setTip("正在获取播放信息", true, false);
        }
        String playTitleInfo = mVodInfo.name + " " + vs.name;
        mController.setTitle(playTitleInfo);

        stopParse();
        playbackStarted = false;
        playTimeoutBasePosition = 0;
        webPlayUrl = null;
        webHeaderMap = null;
        initParseLoadFound();
        allowSwitchPlayer=true;
        hasAutoSwitchedPlayer=false;
        mController.stopOther();
        resetDanmuState();
        clearLyricView();
        mVideoView.clearArtwork();
        if(mVideoView!=null) {
            if (reusePlayer) {
                long previousPosition = mVideoView.getCurrentPosition();
                if (previousPosition > 0 && !TextUtils.isEmpty(progressKey)) {
                    CacheManager.save(MD5.string2MD5(progressKey), previousPosition);
                }
                AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
                if (mediaPlayer != null) {
                    mVideoView.clearVideoFrame();
                }
            } else {
                mVideoView.release();
            }
        }
        ImgUtil.clearMemoryCache();
        subtitleCacheKey = mVodInfo.sourceKey + "-" + mVodInfo.id + "-" + mVodInfo.playFlag + "-" + mVodInfo.playIndex+ "-" + vs.name + "-subt";
        progressKey = mVodInfo.sourceKey + mVodInfo.id + mVodInfo.playFlag + mVodInfo.playIndex + vs.name;
        startResolvePlayUrlTimeout();
        // 换源点击即停前记下的进度:新源 progressKey 不同,交由 inheritProgressIfNeeded 写进新键缓存接着看
        // (新键已有历史记录则不覆盖);回滚原源时键相同,停播 release 已落盘,该方法会直接跳过
        if (pendingInheritProgress > 0 && !TextUtils.isEmpty(pendingInheritKey)) {
            inheritProgressKey = pendingInheritKey;
            inheritProgress = pendingInheritProgress;
            LOG.i("echo-switchSource inherit progress " + pendingInheritProgress + "ms from " + pendingInheritKey);
        }
        pendingInheritKey = null;
        pendingInheritProgress = 0;
        //重新播放清除现有进度
        if (reset) {
            CacheManager.delete(MD5.string2MD5(progressKey), 0);
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), 0);
        }else{
            inheritProgressIfNeeded();
            try{
                int playerType = mVodPlayerCfg.getInt("pl");
                if(playerType==1){
                    mController.getSubtitleView().setVisibility(View.VISIBLE);
                }else {
                    mController.getSubtitleView().setVisibility(View.GONE);
                }
            }catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if(Jianpian.isJpUrl(vs.url)){//荐片地址特殊判断
            String jp_url= vs.url;
            mController.showParse(false);
            if(vs.url.startsWith("tvbox-xg:")){
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
            }else {
                playUrl(Jianpian.JPUrlDec(jp_url), null);
            }
            return;
        }
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (code < 0) {
                    setTip(info, false, true);
                } else {
                    setTip(info, true, false);
                }
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(url, null);
            }
        })) {
            mController.showParse(false);
            return;
        }
        
        if (preloadCoordinator != null) {
            JSONObject preResult = preloadCoordinator.consumeResult(progressKey);
            if (preResult != null) {
                playResultObserver.onChanged(preResult);
                return;
            }
            // 未复用 = 切到的不是预载目标集(或缓存过期):预载数据失效,清掉
            preloadCoordinator.dropPreloadData();
        }
        sourceViewModel.getPlay(sourceKey, mVodInfo.playFlag, progressKey, vs.url, subtitleCacheKey);
    }


    private PreloadCoordinator.Snapshot buildPreloadSnapshot() {
        try {
            if (mVodInfo == null || mVodInfo.seriesMap == null) return null;
            List<VodInfo.VodSeries> episodes = mVodInfo.seriesMap.get(mVodInfo.playFlag);
            if (episodes == null || mVodInfo.playIndex < 0 || mVodInfo.playIndex + 1 >= episodes.size()) return null;
            VodInfo.VodSeries next = episodes.get(mVodInfo.playIndex + 1);
            if (next == null || TextUtils.isEmpty(next.url)) return null;
            int nextIndex = mVodInfo.playIndex + 1;
            String nextKey = mVodInfo.sourceKey + mVodInfo.id + mVodInfo.playFlag + nextIndex + next.name;
            String nextSubtKey = mVodInfo.sourceKey + "-" + mVodInfo.id + "-" + mVodInfo.playFlag + "-" + nextIndex + "-" + next.name + "-subt";
            long startSkipMs = mVodPlayerCfg == null ? 0 : mVodPlayerCfg.optInt("st", 0) * 1000L;
            // 内核判定(预载方案):取实际播放器实例,非 app ExoPlayer 时协调器跳过预载(规格 §1)
            AbstractPlayer mediaPlayer = mVideoView == null ? null : mVideoView.getMediaPlayer();
            boolean exoKernel = mediaPlayer instanceof ExoPlayer;
            return new PreloadCoordinator.Snapshot(mContext, sourceKey, mVodInfo.playFlag, progressKey, nextKey, next.url, nextSubtKey, startSkipMs, exoKernel);
        } catch (Throwable th) {
            LOG.i("echo-preload-skip: snapshot error " + th);
            return null;
        }
    }

    private void inheritProgressIfNeeded() {
        try {
            if (TextUtils.isEmpty(inheritProgressKey) || TextUtils.isEmpty(progressKey)) return;
            if (TextUtils.equals(inheritProgressKey, progressKey)) return;
            if (inheritProgress <= 0) return;
            Object targetCache = CacheManager.getCache(MD5.string2MD5(progressKey));
            if (targetCache == null) {
                CacheManager.save(MD5.string2MD5(progressKey), inheritProgress);
            }
        } finally {
            inheritProgressKey = null;
            inheritProgress = 0;
        }
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;
    private String inheritProgressKey;
    private long inheritProgress;
    private String parseFlag;
    private String webUrl;
    private String webUserAgent;
    private HashMap<String, String > webHeaderMap;
    private String webPlayUrl;
    private String m3u8ProxyUrl;
    private String m3u8SourceUrl;

    private void initParse(String flag, boolean useParse, String playUrl, final String url) {
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        mController.showParse(useParse);
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean);
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        JSONObject playData = jsonPlayData.optJSONObject("data");
        if (playData == null) {
            playData = jsonPlayData;
        }
        String url = playData.optString("url", jsonPlayData.optString("url", ""));
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        boolean parse = false;
        if (url.startsWith("video://")) {
            url = url.substring(8);
            parse = true;
        }
        url = DefaultConfig.checkReplaceProxy(url);
        if (!url.startsWith("http") && !url.startsWith("data:application")) {
            return null;
        }
        parse = parse || playData.optInt("parse", jsonPlayData.optInt("parse", 0)) == 1;
        JSONObject headers = new JSONObject();
        HashMap<String, String> headerMap = getHeaders(jsonPlayData);
        HashMap<String, String> dataHeaderMap = getHeaders(playData);
        if (headerMap != null) putHeaders(headers, headerMap);
        if (dataHeaderMap != null) putHeaders(headers, dataHeaderMap);
        String ua = playData.optString("user-agent", jsonPlayData.optString("user-agent", ""));
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = playData.optString("referer", jsonPlayData.optString("referer", ""));
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        taskResult.put("parse", parse ? 1 : 0);
        return taskResult;
    }

    private HashMap<String, String> getHeaders(JSONObject object) {
        if (object == null) return null;
        HashMap<String, String> headers = new HashMap<>();
        appendHeaders(headers, object.opt("header"));
        appendHeaders(headers, object.opt("headers"));
        return headers.isEmpty() ? null : headers;
    }

    private void appendHeaders(HashMap<String, String> headers, Object rawHeaders) {
        if (rawHeaders == null || rawHeaders == JSONObject.NULL) return;
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

    private void putHeaders(JSONObject target, HashMap<String, String> headers) throws JSONException {
        if (target == null || headers == null) return;
        for (String key : headers.keySet()) {
            target.put(key, headers.get(key));
        }
    }

    private String getHeaderValue(HashMap<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return headers.get(key);
            }
        }
        return null;
    }

    void startResolvePlayUrlTimeout() {
        cancelPlayTimeout();
        mHandler.sendEmptyMessageDelayed(MSG_RESOLVE_PLAY_URL_TIMEOUT, getResolvePlayUrlTimeoutMs());
    }

    private long getResolvePlayUrlTimeoutMs() {
        if (sourceBean == null) return RESOLVE_PLAY_URL_TIMEOUT_MS;
        return Math.max(RESOLVE_PLAY_URL_TIMEOUT_MS, (sourceBean.getPlayTimeoutSeconds() + 1L) * 1000L);
    }

    void startSwitchLinePlayTimeout() {
        if (!allowAutoSwitchLine) {
            cancelPlayTimeout();
            return;
        }
        cancelPlayTimeout();
        LOG.i("echo-switchLinePlay start timeout");
        mHandler.sendEmptyMessageDelayed(MSG_SWITCH_LINE_PLAY_TIMEOUT, SWITCH_LINE_PLAY_TIMEOUT_MS);
    }

    void cancelSwitchLinePlayTimeout() {
        cancelPlayTimeout();
    }

    void cancelPlayTimeout() {
        mHandler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT);
        mHandler.removeMessages(MSG_SWITCH_LINE_PLAY_TIMEOUT);
    }

    public void setAutoSwitchLineEnabled(boolean enabled) {
        allowAutoSwitchLine = enabled;
        if (!enabled) {
            cancelPlayTimeout();
            triedLineFlags.clear();
        }
    }

public void setPreviewMode(boolean previewMode) {
this.previewMode = previewMode;
if (mController != null) {
mController.setPreviewMode(previewMode);
mController.getLyricView().setTextSize(previewMode ? 16 : 24);
}
}

public void toggleControllerControls() {
if (mController != null) {
mController.toggleControlBar();
}
}

    public void stopForSourceSwitch(String tip) {
        if (mVideoView == null) return;
        cancelPlayTimeout();
        stopParse();
        playbackStarted = false;
        playTimeoutBasePosition = 0;
        switchStopPending = true;
        reusePlayerOnSwitch = false;
        releasePlayerOnSwitch = false;
        stopMusicSessionForFailedPlayback();
        
        long position = mVideoView.getCurrentPosition();
        pendingInheritKey = progressKey;
        pendingInheritProgress = position;
        mVideoView.pause();
        mVideoView.release();
        if (mController != null) mController.stopOther();
        resetDanmuState();
        webPlayUrl = null;
        webHeaderMap = null;
        initParseLoadFound();
        LOG.i("echo-switchSource stop at " + position + "ms, key=" + pendingInheritKey);
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false);
    }

    public void clearSourceSwitchTip() {
        if (!switchStopPending) return;
        hideTipOnUiThread();
    }

    void markPlaybackStarted() {
        playbackStarted = true;
        cancelPlayTimeout();
    }

    boolean isPlaybackStarted() {
        if (playbackStarted) return true;
        if (mVideoView == null) return false;
        int state = mVideoView.getCurrentPlayState();
        return isStartedPlayState(state) || hasPlaybackProgress(mVideoView.getCurrentPosition()) || mVideoView.isPlaying();
    }

    boolean isStartedPlayState(int state) {
        return state == VideoView.STATE_PREPARED || state == VideoView.STATE_BUFFERED || state == VideoView.STATE_PLAYING;
    }

    boolean hasPlaybackProgress(long progress) {
        return progress > Math.max(playTimeoutBasePosition, 0) + 1000;
    }

    void handleResolvePlayUrlTimeout() {
        LOG.i("echo-resolvePlayUrl timeout, try next line");
        if (sourceViewModel != null) sourceViewModel.cancelPlayRequest();
        stopParse();
        if (userPickedLine) {
            userPickedLine = false;
            stopMusicSessionForFailedPlayback();
            setTip("获取播放地址超时", false, true);
            return;
        }
        if (!tryNextLineIfEnabled()) {
            stopMusicSessionForFailedPlayback();
            setTip("获取播放地址超时", false, true);
        }
    }

    void handleResolvePlayUrlFailed(String err) {
        LOG.i("echo-resolvePlayUrl failed, try next line: " + err);
        if (sourceViewModel != null) sourceViewModel.cancelPlayRequest();
        stopParse();
        if (userPickedLine) {
            userPickedLine = false;
            cancelPlayTimeout();
            stopMusicSessionForFailedPlayback();
            setTip(err, false, true);
            return;
        }
        if (tryNextLineIfEnabled()) return;
        cancelPlayTimeout();
        stopMusicSessionForFailedPlayback();
        setTip(err, false, true);
    }

    void handleSwitchLinePlayTimeout() {
        int state = mVideoView == null ? -1 : mVideoView.getCurrentPlayState();
        LOG.i("echo-switchLinePlay timeout state: " + state + ", started: " + playbackStarted);
        if (isPlaybackStarted()) {
            cancelPlayTimeout();
            hideTipOnUiThread();
            return;
        }
        LOG.i("echo-switchLinePlay timeout, try next line");
        stopParse();
        if (hasAutoSwitchedPlayer) {
            if (!tryNextLineIfEnabled()) {
                stopMusicSessionForFailedPlayback();
                setTip("播放超时", false, true);
            }
            return;
        }
        if (!autoRetry()) {
            stopMusicSessionForFailedPlayback();
            setTip("播放超时", false, true);
        }
    }

    void stopParse() {
        mHandler.removeMessages(MSG_PARSE_TIMEOUT);
        stopLoadWebView(false);
        OkGo.getInstance().cancelTag("play");
        OkGo.getInstance().cancelTag("json_jx");
        if (parseThreadPool != null) {
            try {
                parseThreadPool.shutdown();
                parseThreadPool = null;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    ExecutorService parseThreadPool;

    private void doParse(ParseBean pb) {
        stopParse();
        initParseLoadFound();
        if (pb.getType() == 4) {
            parseMix(pb,true);
        }
        else if (pb.getType() == 0) {
            setTip("正在嗅探播放地址", true, false);
            mHandler.removeMessages(MSG_PARSE_TIMEOUT);
            mHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, 20 * 1000);
            if(pb.getExt()!=null){
                // 解析ext
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    HashMap<String, String> headerMap = getHeaders(jsonObject);
                    if (headerMap != null) {
                        for (String key : headerMap.keySet()) {
                            if (key.equalsIgnoreCase("user-agent")) {
                                webUserAgent = headerMap.get(key).trim();
                            } else {
                                reqHeaders.put(key, headerMap.get(key));
                            }
                        }
                        if(reqHeaders.size()>0)webHeaderMap = reqHeaders;
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            loadWebView(pb.getUrl() + webUrl);

        } else if (pb.getType() == 1) { // json 解析
            setTip("正在解析播放地址", true, false);
            // 解析ext
            HttpHeaders reqHeaders = new HttpHeaders();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                HashMap<String, String> headerMap = getHeaders(jsonObject);
                if (headerMap != null) {
                    for (String key : headerMap.keySet()) {
                        reqHeaders.put(key, headerMap.get(key));
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            OkGo.<String>get(pb.getUrl() + mController.encodeUrl(webUrl))
                    .tag("json_jx")
                    .headers(reqHeaders)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                HashMap<String, String> headers = getHeaders(rs);
                                if (rs.optInt("parse", 0) == 1) {
                                    webHeaderMap = headers;
                                    if (headers != null) {
                                        webUserAgent = getHeaderValue(headers, "user-agent");
                                        if (webUserAgent != null) webUserAgent = webUserAgent.trim();
                                    }
                                    loadWebView(DefaultConfig.checkReplaceProxy(rs.getString("url")));
                                } else {
                                    playUrl(rs.getString("url"), headers);
                                }
                            } catch (Throwable e) {
                                e.printStackTrace();
                                errorWithRetry("解析错误", false);
//                                setTip("解析错误", false, true);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            errorWithRetry("解析错误", false);
//                            setTip("解析错误", false, true);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), p.mixUrl());
                }
            }
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject rs = ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误", false);
                        setTip("解析错误", false, true);
                    } else {
                        HashMap<String, String> headers = getHeaders(rs);
                        if (rs.has("jxFrom")) {
                            if(!isAttached())return;
                            mActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(mContext, "解析来自:" + rs.optString("jxFrom"), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        boolean parseWV = rs.optInt("parse", 0) == 1;
                        if (parseWV) {
                            String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            loadUrl(wvUrl);
                        } else {
                            playUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
             parseMix(pb,false);
        }
    }

    private void parseMix(ParseBean pb,boolean isSuper)
    {
        setTip("正在解析播放地址", true, false);
        parseThreadPool = Executors.newSingleThreadExecutor();
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        LinkedHashMap<String, String> json_jxs = new LinkedHashMap<>();
        String extendName = "";
        for (ParseBean p : ApiConfig.get().getParseBeanList()) {
            HashMap<String, String> data = new HashMap<String, String>();
            data.put("url", p.getUrl());
            if (p.getUrl().equals(pb.getUrl())) {
                extendName = p.getName();
            }
            data.put("type", p.getType() + "");
            data.put("ext", p.getExt());
            jxs.put(p.getName(), data);

            if (p.getType() == 1) {
                json_jxs.put(p.getName(), p.mixUrl());
            }
        }
        String finalExtendName = extendName;
        // BugReview #21:目标解析器在会话开始时构建并按调用传递,替代静态字段跨线程读取
        SuperParse.ParseTargets parseTargets = SuperParse.buildTargets(jxs, parseFlag + "123");
        parseThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                if(isSuper){
                    //并发执行 嗅探和json
                    JSONObject rs = SuperParse.parse(jxs, parseFlag+"123", webUrl, parseTargets);
                    if (!rs.has("url") || rs.optString("url").isEmpty()) {
                        setTip("解析错误", false, true);
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            setTip("超级解析中", true, false);

                            if(!isAttached())return;
                            mActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                                    mHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, 20 * 1000);
                                    loadWebView(mixParseUrl);
                                }
                            });
                            parseThreadPool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    JSONObject res = SuperParse.doJsonJx(parseTargets.jsonJx, webUrl);
                                    rsJsonJX(res, true);
                                }
                            });
                        } else {
                            rsJsonJX(rs,false);
                        }
                    }
                }else {
                    JSONObject rs = ApiConfig.get().jsonExtMix(parseFlag + "111", pb.getUrl(), finalExtendName, jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误", false);
                        setTip("解析错误", false, true);
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            if(!isAttached())return;
                            mActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    setTip("正在嗅探播放地址", true, false);
                                    mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                                    mHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, 20 * 1000);
                                    loadWebView(mixParseUrl);
                                }
                            });
                        } else {
                            rsJsonJX(rs,false);
                        }
                    }
                }
            }
        });
    }

    private void rsJsonJX(JSONObject rs,boolean isSuper){
        if(isSuper){
            if(rs==null || !rs.has("url"))return;
            stopLoadWebView(false);
        }
        HashMap<String, String> headers = getHeaders(rs);
        if (rs.has("jxFrom")) {
            if(!isAttached())return;
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mContext, "解析来自:" + rs.optString("jxFrom"), Toast.LENGTH_SHORT).show();
                }
            });
        }
        playUrl(rs.optString("url", ""), headers);
    }
    public MyVideoView getPlayer() {
        return mVideoView;
    }

    // webview
    private WebView mSysWebView;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private LinkedList<String> loadFoundVideoUrls = new LinkedList<>();
    private HashMap<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);

    void loadWebView(String url) {
        if (mSysWebView == null) {
            initWebView();
        }
        loadUrl(url);
    }

    void initWebView() {
        mSysWebView = new MyWebView(mContext);
        configWebViewSys(mSysWebView);
    }

    void loadUrl(String url) {
        if(!isAttached())return;
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    if(webUserAgent != null) {
                        mSysWebView.getSettings().setUserAgentString(webUserAgent);
                    }
                    //mSysWebView.clearCache(true);
                    if(webHeaderMap != null){
                        mSysWebView.loadUrl(url,webHeaderMap);
                    }else {
                        mSysWebView.loadUrl(url);
                    }
                }
            }
        });
    }

    void stopLoadWebView(boolean destroy) {
        if (mActivity == null) return;
        if(!isAttached())return;
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    mSysWebView.loadUrl("about:blank");
                    if (destroy) {
                        mSysWebView.clearCache(true);
                        mSysWebView.removeAllViews();
                        mSysWebView.destroy();
                        mSysWebView = null;
                    }
                }
            }
        });
    }

    boolean checkVideoFormat(String url) {
        try{
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean != null && sourceBean.getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp != null && sp.manualVideoCheck()){
                    return sp.isVideoFormat(url);
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        }catch (Exception e){
            return false;
        }
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayContainer.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if(!isAttached())return;
        mActivity.addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setBlockNetworkImage(true);
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 添加webView配置 */
        //设置编码
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());
//         settings.setUserAgentString(ANDROID_UA);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        SysWebClient mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private class SysWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            sslErrorHandler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted( view,  url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            LOG.i("echo-onPageFinished url:" + url);
            if(!url.equals("about:blank")){
                mController.evaluateScript(sourceBean,url,view);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i( "shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("echo-loadFoundVideoUrl:" + url );
                    if (loadFoundCount.incrementAndGet() == 1) {
                        stopLoadWebView(false);
                        SuperParse.stopJsonJx();
                        url = loadFoundVideoUrls.poll();
                        mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if(!TextUtils.isEmpty(cookie))headers.put("Cookie", " " + cookie);//携带cookie
                        playUrl(url, headers);
                    }
                }
            }

            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            LOG.i("echo-shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k," " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }

        @Override
        public void onLoadResource(WebView webView, String url) {
            super.onLoadResource(webView, url);
        }
    }

}
