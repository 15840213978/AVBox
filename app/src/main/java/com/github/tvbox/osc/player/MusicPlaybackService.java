package com.github.tvbox.osc.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import coil3.SingletonImageLoader;
import coil3.request.ImageRequest;
import coil3.target.Target;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.player.PlayContainer;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.ScreenUtils;

import java.lang.ref.WeakReference;

public class MusicPlaybackService extends Service {
    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_UPDATE = "com.github.tvbox.osc.music.UPDATE";
    private static final String ACTION_PLAY = "com.github.tvbox.osc.music.PLAY";
    private static final String ACTION_PAUSE = "com.github.tvbox.osc.music.PAUSE";
    private static final String ACTION_PREVIOUS = "com.github.tvbox.osc.music.PREVIOUS";
    private static final String ACTION_NEXT = "com.github.tvbox.osc.music.NEXT";
    private static final String ACTION_PLACEHOLDER = "com.github.tvbox.osc.music.PLACEHOLDER";
    private static final String ACTION_STOP = "com.github.tvbox.osc.music.STOP";
    private static final String ACTION_SEEK = "com.github.tvbox.osc.music.SEEK";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SUBTITLE = "subtitle";
    private static final String EXTRA_ARTWORK = "artwork";
    private static final String EXTRA_POSITION = "position";
    private static final String EXTRA_DURATION = "duration";
    private static final String EXTRA_PLAYING = "playing";
    private static final String EXTRA_SEEK = "seek";

    private static MusicPlaybackService instance;
    private static WeakReference<PlayContainer> owner;
    /**
     * startForegroundService 已发出、服务尚未就绪(onCreate 未跑)。
     * ⚠️ 此窗口内绝不能 stopService:AOSP 竞态 —— create 已派发到进程,onStartCommand 可能
     * 不再交付,startForeground 永远不执行 → ForegroundServiceDidNotStartInTimeException 杀进程
     * (真机 2026-09-13:音乐起播失败重试期 start/stop 毫秒级抖动,连崩两次,vivo 超时窗 ~5s)。
     * 改为置 stopWhenStarted,让服务自己走「startForeground → stop」的合法时序。
     */
    private static volatile boolean pendingStart;
    private static volatile boolean stopWhenStarted;

    private MediaSessionCompat mediaSession;
    private PendingIntent sessionActivity;
    private String title = "TVBox";
    private String subtitle = "";
    private String artworkUrl = "";
    private Bitmap artwork;
    private long position;
    private long duration;
    private boolean playing;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static boolean isSupported(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        return !ScreenUtils.isTv(context);
    }

