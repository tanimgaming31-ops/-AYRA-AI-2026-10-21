package com.ayra.assistant;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AyraNotificationListener extends NotificationListenerService {
    private static final ConcurrentLinkedDeque<String> LIST=new ConcurrentLinkedDeque<>();
    @Override public void onNotificationPosted(StatusBarNotification n) {
        if(n==null||n.getNotification()==null||n.getPackageName().equals(getPackageName()))return;
        Notification notification=n.getNotification();String title=notification.extras.getString(Notification.EXTRA_TITLE,"");CharSequence body=notification.extras.getCharSequence(Notification.EXTRA_TEXT,"");
        if(title.isEmpty()&&(body==null||body.length()==0))return;
        String message=n.getPackageName()+" — "+title+": "+(body==null?"":body);
        LIST.addFirst(message.length()>280?message.substring(0,280):message);
        while(LIST.size()>12)LIST.pollLast();
    }
    public static String latest(){StringBuilder b=new StringBuilder();for(String s:LIST){if(b.length()>0)b.append('\n');b.append("• ").append(s);}return b.toString();}
}
