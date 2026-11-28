package org.telegram.owpengram.localweb;

import android.app.Activity;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * Entry points for opening a sandboxed, in-app local admin WebView for an
 * allowlisted bot. Official Telegram Mini App URLs are not loaded.
 */
public final class LocalBotWebViewController {

    private LocalBotWebViewController() {
    }

    public static boolean tryOpenFromWebAppButton(BaseFragment fragment, int account, TLRPC.User bot, long dialogId, String buttonUrl, String buttonText) {
        if (!LocalBotAllowlist.isAllowed(bot)) {
            return false;
        }
        if (!LocalBotProtocol.looksLikeAdminButton(buttonText, buttonUrl)) {
            return false;
        }
        return openAdmin(fragment, account, bot, dialogId);
    }

    public static boolean openAdmin(BaseFragment fragment, int account, TLRPC.User bot, long dialogId) {
        if (fragment == null) {
            return false;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return false;
        }
        if (!LocalBotAllowlist.isAllowed(bot)) {
            return false;
        }
        if (account < 0) {
            account = UserConfig.selectedAccount;
        }
        if (dialogId == 0 && bot != null) {
            dialogId = bot.id;
        }
        LocalBotChannel.get(account);
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        LocalBotWebViewSheet sheet = new LocalBotWebViewSheet(activity, resourcesProvider, account, bot, dialogId);
        sheet.show();
        return true;
    }
}
