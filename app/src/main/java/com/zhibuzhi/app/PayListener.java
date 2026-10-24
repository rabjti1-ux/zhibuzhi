package com.zhibuzhi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监听微信、支付宝的付款通知。
 * 识别到一笔付款后：存进“待记账”列表，并发一条提醒，点开就能记一笔。
 * 只看这两个 app 的通知，内容只存在手机上。
 */
public class PayListener extends NotificationListenerService {

    static final String PREFS = "pay";
    static final Object LOCK = new Object();
    private static final String CHANNEL = "pay";
    private static final String WECHAT = "com.tencent.mm";
    private static final String ALIPAY = "com.eg.android.AlipayGphone";
    private static final long DEDUPE_MS = 10 * 60 * 1000L;

    // ¥12.50 / ￥12 / 12.50元
    private static final Pattern AMOUNT = Pattern.compile("[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)|([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元");
    private static final Pattern PAY_WORDS = Pattern.compile("支付|付款|扣款|消费|支出");
    private static final Pattern SKIP_WORDS = Pattern.compile("收款|到账|收入|退款|转入|应还|待还|还款|账单|额度|优惠|红包|领取|返现|奖励|积分|提现");
    private static final Pattern MERCHANT = Pattern.compile("(?:向|在|给)([^，,。\\s]{1,16}?)(?:付款|支付|消费)");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        String platform;
        if (WECHAT.equals(pkg)) platform = "微信";
        else if (ALIPAY.equals(pkg)) platform = "支付宝";
        else return;

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle ex = n.extras;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String all = title + " " + text + " " + big;

        // 微信里只认“微信支付”发来的，避免把聊天消息当成付款
        if ("微信".equals(platform) && !all.contains("微信支付")) return;
        if (!PAY_WORDS.matcher(all).find() || SKIP_WORDS.matcher(all).find()) return;

        Matcher m = AMOUNT.matcher(all);
        if (!m.find()) return;
        String raw = m.group(1) != null ? m.group(1) : m.group(2);
        double amount;
        try {
            amount = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return;
        }
        if (amount <= 0) return;

        String merchant = "";
        Matcher mm = MERCHANT.matcher(all);
        if (mm.find()) merchant = mm.group(1);

        long now = System.currentTimeMillis();
        String sig = platform + "|" + raw + "|" + text;
        String id = "p" + now;

        synchronized (LOCK) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (sig.equals(sp.getString("lastSig", "")) && now - sp.getLong("lastTs", 0L) < DEDUPE_MS) return;
            try {
                JSONArray arr = new JSONArray(sp.getString("pending", "[]"));
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("amount", amount);
                o.put("platform", platform);
                o.put("merchant", merchant);
                o.put("text", (title + " " + text).trim());
                o.put("ts", now);
                arr.put(o);
                while (arr.length() > 50) arr.remove(0);
                sp.edit()
                        .putString("pending", arr.toString())
                        .putString("lastSig", sig)
                        .putLong("lastTs", now)
                        .apply();
            } catch (JSONException e) {
                return;
            }
        }
        postReminder(id, amount, platform, merchant);
    }

    private void postReminder(String id, double amount, String platform, String merchant) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "付款提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("微信、支付宝付款后提醒你记一笔");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setAction("com.zhibuzhi.app.PAYMENT." + id);
        open.putExtra("payId", id);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String amt = String.format(Locale.CHINA, "¥%.2f", amount);
        String body = merchant.isEmpty() ? "点一下记一笔，选个分类就好" : "在 " + merchant + "，点一下记一笔";
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("刚刚用" + platform + "付了 " + amt)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id.hashCode(), notice);
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
