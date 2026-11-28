package org.telegram.owpengram;

import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * Agent bots that may use the composer task-status bar (avatar + shimmering
 * status line) instead of a visible wait-placeholder bubble.
 *
 * <p>Ordinary bots are never listed here: a typing indicator or a normal
 * message must not open the bar. Official / unmodified Telegram ignores this
 * list and keeps the existing 「正在处理…」 placeholder.
 */
public final class AgentBotAllowlist {

    public static final String[] USERNAMES = {
            "manus_bot",
    };

    public static final long[] BOT_IDS = {
            1780243206L,
    };

    private AgentBotAllowlist() {
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
