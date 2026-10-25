package com.zhibuzhi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
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
 * 识别到一笔付款后：存进“待处理”列表，并发一条提醒，点开选择记为消费或还款。
 * 只看这两个 app 的通知，其他通知直接忽略、不保存。内容只存在手机上。
 * 识别到付款但找不到金额时，也会存一条“识别失败”，让用户手动填写。
 * 为了排查“为什么没识别”，最近 20 条微信支付 / 支付宝通知的文字和判断结果也存在手机上，
 * 在“我的”页能看到、能清空。微信的普通聊天消息一律不存内容。
 * 绝不自动扣减欠款，是否算还款由用户选择。
 */
public class PayListener extends NotificationListenerService {

    static final String PREFS = "pay";
    static final Object LOCK = new Object();
    /** 服务现在是否连着系统。和 app 在同一个进程里，打开 app 时可以直接看 */
    static volatile boolean running = false;
    private static final String CHANNEL = "pay";
    private static final String WECHAT = "com.tencent.mm";
    private static final String ALIPAY = "com.eg.android.AlipayGphone";
    private static final long DEDUPE_MS = 10 * 60 * 1000L;
    private static final int LOG_MAX = 20;

    // 金额数字：12 / 12.5 / 1,234.50
    private static final String NUM = "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,7}(?:\\.[0-9]{1,2})?)";
    // 按可信程度依次找金额：先找“实付 / 付款金额”，再找 ¥12，最后找 12元
    private static final Pattern AMT_LABELED = Pattern.compile("(?:实付|实际支付|付款金额|支付金额|消费金额|扣款金额|交易金额|金额)[：:\\s]*[¥￥]?\\s*" + NUM);
    private static final Pattern AMT_SIGN = Pattern.compile("[¥￥]\\s*" + NUM);
    private static final Pattern AMT_WORD = Pattern.compile(NUM + "\\s*元");
    // 金额前面紧挨着这些字的，一般是优惠或奖励，不是付了多少
    private static final Pattern DISCOUNT_BEFORE = Pattern.compile("[减返省送得券奖赚领抵惠][^0-9]{0,3}$");

