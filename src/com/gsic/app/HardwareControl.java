package com.gsic.app;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * v2.78：硬件控制（震动/手电筒/音量走 Java API，其余走 root shell）
 */
public class HardwareControl {

    /** 震动 duration 毫秒 */
    public static String vibrate(Context ctx, long ms) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager)
                        ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm.getDefaultVibrator();
            } else {
                v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return "无震动器";
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
            return "ok";
        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }

    /** 手电筒开关 */
    public static String torch(Context ctx, boolean on) {
        try {
            CameraManager cm = (CameraManager)
                    ctx.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) return "无摄像头";
            // 通常后置是 ids[0]
            cm.setTorchMode(ids[0], on);
            return "ok";
        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }

    /**
     * 设置音量
     * @param stream: media/call/ring/alarm/notification
     * @param vol: 0-max，不传则返回当前
     */
    public static String volume(Context ctx, String stream, Integer vol) {
        try {
            AudioManager am = (AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            int s;
            switch (stream == null ? "media" : stream) {
                case "call": s = AudioManager.STREAM_VOICE_CALL; break;
                case "ring": s = AudioManager.STREAM_RING; break;
                case "alarm": s = AudioManager.STREAM_ALARM; break;
                case "notification": s = AudioManager.STREAM_NOTIFICATION; break;
                default: s = AudioManager.STREAM_MUSIC;
            }
            int max = am.getStreamMaxVolume(s);
            if (vol != null) {
                int v = Math.max(0, Math.min(max, vol));
                am.setStreamVolume(s, v, 0);
                return "ok " + v + "/" + max;
            }
            return am.getStreamVolume(s) + "/" + max;
        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }
}
