package com.github.tvbox.osc.ui.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.view.LayoutInflater;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import android.widget.FrameLayout;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.dlna.CastVideo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.PageHost;
import com.github.tvbox.osc.player.PlaybackEngine;
import com.github.tvbox.osc.player.PlaybackService;
import com.github.tvbox.osc.player.PlaybackController;
import com.github.tvbox.osc.player.PlaybackHostApi;
import com.github.tvbox.osc.player.PlaybackSession;
import com.github.tvbox.osc.player.PlaybackViewBridge;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.ComposeVideoController;
import com.github.tvbox.osc.player.controller.PlayerControlApi;
import com.github.tvbox.osc.player.controller.VodControlListener;
import com.github.tvbox.osc.player.danmu.DanmuLoadController;
import com.github.tvbox.osc.player.state.CastSheetState;
import com.github.tvbox.osc.player.state.DanmuSearchSheetState;
import com.github.tvbox.osc.player.state.DanmuSettingSheetState;
import com.github.tvbox.osc.player.state.EpisodeSheetState;
import com.github.tvbox.osc.player.state.PlayerUiState;
import com.github.tvbox.osc.player.state.SelectDialogState;
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState;
import com.github.tvbox.osc.player.state.SubtitleSheetState;
import me.jessyan.autosize.internal.CustomAdapt;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.KV;
import androidx.media3.common.text.Cue;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import me.jessyan.autosize.AutoSize;
import master.flame.danmaku.ui.widget.DanmakuView;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayContainer extends FrameLayout implements CustomAdapt, PlaybackHostApi {

    /** BugReview #17:轨道切换延迟恢复序号;窗口期内切内核/连续切换/页面销毁后旧回调作废 */
    private final AtomicInteger trackSwitchSeq = new AtomicInteger(0);
    /**
     * 播放会话与派生数据(P1 第一步,播放服务化 Spec §2.1/§3):播什么(vod / 源 key / 播放器配置)、
     * 进度与字幕缓存键、线路与剧集匹配、清晰度、投屏地址改写、请求头提取。
     * P2 起随前台服务/引擎走,页面只通过 {@link PlaybackHostApi} 下指令。
     *
     * <p>P5 起唯一形态:构造期同步取 {@link PlaybackEngine#controller()},之后不再变更(旧路径的页面自建控制器已删除)。
     */
    private PlaybackController scheduler;
    /** 引擎侧渲染容器的显示宿主(P2 挂摘协议:进入页面=搬进来,离开=摘回引擎) */
    private FrameLayout surfaceSlot;
    /** 播放引擎(P2):页面只借它的 player/controller,不得 release */
    private PlaybackEngine engine;
    /** 页面能力(P0:收口原先对 DetailActivity 的 instanceof 回调;P2 起改为弱引用注册) */
    private PageHost pageHost;
    private Activity mActivity;
    private final Context mContext;

    public PlayContainer(@NonNull Activity activity) {
        super(activity);
        mActivity = activity;
        mContext = activity;
        // 调度层归属(P2 起唯一形态):引擎与页面构造同帧取出 —— 不能"先自建控制器、服务就绪再替换",
        // 否则要处理在途取流结果/观察者双投递
        engine = PlaybackService.engine(activity);
        scheduler = engine.controller();
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth());
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true);
        // 新容器创建即清掉全局桥里上一个页面实例的提示残留(如源站错误文案)
        PlayerTipBridge.hide();
        init();
        // 调度层 → 视图侧的回调入口(P1 第二组:重试/换线/超时全部经 viewBridge)
        scheduler.setViewBridge(viewBridge);
        // 页面挂载:搬渲染容器进槽位 + 把视图桥切到本页面(提示/弹幕/字幕/控制器动作都在页面)
        if (engine != null) engine.attach(this, surfaceSlot);
    }

    /** P2:页面视图桥(引擎挂载期把它设为控制器的视图桥,见 PlaybackEngine.attach) */
    public PlaybackViewBridge viewBridge() {
        return viewBridge;
    }

    /** P2:引擎释放(宿主服务销毁/任务移除)时回调:页面立刻放弃对播放器视图的引用 */
    public void onServiceStopped() {
        mVideoView = null;
        engine = null;
        // 引擎没了,页面可能仍然活着(空闲 TTL 释放时页面并未销毁):补一次 EventBus 解注册,
        // 否则 refresh() 这类订阅回调会在这之后继续打到已经没有播放器的页面上
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    private boolean isAttached() {
        if (pageHost != null) return pageHost.isPageAlive();
        return mActivity != null && !mActivity.isFinishing();
    }

    /** 由页面在创建容器后注册(替代原先"直接依赖 DetailActivity"的两处 instanceof 回调) */
    public void setPageHost(PageHost host) {
        this.pageHost = host;
    }

    /**
     * 视图契约(P1 第二组,`skill/avbox-playback-service-spec.md` §3-P1):`PlaybackController` 的
     * "重试/换线/超时"决策要用到的播放动作与提示入口。
     *
     * <p>用匿名实现而不让 PlayContainer `implements`:避免把调度内部用到的动作扩散成容器公开 API;
     * P2 起这份实现将改由"服务 → 页面"的桥提供(服务持有播放器,页面只留显示宿主与提示层)。
     */
    private final PlaybackViewBridge viewBridge = new PlaybackViewBridge() {
        @Override
        public boolean isPageAlive() {
            return isAttached();
        }

        @Override
        public void runOnUi(Runnable action) {
            if (isAttached() && mActivity != null) mActivity.runOnUiThread(action);
        }

        @Override
        public void toast(CharSequence text) {
            Toast.makeText(mContext, text, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void showTip(String msg, boolean loading, boolean error) {
            setTip(msg, loading, error);
        }

        @Override
        public void hideTipOnUiThread() {
            PlayContainer.this.hideTipOnUiThread();
        }

        @Override
        public int currentPlayState() {
            return mVideoView == null ? -1 : mVideoView.getCurrentPlayState();
        }

        @Override
        public long currentPosition() {
            return mVideoView == null ? 0 : mVideoView.getCurrentPosition();
        }

        @Override
        public boolean isPlaying() {
            return mVideoView != null && mVideoView.isPlaying();
        }

        @Override
        public long duration() {
            return mVideoView == null ? 0 : mVideoView.getDuration();
        }

        @Override
        public AbstractPlayer mediaPlayer() {
            return mVideoView == null ? null : mVideoView.getMediaPlayer();
        }

        @Override
        public Context context() {
            return mContext;
        }

        @Override
        public PlaybackHostApi playbackHost() {
            return PlayContainer.this;
        }

        @Override
        public void requestNotificationPermission() {
            if (pageHost != null) {
                pageHost.requestNotificationPermission();
            } else if (mActivity != null) {
                PermissionHelper.requestNotificationIfNeeded(mActivity);
            }
        }

        @Override
        public void switchRenderToTexture() {
            if (mVideoView != null && mVideoView.isSurfaceRenderActive()) {
                mVideoView.switchRenderToTexture();
            }
        }

        @Override
        public void ensureRenderViewMatchesConfig() {
            if (mVideoView != null) mVideoView.ensureRenderViewMatchesConfig();
        }

        @Override
        public void releasePlayer() {
            releasePlayerKernel();
        }

        @Override
        public void setTitle(String title) {
            if (mController != null) mController.setTitle(title);
        }

        @Override
        public void stopOtherPlayers() {
            if (mController != null) mController.stopOther();
        }

        @Override
        public void resetDanmu() {
            resetDanmuState();
        }

        @Override
        public void startDanmuIfReady() {
            PlayContainer.this.startDanmuIfReady();
        }

        @Override
        public void clearLyric() {
            clearLyricView();
        }

        @Override
        public void clearArtwork() {
            if (mVideoView != null) mVideoView.clearArtwork();
        }

        @Override
        public void clearVideoFrame() {
            if (mVideoView != null) mVideoView.clearVideoFrame();
        }

        @Override
        public void setSubtitleViewVisible(boolean visible) {
            if (mController == null) return;
            mController.getSubtitleView().setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onNewPlayStarted() {
            // 新一次播放:退出预览态标记复位(退后台暂停语义依赖它,见 hostPause)
            exitingPreview = false;
        }

        @Override
        public void applyPlayerConfigToView(int forceKernel) {
            if (mVideoView == null) return;
            if (forceKernel > 0) {
                PlayerHelper.updateCfg(mVideoView, scheduler.playerCfg(), forceKernel);
            } else {
                PlayerHelper.updateCfg(mVideoView, scheduler.playerCfg());
            }
        }

        @Override
        public void useTextureRenderForAudio() {
            if (mVideoView != null) mVideoView.setRenderViewFactory(TextureRenderViewFactory.create());
        }

        @Override
        public boolean playExternalPlayer(int playerType, String url, String title, String subtitle,
                                         HashMap<String, String> headers, long progress) {
            if (mActivity == null) return false;
            return PlayerHelper.runExternalPlayer(playerType, mActivity, url, title, subtitle, headers, progress);
        }

        @Override
        public void playM3u8(String url, HashMap<String, String> headers) {
            if (mController != null) mController.playM3u8(url, headers);
        }

        @Override
        public void startVideoPlayback(String url, HashMap<String, String> headers, boolean forceExoPlayer) {
            if (mVideoView == null) return;
            mController.hidePauseRoot();
            boolean reusePlayer = !forceExoPlayer && mVideoView.getMediaPlayer() != null;
            if (!reusePlayer) hideTip();
            if (!reusePlayer && mVideoView.getMediaPlayer() != null) {
                releasePlayerKernel();
            }
            mVideoView.setProgressKey(scheduler.progressKey());
            // 内容真正交给播放器 → 记录归属(D6 接管的可信依据;见 PlaybackController.startedPlaybackKey)
            scheduler.markContentStarted();
            if (headers != null) {
                mVideoView.setUrl(url, headers);
            } else {
                mVideoView.setUrl(url);
            }
            scheduler.startSwitchLinePlayTimeout();
            if (reusePlayer) {
                mVideoView.skipPositionWhenPlay((int) scheduler.playTimeoutBasePosition());
                mVideoView.replay(false);
            } else {
                mVideoView.start();
            }
            mController.resetSpeed();
        }

        @Override
        public PreloadCoordinator.Snapshot buildPreloadSnapshot() {
            return PlayContainer.this.buildPreloadSnapshot();
        }

        @Override
        public void showPreloadReadyTip() {
            PlayContainer.this.showPreloadReady();
        }

        @Override
        public void hidePreloadReadyTip() {
            PlayContainer.this.hidePreloadReady();
        }

        @Override
        public String firstUrlByArray(String url) {
            return mController == null ? url : mController.firstUrlByArray(url);
        }

        @Override
        public void setArtwork(String url) {
            if (mVideoView != null) mVideoView.setArtwork(url);
        }

        @Override
        public void showParse(boolean show) {
            if (mController != null) mController.showParse(show);
        }

        @Override
        public void checkDanmu(String danmaku, Runnable onFailed) {
            PlayContainer.this.checkDanmu(danmaku, onFailed == null ? null : onFailed::run);
        }

        @Override
        public String encodeUrl(String url) {
            return mController == null ? url : mController.encodeUrl(url);
        }

        @Override
        public void evaluateScript(String url, WebView webView) {
            if (mController != null) mController.evaluateScript(scheduler.sourceBean(), url, webView);
        }

        @Override
        public WebView newSniffWebView() {
            return new MyWebView(mContext);
        }

        @Override
        public void attachSniffWebView(WebView webView) {
            if (isAttached() && mActivity != null) {
                mActivity.addContentView(webView, new ViewGroup.LayoutParams(1, 1));
            }
        }

        @Override
        public void showErrorWithRetry(String err, boolean finish) {
            PlayContainer.this.errorWithRetry(err, finish);
        }

        @Override
        public boolean switchPlayerKernel() {
            return mController != null && mController.switchPlayer();
        }

        @Override
        public void applyPlayerConfig(JSONObject cfg) {
            if (mController != null) mController.setPlayerConfig(cfg);
        }

        @Override
        public boolean onLinesExhausted() {
            return pageHost != null && pageHost.onPlaybackLinesExhausted();
        }
    };

    /**
     * 生命周期自动暂停标记：退后台时若在播放中由 hostPause 自动暂停，回前台 hostResume 需恢复；
     * 用户手动暂停（isPlaying=false）退后台时不动，回前台保持暂停直到用户手动播放。
     */
    private boolean lifecyclePaused;

    /** 由 Compose 宿主在页面可见时调用(对应旧 Fragment onResume/onHiddenChanged(false)) */
    public void hostResume() {
        exitingPreview = false;
        if (mController != null) mController.setLifecyclePaused(false);
        reattachIfOwnedByOther();
        if (mVideoView != null && lifecyclePaused) {
            lifecyclePaused = false;
            mVideoView.resume();
        }
    }

    /**
     * P4 点播→直播→点播:直播页会把引擎从本页收回(容器摘走、直播模式打开)。本页再次可见时若发现自己
     * 不再是引擎的挂载页面,就重新挂载(退出直播模式 + 搬容器 + 重设控制器),避免"回来一片空白"。
     */
    private void reattachIfOwnedByOther() {
        if (engine == null || surfaceSlot == null) return;
        if (engine.attachedPage() == this) return;
        if (engine.isReleased()) return;
        if (engine.isLiveMode()) engine.exitLive();
        engine.attach(this, surfaceSlot);
        if (mVideoView != null && mController != null) {
            mVideoView.setVideoController((BaseVideoController) mController);
        }
        LOG.i("echo-p4 re-attach after live/other page");
    }

    /** 由 Compose 宿主在页面不可见时调用(对应旧 Fragment onPause/onHiddenChanged(true)) */
    public void hostPause() {
        // 只有**确定是纯音频**(TRUE)才不退后台暂停;影视(FALSE)与「轨道信息未知(null)」都按既有行为暂停 ——
        // 迁移前 `!Boolean.TRUE.equals(getAudioOnlyPlayback())` 正是这个语义(null 落到 pause 分支);
        // 迁移中改写成 `!hasAudioOnlyPlayback()` 后 **null 变成了「不暂停」**:起播瞬间 getTrackInfo()
        // 返回 null 时影视会被留在后台继续出声(行为回归,但只在极窄的时间窗内可观测)。本次恢复三态判定。
        if (mVideoView != null && !exitingPreview && !scheduler.isConfirmedAudioOnly()) {
            lifecyclePaused = mVideoView.isPlaying();
            if (mController != null) mController.setLifecyclePaused(true);
            mVideoView.pause();
        }
    }

    /** 由 Compose 宿主在页面销毁时调用(对应旧 Fragment onDestroyView) */
    public void hostDestroy() {
        // 2026-09-13 23:40 SIGSEGV 排查:退出播放页后 ~1.2s 进程静默死亡(无 tombstone/无 Fatal signal),
        // 释放链路加分步落盘日志(echo-music 前缀),复现时定位最后走到的步骤
        LOG.i("echo-music destroy: hostDestroy enter");
        // 播放器归引擎:只摘除页面(影视停画面、确认纯音频则继续播 —— 判定在引擎里),
        // 不释放实例、不停媒体会话(退页面音频续播/通知持续)。
        // **共享调度层的在途收尾(解析/嗅探/取流/超时/当前播放源)已全部挪到会话边界** ——
        // 见 PlaybackController.startSession / stopPlaybackForPageExit(架构评审第 3 项)。
        // 理由:调度层是**引擎级**的,而"页面销毁"与"新页面 attach"的先后由系统决定,
        // 拿页面销毁当收尾时机会误撤新页面的在途动作。这里只收本页私有资源。
        if (engine != null) engine.detach(this);
        // 预载第二期:撤下 Toast(就绪回调已由调度层注销)
        cancelPreloadToast();
        // 用 isRegistered 守卫:onServiceStopped() 可能已经解注册过(引擎先于页面销毁),
        // EventBus 对未注册的订阅者抛异常
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        // BugReview #17:作废在途的轨道切换延迟回调,防对已释放播放器实例 seekTo/start
        trackSwitchSeq.incrementAndGet();
        if (danmuLoadController != null) {
            danmuLoadController.destroy();
            danmuLoadController = null;
        }
        mVideoView = null;
        if (mController != null) mController.stopOther();
        mActivity = null;
        LOG.i("echo-music destroy: hostDestroy done");
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
    /** 「下一集已就绪」Toast 续期延迟(预载方案第二期,2026-09-12 定稿 Toast+5s):1.5s 时续一次,LENGTH_LONG(≈3.5s)+1.5s≈5s */
    private static final long PRELOAD_TOAST_REFRESH_DELAY_MS = 1500L;
    private MyVideoView mVideoView;
    private PlayerControlApi mController;
    /** 下一集预载协调器(预载方案第一期,见 skill/avbox-preload-next-episode-spec.md) */
    /** 预载完成回调实例(第二期 UI 提示;销毁时按实例注销,防误清其他容器的回调) */
    /** 「下一集已就绪」Toast 实例(第二期;切集/销毁时 cancel) */
    private Toast preloadReadyToast;
        private Handler mHandler;
    private boolean exitingPreview = false;
    private boolean previewMode;
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
            VodInfo.VodSeries series = scheduler.vod() == null ? null : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
            danmuLoadController.check(danmu, scheduler.vod() == null ? "" : scheduler.vod().name, series == null ? "" : series.name, callback);
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

        private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_PARSE_TIMEOUT:
                        scheduler.stopParse();
                        errorWithRetry("嗅探错误", false);
                        break;
                }
                return false;
            }
        });
        surfaceSlot = findViewById(R.id.surfaceSlot);
        mController = new ComposeVideoController(mActivity);

        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        // 播放器由引擎持有:进度落盘/状态监听(预载时机·音乐会话·弹幕启动)都在引擎侧,
        // 本页只取实例用于控制器挂载与生命周期暂停/恢复
        mVideoView = engine == null ? null : engine.player();
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
                VodInfo.VodSeries series = scheduler.vod() == null ? null : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
                ApiConfig.get().searchDanmuUi(scheduler.vod() == null ? "" : scheduler.vod().name, series == null ? "" : series.name, longClick);
            }

            @Override
            public void playNext(boolean rmProgress) {
                String preProgressKey = scheduler.progressKey();
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
                scheduler.resetAutoRetryState();
                scheduler.clearTriedLines();
                scheduler.doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                scheduler.vod().playerCfg = scheduler.playerCfg().toString();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, scheduler.playerCfg()));
            }

            @Override
            public void replay(boolean replay) {
                // 重播/切内核也是用户主动发起的播放:引擎可能已被空闲释放,先自愈再下发
                reviveEngineIfReleased();
                scheduler.resetAutoRetryState();
                scheduler.clearTriedLines();
                // 复位"已在播放中"标记(2026-09-13):replay 用于切内核/重播,原标记只在
                // play()(换集/换源)复位 —— 切内核后起播失败会被 errorWithRetry 误判为
                // "已在播放中"而静默 return(不提示、不自动重试,表现为"没有画面且毫无反应");
                // 复位后由 STATE_PLAYING → markPlaybackStarted() 重新置位。
                scheduler.setPlaybackStarted(false);
                if(replay){
                    playViaScheduler(true);
                }else {
                    reloadDanmuForPlayback();
                    if(scheduler.webPlayUrl()!=null && !scheduler.webPlayUrl().isEmpty()) {
                        scheduler.stopParse();
                        scheduler.initParseLoadFound();
                        releasePlayerKernel();
                        scheduler.goPlayUrl(scheduler.webPlayUrl(),scheduler.webHeaderMap());
                    }else {
                        playViaScheduler(false);
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
                if (!TextUtils.isEmpty(scheduler.m3u8SourceUrl()) && !scheduler.isM3u8ProxyUrl(url)) scheduler.clearM3u8ProxyUrl();
                scheduler.goPlayUrl(url, headers);
            }

            @Override
            public void onM3u8ProxyUrl(String proxyUrl, String sourceUrl) {
                scheduler.setM3u8Urls(proxyUrl, sourceUrl);
            }

            @Override
            public void clickCast() {
                showCastDialog();
            }

            @Override
            public void setAllowSwitchPlayer(boolean isAllow){scheduler.setAllowSwitchPlayer(isAllow);}
        });
        if (mVideoView != null) mVideoView.setVideoController((BaseVideoController) mController);
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
        if (TextUtils.isEmpty(scheduler.webPlayUrl())) {
            Toast.makeText(mContext, "暂无可投屏播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAttached()) return;
        HashMap<String, String> headers = scheduler.webHeaderMap() == null ? null : new HashMap<>(scheduler.webHeaderMap());
        CastVideo video = new CastVideo(scheduler.getCastUrl(scheduler.webPlayUrl()), getCastTitle(), headers, getCastPosition());
        PlayerUiState uiState = mController.getUiState();
        uiState.setCastSheet(new CastSheetState(video, () -> {
            if (mVideoView != null) mVideoView.pause();
            return kotlin.Unit.INSTANCE;
        }));
    }

    /** 弹幕搜索面板（Step 6：替代 View 版 SearchDanmuDialog，旧 openSearchDanmuDialog 内容） */
    private void openDanmuSearchSheet() {
        if (!isAttached()) return;
        VodInfo.VodSeries series = scheduler.vod() == null ? null : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
        PlayerUiState uiState = mController.getUiState();
        uiState.setDanmuSearchSheet(new DanmuSearchSheetState(
                series == null ? "" : series.name,
                scheduler.vod() == null ? "" : scheduler.vod().name,
                danmu -> {
                    if (!isAttached()) return kotlin.Unit.INSTANCE;
                    checkDanmu(danmu);
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private String getCastTitle() {
        if (scheduler.vod() == null) return "TVBox";
        try {
            VodInfo.VodSeries series = scheduler.vod().seriesMap.get(scheduler.vod().playFlag).get(scheduler.vod().playIndex);
            return scheduler.vod().name + " " + series.name;
        } catch (Exception e) {
            return TextUtils.isEmpty(scheduler.vod().name) ? "TVBox" : scheduler.vod().name;
        }
    }

    private long getCastPosition() {
        try {
            return mVideoView == null ? 0 : mVideoView.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
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
            if (!isAttached() || mVideoView == null) return;
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
        if (pageHost != null) pageHost.launchLocalSubtitlePicker();
    }

    /** SAF 选中回调:content:// 拷贝到缓存目录再按文件路径渲染(Exo/IJK 双内核兼容) */
    public void onLocalSubtitlePicked(android.net.Uri uri) {
        // 后台拷贝期间页面可能已销毁(hostDestroy 会置 mActivity=null):Activity 先快照到局部变量,
        // 拷贝完成回主线程时再判一次宿主存活。原实现后台线程直接读 mActivity 字段 ——
        // 拷贝大文件时按返回必 NPE,且 catch 分支再次访问字段造成二次 NPE(非主线程未捕获 = 进程崩溃)。
        final android.app.Activity activity = mActivity;
        if (activity == null || activity.isFinishing()) return;
        new Thread(() -> {
            try {
                String name = queryDisplayName(activity, uri);
                if (name == null || !name.contains(".")) name = "local_subtitle.srt";
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                File dst = new File(activity.getCacheDir(), "subtitle_" + System.currentTimeMillis() + "_" + name);
                try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                String path = dst.getAbsolutePath();
                activity.runOnUiThread(() -> {
                    // 页面已销毁:放弃渲染(播放器已 release),防对已置空的 mVideoView 操作
                    if (!isAttached()) return;
                    LOG.i("echo-Local Subtitle Path: " + path);
                    setSubtitle(path);
                });
            } catch (Exception e) {
                LOG.e("echo-Local Subtitle copy err: " + e);
                activity.runOnUiThread(() -> {
                    if (isAttached()) {
                        android.widget.Toast.makeText(activity, "读取字幕文件失败", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** SAF 文件显示名(用于保留字幕扩展名,渲染器按扩展名选解析器);Activity 由调用方传入,防销毁后读空字段 */
    private String queryDisplayName(android.app.Activity activity, android.net.Uri uri) {
        try (android.database.Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
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
        String word = (scheduler.vod().playFlag.contains("Ali") || scheduler.vod().playFlag.contains("parse"))
                ? scheduler.vod().playNote : scheduler.vod().name;
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
        if (mVideoView == null) return;
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
                        if (mediaPlayer instanceof IjkMediaPlayer) ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId, scheduler.progressKey());
                        if (mediaPlayer instanceof ExoPlayer) ((ExoPlayer) mediaPlayer).setTrack(value, scheduler.progressKey());
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
        if (mVideoView == null) return;
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
        // 字幕 sheet 打开的窗口期内引擎可能已释放(onServiceStopped 置空 mVideoView),裸取会未捕获 NPE
        if (mVideoView == null) return;
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
        if (scheduler.isPlaybackStarted()) {
            scheduler.cancelPlayTimeout();
            hideTipOnUiThread();
            return;
        }
        if (!scheduler.autoRetry()) {
            scheduler.stopMusicSessionForFailedPlayback();
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

                    private void initSubtitleView() {
        if (mVideoView == null) return;
        TrackInfo trackInfo = null;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().reset();
        mController.getLyricView().bindToMediaPlayer(mediaPlayer);
        mController.getLyricView().setMergeSameTime(true);
        mController.getLyricView().setLyricMode(true);
        mController.getLyricView().setPlaySubtitleCacheKey(scheduler.lyricCacheKey());
        mController.getSubtitleView().hasInternal = false;
        mController.getSubtitleView().isInternal = false;
        hideExoInternalSubtitle();
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer)mediaPlayer).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.getSubtitleView().hasInternal = true;
            }
            //默认选中第一个音轨 一般第一个音轨是国语 && 加载上一次选中的
            ((IjkMediaPlayer)mediaPlayer).loadDefaultTrack(trackInfo,scheduler.progressKey());
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
            exoPlayer.loadDefaultTrack(scheduler.progressKey());
        }
        if (!TextUtils.isEmpty(scheduler.playLyric())) {
            mController.getLyricView().setSubtitlePath(scheduler.playLyric());
            mController.getLyricView().setVisibility(View.VISIBLE);
        }
        mController.getSubtitleView().bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.getSubtitleView().setPlaySubtitleCacheKey(scheduler.subtitleCacheKey());
        String subtitlePathCache = (String)CacheManager.getCache(MD5.string2MD5(scheduler.subtitleCacheKey()));
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setSubtitlePath(subtitlePathCache);
        } else {
            if (scheduler.playSubtitle() != null && scheduler.playSubtitle() .length() > 0) {
                hideExoInternalSubtitle();
                mController.getSubtitleView().setSubtitlePath(scheduler.playSubtitle());
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

            private void clearLyricView() {
        if (mController == null || mController.getLyricView() == null) return;
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().destroy();
        mController.getLyricView().setText("");
    }

        /**
     * 释放播放内核(换源点击即停 / 切内核重播 / 外部播放器接管)。
     *
     * <p>所有权收口(2026-09-14 架构评审第 2 项):播放器归引擎,页面**不得**直接 `mVideoView.release()`
     * —— 只表达"我要换内核"的意图,由引擎执行并同步自己的状态(如清掉 D6 的接管依据)。
     */
    private void releasePlayerKernel() {
        if (engine != null) {
            engine.releasePlayer();
        } else if (mVideoView != null) {
            // 引擎已不在(宿主服务销毁且页面未收到回执)时的兜底,正常路径走不到
            mVideoView.release();
        }
    }

    /**
     * 引擎已被释放(空闲 TTL / 任务移除)而本页仍存活时,重新取一个引擎并把视图挂回来。
     *
     * <p>只可能是"页面在栈里但长时间没有播放"的窗口(如点播→直播→回点播后停着不播),
     * 用户一旦再次发起播放(setData / play)就必须能自愈 —— 否则会黑屏且毫无反应。
     */
    private boolean reviveEngineIfReleased() {
        if (engine != null && !engine.isReleased()) return false;
        if (mActivity == null || surfaceSlot == null) return false;
        // 换引擎前先把**旧**调度层的在途收干净:三处超时/取流/解析若在切换后才到达,
        // 会回调到本页面桥,而桥里的 mVideoView 已经换成新实例 —— 等于把上一轮的内容播到新播放器上
        if (scheduler != null) scheduler.stopPlaybackForPageExit();
        engine = PlaybackService.engine(mActivity);
        scheduler = engine.controller();
        mVideoView = engine.player();
        engine.attach(this, surfaceSlot);
        if (mVideoView != null) {
            mVideoView.setVideoController((BaseVideoController) mController);
            if (danmuLoadController != null) danmuLoadController.setVideoView(mVideoView);
        }
        LOG.i("echo-p2 revive engine after release");
        return true;
    }

    /** 播放当前集(PlaybackHostApi;实现已迁至调度层,见 PlaybackController.play) */
    @Override
    public void play(boolean reset) {
        reviveEngineIfReleased();
        scheduler.play(reset);
    }

    /** 切换清晰度(PlaybackHostApi;实现已迁至调度层,见 PlaybackController.selectQuality) */
    @Override
    public boolean selectQuality(int position) {
        return scheduler.selectQuality(position);
    }
                @Override
    public void setData(PlaybackSession session) {
        if (engine == null || engine.isReleased()) {
            // 引擎已释放:任务被移除、或空闲 TTL 到期自释放(见 PlaybackEngine.IDLE_RELEASE_DELAY_MS)。
            // 本页可能还在栈里(点播→直播→回点播后长时间不播),此时重新取一个引擎接上,而不是放弃播放
            if (!reviveEngineIfReleased()) {
                LOG.i("echo-p5 setData skipped: engine released");
                return;
            }
        }
        // D6 同片接管(P3,Spec §2.3):引擎里就是这一集(退页面音频续播后重进 / 预览态来回切换)时,
        // 只同步 UI 与配置,不重新取流重播 —— 播放器与会话都还在
        if (isSamePlaybackOwned(session)) {
            LOG.i("echo-p3 take over same playback: " + session.playbackKey());
            // 接管 ≠ 什么都不做(2026-09-14「快速返回再进入」回归修复):
            // ① 会话归属必须切到本次 —— 否则 scheduler.vod() 仍指向**上一个已销毁页面**的 VodInfo,
            //    选集/换线/投屏标题/播放记录改的都是旧对象,与新页面 UI 脱节(选集点了不跳、高亮不动);
            // ② 标题只在 play() 里下发,接管不走 play() → 播放器顶栏标题为空;
            // ③ startSession 会清空"已起播内容"标记,而播放器里确实还是这一集 → 补标回来,
            //    否则再退再进就会被 D6 拒绝接管、白白重取一次流。
            engine.setData(session);
            mController.setPlayerConfig(scheduler.playerCfg());
            scheduler.markContentStarted();
            scheduler.publishTitle();
            // 与正常路径对齐:接管同样是一次"新的播放",已尝试线路与手选线路标记要跟着重置 ——
            // 否则上一轮自动换线留下的 triedLines 会让新一轮永远跳过某条线路,
            // 上一轮残留的 userPickedLine 会让新一轮失败时"直接报错停留、不自动换线"
            scheduler.clearTriedLines();
            scheduler.setUserPickedLine(session.userPickedLine());
            if (mVideoView != null && !mVideoView.isPlaying()) mVideoView.start();
            return;
        }
        // 会话落进引擎(引擎侧一并 startSession):detach 后仍可被接管
        engine.setData(session);
        // 等价于原 initPlayerCfg 末尾那次调用:会话/配置就绪后刷到控制器
        mController.setPlayerConfig(scheduler.playerCfg());
        scheduler.clearTriedLines();
        scheduler.setUserPickedLine(session.userPickedLine());
        playViaScheduler(false);
    }

    /**
     * 所有 `scheduler.play()` 的统一入口:先做引擎自愈(见 {@link #reviveEngineIfReleased()}),
     * 再下发 —— 换集/重播可能发生在"引擎已被空闲释放而页面还活着"的窗口里,
     * 直接用旧 scheduler 会把内容播到已释放的播放器上(有声无画)。
     */
    private void playViaScheduler(boolean reset) {
        reviveEngineIfReleased();
        scheduler.play(reset);
    }

    /**
     * D6:引擎当前会话与目标会话是同一集(源 key|片 id|线路|集索引),且播放器实例仍持有、非错误态 ——
     * 判定为"接管续播"而非"换片重播"。
     */
    /**
     * D6 接管判定的唯一可信依据是 **"播放器里的内容确实是这个会话起的"**(`startedPlaybackKey`),
     * 而不是"会话登记过"或"播放器实例还在":
     * <ul>
     *   <li>会话登记过但取流失败 → 播放器里其实是上一部/上一集,接管就会播错内容;</li>
     *   <li>直播接管过播放器 → 内容是直播流,且进入直播时已把会话与归属清空;</li>
     *   <li>换源/外部播放器 → 播放器已被释放(`getMediaPlayer() == null`)。</li>
     * </ul>
     */
    private boolean isSamePlaybackOwned(PlaybackSession session) {
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false;
        // 直播模式下的播放器属于直播页(内容是直播流),不能当"点播同片"接管(纵深防御)
        if (engine.isLiveMode()) return false;
        if (mVideoView == null || mVideoView.getMediaPlayer() == null) return false;
        int state = mVideoView.getCurrentPlayState();
        return state != VideoView.STATE_ERROR && state != VideoView.STATE_IDLE;
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

        public void resumeFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.start();
            scheduler.updateMusicSession();
        }
    }

    public void pauseFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.pause();
            scheduler.updateMusicSession();
        }
    }

    public void stopFromMediaSession() {
        if (mVideoView != null) mVideoView.pause();
        scheduler.stopMusicSession();
    }

    public void seekFromMediaSession(long position) {
        if (mVideoView != null) {
            mVideoView.seekTo(position);
            scheduler.updateMusicSession();
        }
    }

                
    public void playNext(boolean isProgress) {
        scheduler.clearTriedLines();
        boolean hasNext;
        if (scheduler.vod() == null || scheduler.vod().seriesMap.get(scheduler.vod().playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = scheduler.vod().playIndex + 1 < scheduler.vod().seriesMap.get(scheduler.vod().playFlag).size();
        }
        if (!hasNext) {
            Toast.makeText(mActivity, "已经是最后一集了!", Toast.LENGTH_SHORT).show();
            return;
        }else {
            scheduler.vod().playIndex++;
        }
        scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    public void playPrevious() {
        scheduler.clearTriedLines();
        boolean hasPre = true;
        if (scheduler.vod() == null || scheduler.vod().seriesMap.get(scheduler.vod().playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = scheduler.vod().playIndex - 1 >= 0;
        }
        if (!hasPre) {
            Toast.makeText(mActivity, "已经是第一集了!", Toast.LENGTH_SHORT).show();
            return;
        }
        scheduler.vod().playIndex--;
        scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    private void showEpisodeDialog() {
        if (!isAttached() || scheduler.vod() == null || scheduler.vod().seriesMap == null || TextUtils.isEmpty(scheduler.vod().playFlag)) return;
        List<VodInfo.VodSeries> episodes = scheduler.vod().seriesMap.get(scheduler.vod().playFlag);
        if (episodes == null || episodes.isEmpty()) return;
        String title = TextUtils.isEmpty(scheduler.vod().name) ? "选集" : scheduler.vod().name + " 选集";
        mController.getUiState().setEpisodeSheet(new EpisodeSheetState(
                title,
                episodes,
                scheduler.vod().playIndex,
                position -> {
                    if (position < 0 || position >= episodes.size() || position == scheduler.vod().playIndex) return kotlin.Unit.INSTANCE;
                    scheduler.clearTriedLines();
                    scheduler.vod().playIndex = position;
                    scheduler.setReusePlayerOnSwitch(true);
                    playViaScheduler(false);
                    return kotlin.Unit.INSTANCE;
                }));
    }
    public void setPlayTitle(boolean show) {
        if (!show) {
            mController.setTitle("");
            return;
        }
        VodInfo vod = scheduler.vod();
        // 线路/集号不可信(历史恢复、源更新)时降级为仅片名(与 getCastTitle 的兜底一致),不裸链式取值
        VodInfo.VodSeries vs = vod == null ? null : scheduler.currentSeries(vod.playFlag, vod.playIndex);
        mController.setTitle(vod == null ? "" : (vs == null ? vod.name : vod.name + " " + vs.name));
    }

        private PreloadCoordinator.Snapshot buildPreloadSnapshot() {
        try {
            if (scheduler.vod() == null || scheduler.vod().seriesMap == null) return null;
            List<VodInfo.VodSeries> episodes = scheduler.vod().seriesMap.get(scheduler.vod().playFlag);
            if (episodes == null || scheduler.vod().playIndex < 0 || scheduler.vod().playIndex + 1 >= episodes.size()) return null;
            VodInfo.VodSeries next = episodes.get(scheduler.vod().playIndex + 1);
            if (next == null || TextUtils.isEmpty(next.url)) return null;
            int nextIndex = scheduler.vod().playIndex + 1;
            String nextKey = scheduler.vod().sourceKey + scheduler.vod().id + scheduler.vod().playFlag + nextIndex + next.name;
            String nextSubtKey = scheduler.vod().sourceKey + "-" + scheduler.vod().id + "-" + scheduler.vod().playFlag + "-" + nextIndex + "-" + next.name + "-subt";
            long startSkipMs = scheduler.playerCfg() == null ? 0 : scheduler.playerCfg().optInt("st", 0) * 1000L;
            // 内核判定(预载方案):取实际播放器实例,非 app ExoPlayer 时协调器跳过预载(规格 §1)
            AbstractPlayer mediaPlayer = mVideoView == null ? null : mVideoView.getMediaPlayer();
            boolean exoKernel = mediaPlayer instanceof ExoPlayer;
            return new PreloadCoordinator.Snapshot(mContext, scheduler.sourceKey(), scheduler.vod().playFlag, scheduler.progressKey(), nextKey, next.url, nextSubtKey, startSkipMs, exoKernel);
        } catch (Throwable th) {
            LOG.i("echo-preload-skip: snapshot error " + th);
            return null;
        }
    }
            /** 预览态启用/全屏禁用自动换线(委托调度层,见 PlaybackController.setAutoSwitchLineEnabled) */
    @Override
    public void setAutoSwitchLineEnabled(boolean enabled) {
        scheduler.setAutoSwitchLineEnabled(enabled);
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
        scheduler.cancelPlayTimeout();
        scheduler.stopParse();
        scheduler.markStoppedForSourceSwitch();
        scheduler.stopMusicSessionForFailedPlayback();
        
        long position = mVideoView.getCurrentPosition();
        scheduler.setPendingInherit(scheduler.progressKey(), position);
        mVideoView.pause();
        // 所有权在引擎:换源"点击即停"只表达"释放内核"的意图(见 releasePlayerKernel)
        releasePlayerKernel();
        if (mController != null) mController.stopOther();
        resetDanmuState();
        scheduler.setWebPlayUrl(null);
        scheduler.setWebHeaderMap(null);
        scheduler.initParseLoadFound();
        LOG.i("echo-switchSource stop at " + position + "ms, key=" + scheduler.progressKey());
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false);
    }

    public void clearSourceSwitchTip() {
        if (!scheduler.isSwitchStopPending()) return;
        hideTipOnUiThread();
    }
                public MyVideoView getPlayer() {
        return mVideoView;
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
}
