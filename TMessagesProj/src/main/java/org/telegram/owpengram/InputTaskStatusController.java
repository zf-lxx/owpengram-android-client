package org.telegram.owpengram;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Explicit in-progress task status shown above the chat composer (avatar +
 * shimmering status line + elapsed time).
 *
 * <p>For allowlisted agent bots (see {@link AgentBotAllowlist}), the forked
 * client shows this bar as soon as the user sends a message, and hides the
 * server's wait-placeholder bubble (「正在处理…」 and its elapsed-time
 * edits). Official / unmodified Telegram does not include this logic and
 * keeps the placeholder bubble as the fallback.
 *
 * <p>It is <b>not</b> tied to Telegram typing indicators or {@code sendChatAction}
 * for ordinary bots. A typing indicator alone never opens the bar — even
 * for an allowlisted agent, typing only keeps an already-visible bar alive.
 * The discreet debug preview still calls {@link #show} after a version-tap.
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
 * <p>The bar also auto-hides when a real incoming message from that peer
 * arrives, when the user leaves the chat, or after {@link #SILENCE_TIMEOUT_MS}
 * (3 minutes) with no {@link #show} / {@link #update} call. Wait-placeholder
 * messages and typing from an allowlisted agent count as updates, so a long
 * turn does not time out while work continues.
 */
public final class InputTaskStatusController {

    /** Safety timeout: hide after this long with no show()/update(). */
    public static final long SILENCE_TIMEOUT_MS = 3 * 60 * 1000L;

    /** Same copy the workspace harness posts after 8s. */
    public static final String WAIT_PLACEHOLDER = "正在处理…";
    /** Same copy the workspace harness posts while compacting context. */
    public static final String COMPACTION_STATUS = "正在压缩上下文";

    private static final Pattern WAIT_ELAPSED_PATTERN = Pattern.compile(
            "^还在处理，已等待 \\d+ (秒|分钟)(…|\\.\\.\\.?)$");

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
    private static final HashSet<String> hiddenPlaceholderKeys = new HashSet<>();
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

    public static boolean isAgentBot(TLRPC.User user) {
        return AgentBotAllowlist.isAllowed(user);
    }

    public static boolean isAgentDialog(int account, long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        try {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            return isAgentBot(user);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * True if {@code text} is the harness wait placeholder, an elapsed-time
     * edit of it, or the compaction status line. These are hidden on the
     * forked client and drive the input bar instead.
     */
    public static boolean isWaitPlaceholderText(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String t = text.toString().trim();
        if (t.equals(WAIT_PLACEHOLDER) || t.equals("正在处理...") || t.equals("正在处理")) {
            return true;
        }
        if (t.equals(COMPACTION_STATUS)) {
            return true;
        }
        return WAIT_ELAPSED_PATTERN.matcher(t).matches();
    }

    public static String messagePlainText(MessageObject message) {
        if (message == null) {
            return null;
        }
        if (message.messageOwner != null && !TextUtils.isEmpty(message.messageOwner.message)) {
            return message.messageOwner.message;
        }
        if (message.messageText != null) {
            return message.messageText.toString();
        }
        return null;
    }

    /**
     * Incoming plain-text wait placeholder from an allowlisted agent bot.
     * Media / service messages are never treated as placeholders.
     */
    public static boolean isAgentWaitPlaceholder(int account, MessageObject message) {
        if (message == null || message.isOutOwner()) {
            return false;
        }
        if (message.messageOwner != null && message.messageOwner.action != null) {
            return false;
        }
        if (message.messageOwner != null && message.messageOwner.media != null
                && !(message.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)) {
            return false;
        }
        if (!isWaitPlaceholderText(messagePlainText(message))) {
            return false;
        }
        long dialogId = message.getDialogId();
        if (dialogId == 0 && message.messageOwner != null) {
            dialogId = MessageObject.getDialogId(message.messageOwner);
        }
        return isAgentDialog(account, dialogId);
    }

    public static boolean containsWaitPlaceholder(int account, ArrayList<MessageObject> arr) {
        if (arr == null) {
            return false;
        }
        for (int i = 0, n = arr.size(); i < n; i++) {
            if (isAgentWaitPlaceholder(account, arr.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copy of {@code arr} without allowlisted-agent wait placeholders.
     * The original list is not mutated. Hidden IDs are remembered so a later
     * edit into a real reply can be shown as a normal message. When
     * {@code driveBar} is true (live incoming messages), the input bar is
     * shown or kept alive. History loads should pass false and call
     * {@link #onMessagesLoaded} instead, so an old leftover placeholder does
     * not reopen the bar after a later real reply.
     */
    public static ArrayList<MessageObject> withoutWaitPlaceholder(int account, ArrayList<MessageObject> arr) {
        return withoutWaitPlaceholder(account, arr, true);
    }

    public static ArrayList<MessageObject> withoutWaitPlaceholder(int account, ArrayList<MessageObject> arr, boolean driveBar) {
        ArrayList<MessageObject> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0, n = arr.size(); i < n; i++) {
            MessageObject msg = arr.get(i);
            if (isAgentWaitPlaceholder(account, msg)) {
                rememberPlaceholder(account, msg, driveBar);
            } else if (msg != null) {
                out.add(msg);
            }
        }
        return out;
    }

    /**
     * Optimistic show: the user just sent a message to an allowlisted agent.
     * Mid-turn follow-ups reuse the same bar (same start time), so it does
     * not flicker hide/show.
     */
    public static void onUserSentToAgent(int account, long dialogId) {
        if (!isAgentDialog(account, dialogId)) {
            return;
        }
        show(account, dialogId, defaultStatusText());
    }

    /**
     * Typing from an allowlisted agent only refreshes an already visible bar.
     * It never opens the bar by itself.
     */
    public static void onAgentTyping(int account, long dialogId) {
        if (!isShowing(account, dialogId)) {
            return;
        }
        if (!isAgentDialog(account, dialogId)) {
            return;
        }
        update(account, dialogId, null);
    }

    /**
     * After history is loaded, restore the bar if this chat is still waiting
     * on an allowlisted agent (latest message is a hidden placeholder, or a
     * recent outgoing message with no reply yet).
     */
    public static void onMessagesLoaded(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (!isAgentDialog(account, dialogId) || messages == null || messages.isEmpty()) {
            return;
        }
        MessageObject latest = null;
        for (int i = 0, n = messages.size(); i < n; i++) {
            MessageObject msg = messages.get(i);
            if (msg == null || msg.getId() <= 0) {
                continue;
            }
            if (latest == null || msg.getId() > latest.getId()) {
                latest = msg;
            }
        }
        if (latest == null) {
            return;
        }
        if (isAgentWaitPlaceholder(account, latest)) {
            rememberPlaceholder(account, latest);
            return;
        }
        if (latest.isOutOwner() && isRecentEnough(latest)) {
            showSince(account, dialogId, defaultStatusText(), startedAtFromMessage(latest));
        }
    }

    /**
     * Process incoming edits. Wait-placeholder edits keep the message hidden
     * and refresh the bar. Edits that turn a hidden placeholder into a real
     * reply are added to {@code revealed} so the chat can insert them, and
     * the bar is hidden. All non-placeholder edits are also added to
     * {@code visibleEdits} (a later edit of an already-revealed reply still
     * needs to update the bubble). Hidden IDs are kept so UI and
     * notifications can both observe the same reveal.
     */
    public static void applyEdits(int account, ArrayList<MessageObject> edits,
                                  ArrayList<MessageObject> revealed,
                                  ArrayList<MessageObject> visibleEdits) {
        if (edits == null) {
            return;
        }
        for (int i = 0, n = edits.size(); i < n; i++) {
            MessageObject msg = edits.get(i);
            if (msg == null) {
                continue;
            }
            if (isAgentWaitPlaceholder(account, msg)) {
                rememberPlaceholder(account, msg);
                continue;
            }
            if (wasHiddenPlaceholder(account, msg)) {
                if (revealed != null) {
                    revealed.add(msg);
                }
                long dialogId = msg.getDialogId();
                if (dialogId == 0 && msg.messageOwner != null) {
                    dialogId = MessageObject.getDialogId(msg.messageOwner);
                }
                hide(account, dialogId);
            }
            if (visibleEdits != null) {
                visibleEdits.add(msg);
            }
        }
    }

    /**
     * Collect placeholder-edits that just became real replies, for the
     * notification path (they were never pushed, so they must be treated as
     * new messages).
     */
    public static ArrayList<MessageObject> takeRevealedEdits(int account, ArrayList<MessageObject> edits) {
        ArrayList<MessageObject> revealed = new ArrayList<>();
        applyEdits(account, edits, revealed, null);
        return revealed;
    }

    public static boolean wasHiddenPlaceholder(int account, MessageObject message) {
        if (message == null) {
            return false;
        }
        long dialogId = message.getDialogId();
        if (dialogId == 0 && message.messageOwner != null) {
            dialogId = MessageObject.getDialogId(message.messageOwner);
        }
        synchronized (hiddenPlaceholderKeys) {
            return hiddenPlaceholderKeys.contains(placeholderKey(account, dialogId, message.getId()));
        }
    }

    /**
     * If this dialog's last-message preview is a hidden wait placeholder,
     * return the bar's default copy so the chat list does not show
     * 「正在处理…」. Returns null when the preview should be left alone.
     */
    public static CharSequence dialogPreviewOverride(int account, MessageObject message) {
        if (isAgentWaitPlaceholder(account, message)) {
            return defaultStatusText();
        }
        return null;
    }

    public static void show(int account, long dialogId, String statusText) {
        showInternal(account, dialogId, statusText, 0L);
    }

    public static void showSince(int account, long dialogId, String statusText, long startedAtElapsedRealtime) {
        showInternal(account, dialogId, statusText, startedAtElapsedRealtime);
    }

    private static void showInternal(int account, long dialogId, String statusText, long startedAtElapsedRealtime) {
        AndroidUtilities.runOnUIThread(() -> {
            if (dialogId == 0) {
                return;
            }
            ensureAccountObserver(account);
            String text = TextUtils.isEmpty(statusText) ? defaultStatusText() : statusText;
            String key = key(account, dialogId);
            Status existing = statuses.get(key);
            if (existing != null) {
                existing.text = text;
                existing.lastUpdateElapsedRealtime = SystemClock.elapsedRealtime();
                if (startedAtElapsedRealtime > 0 && startedAtElapsedRealtime < existing.startedAtElapsedRealtime) {
                    existing.startedAtElapsedRealtime = startedAtElapsedRealtime;
                }
            } else {
                Status created = new Status(account, dialogId, text);
                if (startedAtElapsedRealtime > 0) {
                    created.startedAtElapsedRealtime = startedAtElapsedRealtime;
                }
                statuses.put(key, created);
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
            Iterator<Map.Entry<String, Status>> it = statuses.entrySet().iterator();
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

    private static void rememberPlaceholder(int account, MessageObject message) {
        rememberPlaceholder(account, message, true);
    }

    private static void rememberPlaceholder(int account, MessageObject message, boolean driveBar) {
        if (message == null) {
            return;
        }
        long dialogId = message.getDialogId();
        if (dialogId == 0 && message.messageOwner != null) {
            dialogId = MessageObject.getDialogId(message.messageOwner);
        }
        if (dialogId == 0) {
            return;
        }
        synchronized (hiddenPlaceholderKeys) {
            hiddenPlaceholderKeys.add(placeholderKey(account, dialogId, message.getId()));
        }
        if (!driveBar) {
            return;
        }
        String statusText = statusTextForPlaceholder(messagePlainText(message));
        long startedAt = startedAtFromMessage(message);
        if (isShowing(account, dialogId)) {
            update(account, dialogId, statusText);
        } else {
            showSince(account, dialogId, statusText, startedAt);
        }
    }

    private static String statusTextForPlaceholder(String text) {
        if (text != null && text.trim().equals(COMPACTION_STATUS)) {
            return COMPACTION_STATUS;
        }
        return defaultStatusText();
    }

    private static boolean isRecentEnough(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - message.messageOwner.date * 1000L);
        return ageMs <= SILENCE_TIMEOUT_MS;
    }

    private static long startedAtFromMessage(MessageObject message) {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (message == null || message.messageOwner == null || message.messageOwner.date <= 0) {
            return nowElapsed;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - message.messageOwner.date * 1000L);
        return nowElapsed - Math.min(ageMs, SILENCE_TIMEOUT_MS);
    }

    private static String placeholderKey(int account, long dialogId, int messageId) {
        return account + ":" + dialogId + ":" + messageId;
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
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.replaceMessagesObjects);
    }

    private static final class AccountObserver implements NotificationCenter.NotificationCenterDelegate {
        final int account;

        AccountObserver(int account) {
            this.account = account;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.replaceMessagesObjects) {
                if (args == null || args.length < 2) {
                    return;
                }
                long dialogId = (Long) args[0];
                ArrayList<MessageObject> messageObjects = (ArrayList<MessageObject>) args[1];
                if (messageObjects == null) {
                    return;
                }
                for (int i = 0; i < messageObjects.size(); i++) {
                    MessageObject msg = messageObjects.get(i);
                    if (isAgentWaitPlaceholder(account, msg)) {
                        rememberPlaceholder(account, msg);
                    } else if (wasHiddenPlaceholder(account, msg)) {
                        hide(account, dialogId);
                    }
                }
                return;
            }
            if (id != NotificationCenter.didReceiveNewMessages || args == null || args.length < 2) {
                return;
            }
            long dialogId = (Long) args[0];
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
                return; // scheduled
            }
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) {
                return;
            }
            boolean showing = statuses.get(key(account, dialogId)) != null;
            boolean anyRealIncoming = false;
            for (int i = 0; i < messages.size(); i++) {
                MessageObject msg = messages.get(i);
                if (msg == null || msg.isOutOwner()) {
                    continue;
                }
                if (msg.getDialogId() != 0 && msg.getDialogId() != dialogId) {
                    continue;
                }
                if (isAgentWaitPlaceholder(account, msg)) {
                    rememberPlaceholder(account, msg);
                    showing = true;
                    continue;
                }
                anyRealIncoming = true;
            }
            if (anyRealIncoming && (showing || statuses.get(key(account, dialogId)) != null)) {
                hide(account, dialogId);
            }
        }
    }
}
