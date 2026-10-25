package com.zhibuzhi.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 手机重启或 app 更新后，系统会清掉闹钟，这里重新排好还款提醒 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Reminders.rearm(ctx);
        }
    }
}
