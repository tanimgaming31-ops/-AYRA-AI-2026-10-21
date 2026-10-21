package com.ayra.assistant;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String message=intent.getStringExtra("message");if(message==null)return;
        int id=intent.getIntExtra("notificationId",22);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,"ayra_reminders"):new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("AYRA reminder").setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message)).setAutoCancel(true);
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);manager.notify(id,b.build());
    }
}
