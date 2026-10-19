package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing with notifications. Android only lists other apps' media sessions to a caller that
 * names an enabled notification listener of its own, so this exists for the Now playing card. The
 * install script enables it (cmd notification allow_listener); without that the card never shows.
 */
public class NowPlayingListener extends NotificationListenerService {
}