    private static final Pattern PAY_WORDS = Pattern.compile("支付|付款|扣款|消费|支出|转账|还款");
    // 明确说已经付了钱：带这些字的，就算里面有“积分”“立减”“账单详情”也照样算付款
    private static final Pattern STRONG_PAY = Pattern.compile("已支付|支付成功|成功支付|付款成功|成功付款|已付款|扣款成功|成功扣款|消费成功|付款金额|支付金额|实付|(?:向|给)[^，,。\\s]{1,16}?(?:付款|支付)\\s*[¥￥0-9]");
    // “收款方”“收款人”是付款详情里的字段，不代表收钱，先去掉再判断
    private static final Pattern PAYEE_FIELD = Pattern.compile("收款方|收款人|收款账户|收款单位|收款商户");
    // 收钱、退款、转入、提现：不是付款
    private static final Pattern INCOME_WORDS = Pattern.compile("收款|到账|收入|退款|转入|提现");
    // 账单提醒、广告：只有在没有明确付款字样时才跳过
    private static final Pattern PROMO_WORDS = Pattern.compile("应还|待还|还款日|账单|出账|额度|优惠|立减|活动|福利|抽奖|红包|领取|返现|奖励|积分|即将");
    // 明确是“已经还了 / 已经转出去了”的，即使带“账单”等字也算付款
    private static final Pattern DONE_PAY = Pattern.compile("还款成功|成功还款|已还款|已还清|转账成功|已转账|转账给");
    // 可能是还款：只做提示，不会自动关联
    private static final Pattern REPAY_HINT = Pattern.compile("还款|转账|花呗|白条|借呗|信用卡|借款");
    private static final Pattern MERCHANT = Pattern.compile("(?:向|在|给)([^，,。\\s]{1,16}?)(?:付款|支付|消费)");
    private static final Pattern TRANSFER_TO = Pattern.compile("转账给([^，,。\\s]{1,16})");
    // 微信关掉“通知显示消息详情”后，通知里只写“你收到了一条消息”
    private static final Pattern WX_HIDDEN = Pattern.compile("收到(?:了)?\\s*[0-9一二三四五六七八九十几]*\\s*条(?:新)?消息");

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        // 有些手机会把服务断开，主动请系统重新连上
        try {
            requestRebind(new ComponentName(this, PayListener.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 这里出错会让整个 app 崩掉，监听也跟着断，所以整段兜住
        try {
            handle(sbn);
        } catch (Exception ignored) {
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        String platform;
        String seenKey;
        if (WECHAT.equals(pkg)) {
            platform = "微信";
            seenKey = "seen_wx";
        } else if (ALIPAY.equals(pkg)) {
            platform = "支付宝";
            seenKey = "seen_ali";
        } else {
            return;
        }

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        // 常驻通知、分组汇总通知不是一条具体的消息
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        long now = System.currentTimeMillis();
        // 只记“什么时候收到过这个 app 的通知”，不存内容，用来判断监听是不是还活着
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(seenKey, now).apply();

        String all = collectText(n);
        String title = str(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(n.extras.getCharSequence(Notification.EXTRA_TEXT));

        // 微信里只认“微信支付”发来的，避免把聊天消息当成付款。聊天内容一律不保存
        if ("微信".equals(platform) && !all.contains("微信支付")) {
            if (WX_HIDDEN.matcher(all).find()) {
                log(now, platform, "（通知里只写了“收到消息”，看不到内容）",
                        "微信没开“通知显示消息详情”，认不出是不是付款", false);
            }
            return;
        }

        String cleaned = PAYEE_FIELD.matcher(all).replaceAll("");
        boolean done = DONE_PAY.matcher(cleaned).find();
        if (INCOME_WORDS.matcher(cleaned).find() && !done) {
            log(now, platform, all, "跳过：像是收钱、退款或转入", false);
            return;
        }
        if (!PAY_WORDS.matcher(cleaned).find()) {
            log(now, platform, all, "跳过：没有付款字样", false);
            return;
        }
        if (PROMO_WORDS.matcher(cleaned).find() && !done && !STRONG_PAY.matcher(cleaned).find()) {
            log(now, platform, all, "跳过：像是广告或账单提醒", false);
            return;
        }

        String raw = findAmount(cleaned);
        double amount = 0;
        if (!raw.isEmpty()) {
            try {
                amount = Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                amount = 0;
            }
        }
        boolean ok = amount > 0;

        String merchant = "";
        Matcher mm = MERCHANT.matcher(cleaned);
        if (mm.find()) {
            merchant = mm.group(1);
        } else {
            Matcher mt = TRANSFER_TO.matcher(cleaned);
            if (mt.find()) merchant = mt.group(1);
        }
        String hint = REPAY_HINT.matcher(cleaned).find() ? "repay" : "";

        String sig = platform + "|" + raw + "|" + all;
        String id = "p" + now;

        synchronized (LOCK) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            // 同一条通知被重新推送（比如微信更新了通知），不重复记
            if (sig.equals(sp.getString("lastSig", "")) && now - sp.getLong("lastTs", 0L) < DEDUPE_MS) return;
            try {
                JSONArray arr = new JSONArray(sp.getString("pending", "[]"));
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("amount", ok ? amount : 0);
                o.put("platform", platform);
                o.put("merchant", merchant);
                o.put("text", (title + " " + text).trim().isEmpty() ? cut(all, 80) : (title + " " + text).trim());
                o.put("ts", now);
                o.put("hint", hint);
                o.put("failed", !ok);
                arr.put(o);
                while (arr.length() > 50) arr.remove(0);

                JSONObject last = new JSONObject();
                last.put("ts", now);
                last.put("platform", platform);
                last.put("amount", ok ? amount : 0);
                last.put("ok", ok);

                sp.edit()
                        .putString("pending", arr.toString())
                        .putString("last", last.toString())
                        .putString("lastSig", sig)
                        .putLong("lastTs", now)
                        .apply();
            } catch (JSONException e) {
                return;
            }
        }
        log(now, platform, all,
                ok ? "识别成功：" + String.format(Locale.CHINA, "¥%.2f", amount) : "是付款，但没认出金额（已留在首页手动填）", ok);
        postReminder(id, amount, platform, merchant, ok, !hint.isEmpty());
    }

    /** 把通知里所有能看到的文字拼起来：标题、正文、展开后的长文、多行消息等 */
    private static String collectText(Notification n) {
        Bundle ex = n.extras;
        StringBuilder sb = new StringBuilder();
        add(sb, ex.getCharSequence(Notification.EXTRA_TITLE));
        add(sb, ex.getCharSequence(Notification.EXTRA_TITLE_BIG));
        add(sb, ex.getCharSequence(Notification.EXTRA_TEXT));
        add(sb, ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        add(sb, ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        add(sb, ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        add(sb, ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
        try {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) for (CharSequence l : lines) add(sb, l);
        } catch (Exception ignored) {
        }
        try {
            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null) {
                for (Parcelable p : msgs) {
                    if (p instanceof Bundle) add(sb, ((Bundle) p).getCharSequence("text"));
                }
            }
        } catch (Exception ignored) {
        }
        add(sb, n.tickerText);
        return sb.toString().trim();
    }

    private static void add(StringBuilder sb, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().trim();
        // 长文常常和正文一样，重复的就不再拼
        if (s.isEmpty() || sb.indexOf(s) >= 0) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(s);
    }

    /** 找付了多少钱；跳过“立减 ¥2”“奖励 0.5 元”这类优惠金额。找不到返回空串 */
    private static String findAmount(String s) {
        Pattern[] order = {AMT_LABELED, AMT_SIGN, AMT_WORD};
        for (Pattern p : order) {
            Matcher m = p.matcher(s);
            while (m.find()) {
                String before = s.substring(Math.max(0, m.start() - 4), m.start());
                if (DISCOUNT_BEFORE.matcher(before).find()) continue;
                String raw = m.group(1).replace(",", "");
                try {
                    if (Double.parseDouble(raw) > 0) return raw;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return "";
    }

    /** 最近 20 条微信支付、支付宝通知和判断结果，只存在手机上，给“我的”页排查用 */
    private void log(long ts, String platform, String text, String verdict, boolean ok) {
        synchronized (LOCK) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            try {
                JSONArray arr = new JSONArray(sp.getString("log", "[]"));
                JSONObject o = new JSONObject();
                o.put("ts", ts);
                o.put("platform", platform);
                o.put("text", cut(text, 160));
                o.put("verdict", verdict);
                o.put("ok", ok);
                arr.put(o);
                while (arr.length() > LOG_MAX) arr.remove(0);
                sp.edit().putString("log", arr.toString()).apply();
            } catch (JSONException ignored) {
            }
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private void postReminder(String id, double amount, String platform, String merchant, boolean ok, boolean maybeRepay) {
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

        String titleText;
        String body;
        if (!ok) {
            titleText = "检测到一笔" + platform + "付款，但没认出金额";
            body = "点一下手动记一笔";
        } else {
            String amt = String.format(Locale.CHINA, "¥%.2f", amount);
            if (maybeRepay) {
                titleText = "刚刚用" + platform + "付了 " + amt + "，是还款吗？";
                body = "点开选择：关联到某笔欠款，或者记为日常消费";
            } else {
                titleText = "刚刚用" + platform + "付了 " + amt;
                body = merchant.isEmpty() ? "点一下记一笔，选个分类就好" : "在 " + merchant + "，点一下记一笔";
            }
        }
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(titleText)
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
