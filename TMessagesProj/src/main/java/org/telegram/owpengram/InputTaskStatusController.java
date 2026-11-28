package org.telegram.owpengram;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Explicit in-progress task status shown above the chat composer (avatar +
 * shimmering status line + elapsed time). This is a dedicated API for agent
 * or task invocations.
 *
 * <p>It is <b>not</b> tied to Telegram typing indicators or {@code sendChatAction}.
 * Ordinary bot chats stay unchanged unless an agent (or the discreet debug
 * preview) calls {@link #show}.
 *
 * <p>Usage from a future agent / integration:
 * <pre>
 *   InputTaskStatusController.show(account, dialogId, "排查消息延迟原因");
 *   InputTaskStatusController.update(account, dialogId, "正在生成回复");
 *   InputTaskStatusController.hide(account, dialogId);
 * </pre>
 *
 * Passing {@code null} or an empty status string uses the default copy
 * 「正在回复」 (or "Replying" when the UI language is not Chinese).
 *
 * <p>The bar also auto-hides when a new incoming message from that peer
 * arrives, when the user leaves the chat, or after {@link #SILENCE_TIMEOUT_MS}
 * (3 minutes) with no {@link #show} / {@link #update} call.
 */
public final class InputTaskStatusController {

    /** Safety timeout: hide after this long with no show()/update(). */
    public static final long SILENCE_TIMEOUT_MS = 3 * 60 * 1000L;

    public static final class Status {
        public final int account;
        public final long dialogId;
        public String text;
        public long startedAtElapsedRealtime;
        public long lastUpdateElapsedRealtime;

        Status(int account, long dialogId, String text) {
            this.account = account;
            this.dialogId = dialogId;
            this.text = text;
            long now = SystemClock.elapsedRealtime();
            this.startedAtElapsedRealtime = now;
            this.lastUpdateElapsedRealtime = now;
        }
    }

    private static final HashMap<String, Status> statuses = new HashMap<>();
    private static final HashMap<Integer, AccountObserver> accountObservers = new HashMap<>();
    private static boolean previewEnabled;
    private static Runnable silenceCheck;

    private InputTaskStatusController() {
    }

    public static String defaultStatusText() {
        if (isChineseLocale()) {
            return "正在回复";
        }
        String s = LocaleController.getString(R.string.InputTaskStatusDefault);
        return TextUtils.isEmpty(s) ? "Replying" : s;
    }

    public static boolean isChineseLocale() {
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            String name = info != null ? info.shortName : null;
            if (name != null && name.toLowerCase().startsWith("zh")) {
                return true;
            }
        } catch (Exception ignored) {
        }
        java.util.Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale != null) {
            String lang = locale.getLanguage();
            return lang != null && lang.toLowerCase().startsWith("zh");
        }
        return false;
    }

    public static String formatStatusLine(String statusText, long elapsedMs) {
        String text = TextUtils.isEmpty(statusText) ? defaultStatusText() : statusText;
        long sec = Math.max(0, elapsedMs / 1000L);
        if (sec < 1) {
            return text;
        }
        if (isChineseLocale()) {
            if (sec < 60) {
                return text + " · " + sec + "秒";
            }
            return text + " · " + (sec / 60) + "分钟";
        }
        if (sec < 60) {
            return text + " · " + sec + "s";
        }
        return text + " · " + (sec / 60) + "m";
    }

    public static void show(int account, long dialogId, String statusText) {
        AndroidUtilities.runOnUIThread(() -> {
            if (dialogId == 0) {
                return;
            }
            ensureAccountObserver(account);
            String text = TextUtils.isEmpty(statusText) ? defaultStatusText() : statusText;
            long key = key(account, dialogId);
            Status existing = statuses.get(key);
            if (existing != null) {
                existing.text = text;
                existing.lastUpdateElapsedRealtime = SystemClock.elapsedRealtime();
            } else {
                statuses.put(key, new Status(account, dialogId, text));
            }
            notifyChanged(account, dialogId);
            scheduleSilenceCheck();
        });
    }

    public static void update(int account, long dialogId, String statusText) {
        AndroidUtilities.runOnUIThread(() -> {
            Status existing = statuses.get(key(account, dialogId));
            if (existing == null) {
                show(account, dialogId, statusText);
                return;
            }
            if (!TextUtils.isEmpty(statusText)) {
                existing.text = statusText;
            }
            existing.lastUpdateElapsedRealtime = SystemClock.elapsedRealtime();
            notifyChanged(account, dialogId);
            scheduleSilenceCheck();
        });
    }

    public static void hide(int account, long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            if (statuses.remove(key(account, dialogId)) != null) {
                notifyChanged(account, dialogId);
            }
        });
    }

    public static void hideAll() {
        AndroidUtilities.runOnUIThread(() -> {
            if (statuses.isEmpty()) {
                notifyPreviewOrGlobal(UserConfig.selectedAccount, 0L);
                return;
            }
            HashMap<Integer, ArrayList<Long>> byAccount = new HashMap<>();
            Iterator<Map.Entry<Long, Status>> it = statuses.entrySet().iterator();
            while (it.hasNext()) {
                Status s = it.next().getValue();
                it.remove();
                ArrayList<Long> list = byAccount.get(s.account);
                if (list == null) {
                    list = new ArrayList<>();
                    byAccount.put(s.account, list);
                }
                list.add(s.dialogId);
            }
            for (Map.Entry<Integer, ArrayList<Long>> e : byAccount.entrySet()) {
                for (int i = 0; i < e.getValue().size(); i++) {
                    notifyChanged(e.getKey(), e.getValue().get(i));
                }
            }
        });
    }

    public static Status get(int account, long dialogId) {
        return statuses.get(key(account, dialogId));
    }

    public static boolean isShowing(int account, long dialogId) {
        return get(account, dialogId) != null;
    }

    /**
     * Discreet preview for this APK: when enabled, any open chat shows the
     * default status bar so the effect can be reviewed. Ordinary bots are
     * unaffected while this remains off (the default).
     */
    public static void setPreviewEnabled(boolean enabled) {
        AndroidUtilities.runOnUIThread(() -> {
            if (previewEnabled == enabled) {
                return;
            }
            previewEnabled = enabled;
            if (!enabled) {
                hideAll();
            }
            notifyPreviewOrGlobal(UserConfig.selectedAccount, 0L);
        });
    }

    public static boolean isPreviewEnabled() {
        return previewEnabled;
    }

    public static boolean togglePreviewEnabled() {
        boolean next = !previewEnabled;
        setPreviewEnabled(next);
        return next;
    }

    private static void notifyChanged(int account, long dialogId) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.inputTaskStatusChanged, dialogId);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.inputTaskStatusChanged, dialogId);
    }

    private static void notifyPreviewOrGlobal(int account, long dialogId) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.inputTaskStatusChanged, dialogId);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.inputTaskStatusChanged, dialogId);
    }

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    private static void scheduleSilenceCheck() {
        if (silenceCheck != null) {
            AndroidUtilities.cancelRunOnUIThread(silenceCheck);
        }
        silenceCheck = InputTaskStatusController::checkSilenceTimeout;
        AndroidUtilities.runOnUIThread(silenceCheck, 15_000);
    }

    private static void checkSilenceTimeout() {
        silenceCheck = null;
        if (statuses.isEmpty()) {
            return;
        }
        if (previewEnabled) {
            scheduleSilenceCheck();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        ArrayList<Status> expired = null;
        for (Status s : statuses.values()) {
            if (now - s.lastUpdateElapsedRealtime >= SILENCE_TIMEOUT_MS) {
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(s);
            }
        }
        if (expired != null) {
            for (int i = 0; i < expired.size(); i++) {
                Status s = expired.get(i);
                statuses.remove(key(s.account, s.dialogId));
                notifyChanged(s.account, s.dialogId);
            }
        }
        if (!statuses.isEmpty()) {
            scheduleSilenceCheck();
        }
    }

    private static void ensureAccountObserver(int account) {
        if (accountObservers.containsKey(account)) {
            return;
        }
        AccountObserver observer = new AccountObserver(account);
        accountObservers.put(account, observer);
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.didReceiveNewMessages);
    }

    private static final class AccountObserver implements NotificationCenter.NotificationCenterDelegate {
        final int account;

        AccountObserver(int account) {
            this.account = account;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id != NotificationCenter.didReceiveNewMessages || args == null || args.length < 2) {
                return;
            }
            long dialogId = (Long) args[0];
            if (statuses.get(key(account, dialogId)) == null) {
                return;
            }
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
                return; // scheduled
            }
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) {
                return;
            }
            for (int i = 0; i < messages.size(); i++) {
                MessageObject msg = messages.get(i);
                if (msg == null || msg.isOutOwner()) {
                    continue;
                }
                if (msg.getDialogId() != dialogId) {
                    continue;
                }
                hide(account, dialogId);
                return;
            }
        }
    }
}
