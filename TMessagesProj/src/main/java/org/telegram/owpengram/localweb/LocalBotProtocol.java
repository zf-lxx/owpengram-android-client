package org.telegram.owpengram.localweb;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Client-only document convention for a local bot WebView.
 *
 * <p>Official / unmodified Telegram clients ignore this and just show a
 * JSON file (or the existing HTTPS Mini App button). The forked client
 * hides these documents from the chat bubble, loads the page locally,
 * and uses them as the API channel.
 *
 * <p>Filenames:
 * <ul>
 *   <li>{@code owpengram-local-v1-req.json} — client → bot</li>
 *   <li>{@code owpengram-local-v1-res.json} — bot → client</li>
 *   <li>{@code owpengram-local-v1.json} — accepted as either</li>
 * </ul>
 *
 * Payload (JSON): {@code v=1}, {@code channel=owpengram-local},
 * {@code type=req|res|open}, plus {@code id}, {@code method}, {@code path},
 * {@code body}, {@code status}, {@code data}/{@code error}.
 */
public final class LocalBotProtocol {

    public static final int VERSION = 1;
    public static final String CHANNEL = "owpengram-local";
    public static final String TYPE_REQ = "req";
    public static final String TYPE_RES = "res";
    public static final String TYPE_OPEN = "open";

    public static final String FILENAME_REQ = "owpengram-local-v1-req.json";
    public static final String FILENAME_RES = "owpengram-local-v1-res.json";
    public static final String FILENAME_ANY = "owpengram-local-v1.json";
    public static final String MIME = "application/json";
    public static final String CAPTION = "owpengram-local-v1";

    public static final String VIRTUAL_HOST = "app.owpengram.local";
    public static final String VIRTUAL_ADMIN_URL = "https://" + VIRTUAL_HOST + "/admin/index.html";
    public static final String ASSETS_DIR = "owpengram_local_admin";

    public static final int MAX_BODY_BYTES = 256 * 1024;
    public static final int REQUEST_TIMEOUT_MS = 60_000;

    private LocalBotProtocol() {
    }

    public static boolean isControlFileName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String n = name.trim();
        return FILENAME_REQ.equalsIgnoreCase(n)
                || FILENAME_RES.equalsIgnoreCase(n)
                || FILENAME_ANY.equalsIgnoreCase(n);
    }

    public static String documentFileName(TLRPC.Document document) {
        String name = FileLoader.getDocumentFileName(document);
        return name == null ? "" : name;
    }

    public static boolean isHiddenMessage(MessageObject message) {
        if (message == null) {
            return false;
        }
        TLRPC.Document doc = message.getDocument();
        if (doc != null && isControlFileName(documentFileName(doc))) {
            return true;
        }
        CharSequence cap = message.caption;
        if (cap != null && CAPTION.equals(cap.toString().trim())) {
            return doc != null;
        }
        if (message.messageOwner != null && CAPTION.equals(String.valueOf(message.messageOwner.message).trim())) {
            return doc != null;
        }
        return false;
    }

    public static boolean containsHidden(ArrayList<MessageObject> arr) {
        if (arr == null) {
            return false;
        }
        for (int i = 0, n = arr.size(); i < n; i++) {
            if (isHiddenMessage(arr.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a new list without control documents. The original list is
     * not mutated. Each hidden message is handed to {@link LocalBotChannel}.
     */
    public static ArrayList<MessageObject> withoutHidden(int account, ArrayList<MessageObject> arr) {
        ArrayList<MessageObject> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0, n = arr.size(); i < n; i++) {
            MessageObject msg = arr.get(i);
            if (isHiddenMessage(msg)) {
                LocalBotChannel.get(account).onControlMessage(msg);
            } else if (msg != null) {
                out.add(msg);
            }
        }
        return out;
    }

    public static boolean looksLikeAdminButton(String buttonText, String buttonUrl) {
        String text = buttonText == null ? "" : buttonText;
        String url = buttonUrl == null ? "" : buttonUrl.toLowerCase(Locale.US);
        if (text.contains("管理页") || text.contains("管理後台") || text.contains("管理后台")) {
            return true;
        }
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("admin") || lower.contains("settings")) {
            return true;
        }
        return url.contains("/admin") || url.contains("owpengram-local") || url.contains("miniapp");
    }

    public static JSONObject requestPayload(String id, String method, String path, String body) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("v", VERSION);
        obj.put("channel", CHANNEL);
        obj.put("type", TYPE_REQ);
        obj.put("id", id);
        obj.put("page", "admin");
        obj.put("method", method == null ? "GET" : method.toUpperCase(Locale.US));
        obj.put("path", path == null ? "" : path);
        if (!TextUtils.isEmpty(body)) {
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    obj.put("body", new JSONObject(trimmed));
                } catch (Exception e) {
                    obj.put("bodyText", body);
                }
            } else {
                obj.put("bodyText", body);
            }
        }
        return obj;
    }

    public static String payloadId(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String id = obj.optString("id", "");
        return TextUtils.isEmpty(id) ? null : id;
    }

    public static String payloadType(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        return obj.optString("type", "");
    }

    public static boolean isValidEnvelope(JSONObject obj) {
        if (obj == null) {
            return false;
        }
        if (obj.optInt("v", 0) != VERSION) {
            return false;
        }
        String channel = obj.optString("channel", "");
        return TextUtils.isEmpty(channel) || CHANNEL.equals(channel);
    }
}
