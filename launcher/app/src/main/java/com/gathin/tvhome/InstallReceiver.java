package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * Result of a self-update install session (Updater.install). Not exported: only the PendingIntent
 * the launcher handed to the package installer reaches it.
 */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android's own confirmation; the owner already pressed Update on our prompt.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (RuntimeException e) {
                Log.w(Updater.TAG, "cannot show the install confirmation: " + e);
                Updater.installFailed(context);
            }
            return;
        }
        // On success Android replaces the process; the new version tidies up (Updater.afterStart).
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.w(Updater.TAG, "install failed, status " + status + ": " + message);
        Updater.installFailed(context);
        // Declining Android's confirmation is the same as Later: no message.
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, context.getString(R.string.update_failed,
                    message != null ? message : String.valueOf(status)), Toast.LENGTH_LONG).show();
        }
        Runnable listener = Updater.installListener;
        if (listener != null) listener.run();
    }
}
