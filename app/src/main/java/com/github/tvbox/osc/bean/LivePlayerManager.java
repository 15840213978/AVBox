package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.KV;

import org.json.JSONException;
import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;

    public void init(VideoView videoView) {
        try {
            defaultPlayerConfig.put("pl", KV.get(HawkConfig.LIVE_PLAY_TYPE, KV.get(HawkConfig.PLAY_TYPE, 2)));
            if (defaultPlayerConfig.optInt("pl", 2) == 0) {
                defaultPlayerConfig.put("pl", 2);
            }
            defaultPlayerConfig.put("ijk", KV.get(HawkConfig.IJK_CODEC, "硬解码"));
            defaultPlayerConfig.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1));
            defaultPlayerConfig.put("sc", KV.get(HawkConfig.LIVE_PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * BugReview:异步回调(如代理配置加载)可能在 mVideoView 已释放(init 未执行)时触达播放链路,
     * currentPlayerConfig 此时为 null;统一回落到 defaultPlayerConfig,杜绝 NPE(2026-09-10 22:34 崩溃)
     */
    private JSONObject currentOrDefaultConfig() {
        return currentPlayerConfig != null ? currentPlayerConfig : defaultPlayerConfig;
    }

    public int getLivePlayerType() {
        JSONObject config = currentOrDefaultConfig();
        int playerTypeIndex = 2;
        int playerType = config.optInt("pl", 2);
        String ijkCodec = config.optString("ijk", "硬解码");
        switch (playerType) {
            case 1:
                if (ijkCodec.equals("硬解码"))
                    playerTypeIndex = 0;
                else
                    playerTypeIndex = 1;
                break;
            case 2:
                playerTypeIndex = 2;
                break;
        }
        return playerTypeIndex;
    }

    public int getLivePlayerScale() {
        return currentOrDefaultConfig().optInt("sc", 0);
    }

    public void changeLivePlayerType(VideoView videoView, int playerType) {
        JSONObject playerConfig;
        try {
            playerConfig = new JSONObject(currentOrDefaultConfig().toString());
        } catch (JSONException e) {
            playerConfig = new JSONObject();
        }
        try {
            switch (playerType) {
                case 0:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", "硬解码");
                    break;
                case 1:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", "软解码");
                    break;
                case 2:
                    playerConfig.put("pl", 2);
                    playerConfig.put("ijk", "软解码");
                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        try {
            defaultPlayerConfig.put("pl", playerConfig.getInt("pl"));
            defaultPlayerConfig.put("ijk", playerConfig.getString("ijk"));
            KV.put(HawkConfig.LIVE_PLAY_TYPE, playerConfig.getInt("pl"));
            KV.put(HawkConfig.IJK_CODEC, playerConfig.getString("ijk"));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }

    public boolean switchLivePlayer(VideoView videoView) {
        JSONObject playerConfig = currentPlayerConfig;
        if (playerConfig == null) {
            LOG.i("echo-liveSwitchPlayer: skip empty player config");
            return false;
        }
        try {
            int playerType = playerConfig.getInt("pl");
            int switchPlayerType = (playerType == 1) ? 2 : (playerType == 2) ? 1 : playerType;
            if (switchPlayerType == playerType) {
                LOG.i("echo-liveSwitchPlayer: skip unsupported playerType=" + playerType);
                return false;
            }
            LOG.i("echo-liveSwitchPlayer: " + playerType + " -> " + switchPlayerType);
            playerConfig.put("pl", switchPlayerType);
        } catch (JSONException e) {
            LOG.i("echo-liveSwitchPlayer error: " + e.getMessage());
            return false;
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        currentPlayerConfig = playerConfig;
        return true;
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale){
        videoView.setScreenScaleType(playerScale);
        KV.put(HawkConfig.LIVE_PLAY_SCALE, playerScale);

        JSONObject playerConfig;
        try {
            playerConfig = new JSONObject(currentOrDefaultConfig().toString());
        } catch (JSONException e) {
            playerConfig = new JSONObject();
        }
        try {
            playerConfig.put("sc", playerScale);
            defaultPlayerConfig.put("sc", playerScale);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }
}
