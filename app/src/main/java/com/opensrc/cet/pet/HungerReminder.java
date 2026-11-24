package com.opensrc.cet.pet;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.opensrc.cet.R;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class HungerReminder extends BroadcastReceiver {
    private static final String REMINDER = "pet_hunger";
    private static final String ACTIVE_USER = "activeUser";
    private static final String FED_AT = "fedAt.";
    private static final String NOTIFIED_FOR = "notifiedFor.";
    private static final String DUE = "com.opensrc.cet.pet.HUNGER_DUE";
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

    public static synchronized void selectPet(Context context, String userId) {
        SharedPreferences prefs = preferences(context);
        if (!Objects.equals(userId, prefs.getString(ACTIVE_USER, null))) {
            context.getSystemService(AlarmManager.class).cancel(alarmIntent(context));
            context.getSystemService(NotificationManager.class).cancel(REMINDER, 0);
            prefs.edit().putString(ACTIVE_USER, userId).apply();
        }
        refresh(context);
    }

    public static synchronized void recordFeeding(Context context, String userId, long fedAtMillis) {
        Objects.requireNonNull(userId, "userId");
        if (fedAtMillis <= 0 || fedAtMillis > Long.MAX_VALUE - DAY_MILLIS) {
            throw new IllegalArgumentException("fedAtMillis");
        }
        SharedPreferences prefs = preferences(context);
        // 이전 먹이 사용 응답이 늦게 도착해도 최신 예약 유지
        if (fedAtMillis <= prefs.getLong(FED_AT + userId, 0)) return;
        prefs.edit().putLong(FED_AT + userId, fedAtMillis).apply();
        if (userId.equals(prefs.getString(ACTIVE_USER, null))) {
            context.getSystemService(NotificationManager.class).cancel(REMINDER, 0);
            refresh(context);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DUE.equals(intent.getAction()) || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            refresh(context);
        }
    }

    private static synchronized void refresh(Context context) {
        SharedPreferences prefs = preferences(context);
        String userId = prefs.getString(ACTIVE_USER, null);
        if (userId == null) return;
        long fedAt = prefs.getLong(FED_AT + userId, 0);
        if (fedAt == 0 || fedAt == prefs.getLong(NOTIFIED_FOR + userId, 0)) return;

        long dueAt = fedAt + DAY_MILLIS;
        if (System.currentTimeMillis() < dueAt) {
            context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, dueAt, alarmIntent(context));
        } else if (notifyIfAllowed(context)) {
            prefs.edit().putLong(NOTIFIED_FOR + userId, fedAt).apply();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(REMINDER, Context.MODE_PRIVATE);
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent due = new Intent(context, HungerReminder.class).setAction(DUE);
        return PendingIntent.getBroadcast(context, 0, due, PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean notifyIfAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        manager.createNotificationChannel(new NotificationChannel(REMINDER,
                context.getString(R.string.pet_hunger_channel), NotificationManager.IMPORTANCE_DEFAULT));
        if (manager.getNotificationChannel(REMINDER).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        Notification.Builder notification = new Notification.Builder(context, REMINDER)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.pet_hunger_title))
                .setContentText(context.getString(R.string.pet_hunger_message))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            notification.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE));
        }
        try {
            manager.notify(REMINDER, 0, notification.build());
            return true;
        } catch (SecurityException revoked) {
            // 권한 확인 직후 철회된 경우
            return false;
        }
    }
}