    public static void update(Context context, PlayContainer fragment, String title, String subtitle,
                              String artwork, long position, long duration, boolean playing) {
        if (!isSupported(context)) return;
        owner = new WeakReference<>(fragment);
        Intent intent = new Intent(context, MusicPlaybackService.class).setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_SUBTITLE, subtitle);
        intent.putExtra(EXTRA_ARTWORK, artwork);
        intent.putExtra(EXTRA_POSITION, position);
        intent.putExtra(EXTRA_DURATION, duration);
        intent.putExtra(EXTRA_PLAYING, playing);
        if (instance != null) {
            pendingStart = false;
            stopWhenStarted = false;
            instance.handleIntent(intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pendingStart = true;
            stopWhenStarted = false;
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context, PlayContainer fragment) {
        PlayContainer current = owner == null ? null : owner.get();
        if (fragment != null && current != null && current != fragment) return;
        owner = null;
        if (instance != null) {
            pendingStart = false;
            instance.stopPlaybackService();
        } else if (pendingStart) {
            // FGS 在途:不能 stopService(见 pendingStart 注释),登记"起来就停"
            stopWhenStarted = true;
        } else if (context != null) {
            context.stopService(new Intent(context, MusicPlaybackService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pendingStart = false;
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "TVBoxMusic");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                PlayContainer fragment = getOwner();
                if (fragment != null) fragment.resumeFromMediaSession();
            }

            @Override
            public void onPause() {
                PlayContainer fragment = getOwner();
                if (fragment != null) fragment.pauseFromMediaSession();
            }

            @Override
            public void onSkipToPrevious() {
                PlayContainer fragment = getOwner();
                if (fragment != null) {
                    pauseForSwitch();
                    fragment.playPrevious();
                }
            }

            @Override
            public void onSkipToNext() {
                PlayContainer fragment = getOwner();
                if (fragment != null) {
                    pauseForSwitch();
                    fragment.playNext(false);
                }
            }

            @Override
            public void onStop() {
                PlayContainer fragment = getOwner();
                if (fragment != null) fragment.stopFromMediaSession();
                stopPlaybackService();
            }

            @Override
            public void onSeekTo(long pos) {
                PlayContainer fragment = getOwner();
                if (fragment != null) fragment.seekFromMediaSession(pos);
            }
        }, new Handler(Looper.getMainLooper()));
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            sessionActivity = PendingIntent.getActivity(this, 0, launchIntent, flags);
            mediaSession.setSessionActivity(sessionActivity);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
    
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification());
            } catch (Throwable th) {
                
                LOG.i("echo-music startForeground failed: " + th.getMessage());
            }
        }
        if (intent != null) handleIntent(intent);
        return START_NOT_STICKY;
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            PlayContainer fragment = getOwner();
            if (fragment != null) fragment.stopFromMediaSession();
            stopPlaybackService();
            return;
        }
        if (ACTION_PLAY.equals(action)) {
            PlayContainer fragment = getOwner();
            if (fragment != null) fragment.resumeFromMediaSession();
            return;
        }
        if (ACTION_PAUSE.equals(action)) {
            PlayContainer fragment = getOwner();
            if (fragment != null) fragment.pauseFromMediaSession();
            return;
        }
        if (ACTION_PREVIOUS.equals(action)) {
            PlayContainer fragment = getOwner();
            if (fragment != null) {
                pauseForSwitch();
                fragment.playPrevious();
            }
            return;
        }
        if (ACTION_NEXT.equals(action)) {
            PlayContainer fragment = getOwner();
            if (fragment != null) {
                pauseForSwitch();
                fragment.playNext(false);
            }
            return;
        }
        if (ACTION_SEEK.equals(action)) {
            PlayContainer fragment = getOwner();
            if (fragment != null) fragment.seekFromMediaSession(intent.getLongExtra(EXTRA_SEEK, 0));
            return;
        }
        if (ACTION_UPDATE.equals(action)) {
            // 服务已被 stopPlaybackService 停止(mediaSession 已置 null)但实例尚未销毁时,
            // PlayContainer 的播放状态回调仍可能投递 UPDATE —— 此时不能再走下去:
            // acquirePlaybackLocks 会重新持锁且无人释放(电量泄漏)、startForeground 会让通知复活、
            // buildNotification 曾在真机上直接 NPE 崩溃(2026-09-13 实锤路径)
            if (mediaSession == null) return;
            acquirePlaybackLocks();
            title = intent.getStringExtra(EXTRA_TITLE);
            subtitle = intent.getStringExtra(EXTRA_SUBTITLE);
            String newArtworkUrl = intent.getStringExtra(EXTRA_ARTWORK);
            position = intent.getLongExtra(EXTRA_POSITION, 0);
            duration = intent.getLongExtra(EXTRA_DURATION, 0);
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false);
            updateArtwork(newArtworkUrl);
            updateSession();
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void updateArtwork(String url) {
        if (TextUtils.equals(artworkUrl, url)) return;
        artworkUrl = url == null ? "" : url;
        artwork = null;
        if (TextUtils.isEmpty(artworkUrl)) return;
        // 封面地址的 @Headers= 等附加参数由 VodImages 的 OkHttp 拦截器剥离并注入请求头
        ImageRequest request = new ImageRequest.Builder(this)
                .data(artworkUrl)
                .size(256, 256)
                .target(new Target() {
                    @Override
                    public void onSuccess(coil3.Image image) {
                        // toBitmap 保证软件位图,通知 RemoteViews 不接受硬件位图
                        artwork = coil3.Image_androidKt.toBitmap(image);
                        // 图片下载期间服务可能已被停止(mediaSession 被置 null):此时再刷新通知会
                        // 在 buildNotification() 内对 null mediaSession 取 sessionToken 而崩溃(2026-09-13 修复)
                        if (mediaSession == null) return;
                        updateSession();
                        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
                    }
                })
                .build();
        SingletonImageLoader.get(this).enqueue(request);
    }

    private void updateSession() {
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, subtitle);
        if (duration > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (!TextUtils.isEmpty(artworkUrl)) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl);
        }
        if (artwork != null) metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
        mediaSession.setMetadata(metadata.build());
        long action = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(action)
                .setState(state, position, playing ? 1f : 0f)
                .build());
        mediaSession.setActive(true);
    }

    private void pauseForSwitch() {
        playing = false;
        position = 0;
        updateSession();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new NotificationCompat.Builder(this, CHANNEL_ID)
                : new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification_music)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(sessionActivity)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setDeleteIntent(actionIntent(ACTION_STOP))
                // mediaSession 可能已被 stopPlaybackService 置 null(封面异步回调晚于停止):
                // 这里判空兜底,防 getSessionToken() NPE(2026-09-13 修复)
                .setStyle(new MediaStyle().setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3));
        if (artwork != null) builder.setLargeIcon(artwork);
        builder.addAction(new NotificationCompat.Action(R.drawable.media_action_placeholder, "", actionIntent(ACTION_PLACEHOLDER)));
        builder.addAction(new NotificationCompat.Action(R.drawable.exo_icon_previous, "上一首", actionIntent(ACTION_PREVIOUS)));
        builder.addAction(new NotificationCompat.Action(playing ? R.drawable.exo_icon_pause : R.drawable.exo_icon_play,
                playing ? "暂停" : "播放", actionIntent(playing ? ACTION_PAUSE : ACTION_PLAY)));
        builder.addAction(new NotificationCompat.Action(R.drawable.exo_icon_next, "下一首", actionIntent(ACTION_NEXT)));
        return builder.build();
    }

    private PendingIntent actionIntent(String action) {
        Intent intent = new Intent(this, MusicPlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, action.hashCode(), intent, flags);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("音乐后台播放控制");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void acquirePlaybackLocks() {
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBox:MusicPlayback");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
                LOG.i("echo-music wake lock acquired");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wake lock acquire failed: " + th.getMessage());
        }
        try {
            if (wifiLock == null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBox:MusicPlayback");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                LOG.i("echo-music wifi lock acquired");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wifi lock acquire failed: " + th.getMessage());
        }
    }

    private void releasePlaybackLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                LOG.i("echo-music wifi lock released");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wifi lock release failed: " + th.getMessage());
        } finally {
            wifiLock = null;
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LOG.i("echo-music wake lock released");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wake lock release failed: " + th.getMessage());
        } finally {
            wakeLock = null;
        }
    }

    private PlayContainer getOwner() {
        return owner == null ? null : owner.get();
    }

    private void stopPlaybackService() {
        playing = false;
        releasePlaybackLocks();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopPlaybackService();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
