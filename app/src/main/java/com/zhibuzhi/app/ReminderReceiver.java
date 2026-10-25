package com.zhibuzhi.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟到点，弹出还款提醒 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Reminders.ACTION.equals(intent.getAction())) return;
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        String debtId = intent.getStringExtra("debtId");
        Reminders.show(ctx,
                title == null || title.isEmpty() ? "还款提醒" : title,
                text == null ? "" : text,
                debtId == null ? "" : debtId);
    }
}
