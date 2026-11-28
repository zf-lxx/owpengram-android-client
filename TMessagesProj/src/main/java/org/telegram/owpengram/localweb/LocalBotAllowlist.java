package org.telegram.owpengram.localweb;

import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * Bots that may open a local embedded admin page and talk over the
 * session-channel document convention. Unlisted bots never get a
 * JavascriptInterface or a local WebView.
 */
public final class LocalBotAllowlist {

    public static final String[] USERNAMES = {
            "manus_bot",
    };

    public static final long[] BOT_IDS = {
            1780243206L,
    };

    private LocalBotAllowlist() {
    }

    public static boolean isAllowed(TLRPC.User user) {
        if (user == null || !user.bot) {
            return false;
        }
        for (long id : BOT_IDS) {
            if (user.id == id) {
                return true;
            }
        }
        String username = UserObject.getPublicUsername(user);
        if (username != null) {
            for (String allowed : USERNAMES) {
                if (allowed.equalsIgnoreCase(username)) {
                    return true;
                }
            }
        }
        return false;
    }
}
