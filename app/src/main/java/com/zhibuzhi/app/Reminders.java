package com.zhibuzhi.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 还款提醒。页面算好每条提醒的时间和文字（JSON 数组），这里用系统闹钟按时弹通知。
 * 每次页面传来新列表，就先取消旧的再排新的；开机或 app 更新后从保存的列表重新排。
 */
final class Reminders {

    static final String PREFS = "remind";
    static final String ACTION = "com.zhibuzhi.app.REMIND";
    private static final String CHANNEL = "debt";

    private Reminders() {
    }

    static synchronized void schedule(Context ctx, String json) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray old = parse(sp.getString("list", "[]"));
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null) cancel(ctx, o.optString("id"));
        }
        JSONArray next = parse(json);
        sp.edit().putString("list", next.toString()).apply();
        arm(ctx, next);
    }

    static synchronized void rearm(Context ctx) {
        arm(ctx, parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]")));
    }

    private static JSONArray parse(String s) {
        try {
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static Intent intent(Context ctx, String id) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(ACTION);
        i.setData(Uri.parse("zhibuzhi://remind/" + Uri.encode(id)));
        return i;
    }

    private static void arm(Context ctx, JSONArray arr) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            long at = o.optLong("at");
            if (id.isEmpty() || at <= now) continue;
            Intent it = intent(ctx, id);
            it.putExtra("title", o.optString("title"));
            it.putExtra("text", o.optString("text"));
            it.putExtra("debtId", o.optString("debtId"));
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // 不需要“精确闹钟”权限；系统可能推迟几分钟，对还款提醒足够
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancel(Context ctx, String id) {
        if (id == null || id.isEmpty()) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent(ctx, id),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi == null) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
        pi.cancel();
    }

    static void show(Context ctx, String title, String text, String debtId) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "还款提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("欠款快到期或已逾期时提醒你还钱");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setAction("com.zhibuzhi.app.DEBT." + debtId);
        open.putExtra("debtId", debtId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, ("debt:" + debtId).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(("debt:" + debtId).hashCode(), n);
    }
}
