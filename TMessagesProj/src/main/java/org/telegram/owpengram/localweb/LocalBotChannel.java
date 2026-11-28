package org.telegram.owpengram.localweb;

import android.text.TextUtils;
import android.util.SparseArray;

import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Sends and receives {@link LocalBotProtocol} documents over the existing
 * Telegram chat, without a public HTTP origin.
 */
public final class LocalBotChannel implements NotificationCenter.NotificationCenterDelegate {

    public interface RequestListener {
        void onResult(String requestId, int status, String bodyJson);

        void onError(String requestId, String message);
    }

    private static final SparseArray<LocalBotChannel> INSTANCES = new SparseArray<>();

    public static LocalBotChannel get(int account) {
        synchronized (INSTANCES) {
            LocalBotChannel channel = INSTANCES.get(account);
            if (channel == null) {
                channel = new LocalBotChannel(account);
                INSTANCES.put(account, channel);
                channel.register();
            }
            return channel;
        }
    }

    private final int currentAccount;
    private final HashMap<String, Pending> pending = new HashMap<>();
    private final HashMap<String, MessageObject> waitingForFile = new HashMap<>();
    private final HashSet<String> processed = new HashSet<>();

    private LocalBotChannel(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    private void register() {
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
        center.addObserver(this, NotificationCenter.httpFileDidLoad);
        center.addObserver(this, NotificationCenter.httpFileDidFailedLoad);
    }

    public void sendRequest(long dialogId, String requestId, String method, String path, String body, RequestListener listener) {
        AndroidUtilities.runOnUIThread(() -> {
            Pending prev = pending.remove(requestId);
            if (prev != null) {
                AndroidUtilities.cancelRunOnUIThread(prev.timeout);
            }
            Pending p = new Pending();
            p.id = requestId;
            p.dialogId = dialogId;
            p.listener = listener;
            p.timeout = () -> fail(requestId, "管理页请求超时");
            pending.put(requestId, p);
            AndroidUtilities.runOnUIThread(p.timeout, LocalBotProtocol.REQUEST_TIMEOUT_MS);
            try {
                JSONObject payload = LocalBotProtocol.requestPayload(requestId, method, path, body);
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > LocalBotProtocol.MAX_BODY_BYTES) {
                    fail(requestId, "请求过大");
                    return;
                }
                File file = writeTemp(requestId, bytes);
                SendMessagesHelper.prepareSendingDocument(
                        AccountInstance.getInstance(currentAccount),
                        file.getAbsolutePath(),
                        LocalBotProtocol.FILENAME_REQ,
                        null,
                        LocalBotProtocol.CAPTION,
                        LocalBotProtocol.MIME,
                        dialogId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        0,
                        null,
                        null,
                        false
                );
            } catch (Exception e) {
                FileLog.e(e);
                fail(requestId, e.getMessage() != null ? e.getMessage() : "发送失败");
            }
        });
    }

    public void cancel(String requestId) {
        AndroidUtilities.runOnUIThread(() -> fail(requestId, "已取消"));
    }

    public void onControlMessage(MessageObject message) {
        if (message == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> handleControlMessage(message));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            if (args == null || args.length < 2 || !(args[1] instanceof ArrayList)) {
                return;
            }
            ArrayList<?> arr = (ArrayList<?>) args[1];
            for (int i = 0, n = arr.size(); i < n; i++) {
                Object item = arr.get(i);
                if (item instanceof MessageObject) {
                    MessageObject msg = (MessageObject) item;
                    if (LocalBotProtocol.isHiddenMessage(msg)) {
                        handleControlMessage(msg);
                    }
                }
            }
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            if (args == null || args.length < 1 || !(args[0] instanceof String)) {
                return;
            }
            String location = (String) args[0];
            MessageObject msg = waitingForFile.remove(location);
            if (msg != null) {
                File file = resolveFile(msg);
                if (file != null) {
                    readAndDispatch(msg, file);
                } else {
                    FileLog.e("LocalBotChannel: fileLoaded but path missing for " + location);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            if (args == null || args.length < 1 || !(args[0] instanceof String)) {
                return;
            }
            String location = (String) args[0];
            MessageObject msg = waitingForFile.remove(location);
            if (msg != null) {
                FileLog.e("LocalBotChannel: failed to download control document " + location);
            }
        }
    }

    private void handleControlMessage(MessageObject message) {
        String key = currentAccount + ":" + message.getDialogId() + ":" + message.getId();
        if (message.getId() > 0 && !processed.add(key)) {
            scheduleDelete(message);
            return;
        }
        scheduleDelete(message);
        if (message.isOutOwner()) {
            return;
        }
        TLRPC.Document doc = message.getDocument();
        if (doc == null) {
            return;
        }
        File file = resolveFile(message);
        if (file != null) {
            readAndDispatch(message, file);
            return;
        }
        String location = FileLoader.getAttachFileName(doc);
        if (!TextUtils.isEmpty(location)) {
            waitingForFile.put(location, message);
        }
        FileLoader.getInstance(currentAccount).loadFile(doc, message, FileLoader.PRIORITY_HIGH, 0);
    }

    private void readAndDispatch(MessageObject message, File file) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                byte[] raw = readLimited(file, LocalBotProtocol.MAX_BODY_BYTES);
                String text = new String(raw, StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(text);
                AndroidUtilities.runOnUIThread(() -> dispatchPayload(message, obj));
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void dispatchPayload(MessageObject message, JSONObject obj) {
        if (!LocalBotProtocol.isValidEnvelope(obj)) {
            FileLog.e("LocalBotChannel: ignored invalid envelope");
            return;
        }
        String type = LocalBotProtocol.payloadType(obj);
        if (LocalBotProtocol.TYPE_OPEN.equals(type)) {
            return;
        }
        if (!LocalBotProtocol.TYPE_RES.equals(type) && !TextUtils.isEmpty(type) && !LocalBotProtocol.TYPE_REQ.equals(type)) {
            // Accept typeless payloads that still carry a status as responses.
            if (!obj.has("status") && !obj.has("data") && !obj.has("error")) {
                return;
            }
        }
        if (LocalBotProtocol.TYPE_REQ.equals(type)) {
            return;
        }
        String id = LocalBotProtocol.payloadId(obj);
        if (TextUtils.isEmpty(id)) {
            return;
        }
        Pending p = pending.remove(id);
        if (p == null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(p.timeout);
        if (p.listener == null) {
            return;
        }
        if (obj.has("error") && !obj.optBoolean("ok", obj.has("data"))) {
            int status = obj.optInt("status", 400);
            JSONObject payload = obj.optJSONObject("data");
            if (payload == null) {
                payload = new JSONObject();
                try {
                    payload.put("ok", false);
                    payload.put("error", obj.optString("error", "请求失败"));
                } catch (Exception ignored) {
                }
            }
            p.listener.onResult(id, status, payload.toString());
            return;
        }
        int status = obj.optInt("status", 200);
        JSONObject data = obj.optJSONObject("data");
        if (data == null) {
            data = obj;
        }
        p.listener.onResult(id, status, data.toString());
    }

    private void fail(String requestId, String message) {
        Pending p = pending.remove(requestId);
        if (p == null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(p.timeout);
        if (p.listener != null) {
            p.listener.onError(requestId, message);
        }
    }

    private void scheduleDelete(MessageObject message) {
        if (message == null || message.getId() <= 0) {
            return;
        }
        final int mid = message.getId();
        final long dialogId = message.getDialogId();
        final long randomId = message.messageOwner != null ? message.messageOwner.random_id : 0;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ArrayList<Integer> ids = new ArrayList<>();
                ids.add(mid);
                ArrayList<Long> randoms = new ArrayList<>();
                if (randomId != 0) {
                    randoms.add(randomId);
                }
                MessagesController.getInstance(currentAccount).deleteMessages(ids, randoms, null, dialogId, 0, false, 0);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }, 800);
    }

    private File resolveFile(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return null;
        }
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            File attach = new File(message.messageOwner.attachPath);
            if (attach.exists() && attach.length() > 0) {
                return attach;
            }
        }
        File path = FileLoader.getInstance(currentAccount).getPathToMessage(message.messageOwner);
        if (path != null && path.exists() && path.length() > 0) {
            return path;
        }
        TLRPC.Document doc = message.getDocument();
        if (doc != null) {
            File attach = FileLoader.getInstance(currentAccount).getPathToAttach(doc, false);
            if (attach != null && attach.exists() && attach.length() > 0) {
                return attach;
            }
        }
        return null;
    }

    private static File writeTemp(String requestId, byte[] bytes) throws Exception {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "owpengram-local");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法写入本地缓存");
        }
        File file = new File(dir, requestId + "-" + LocalBotProtocol.FILENAME_REQ);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.flush();
        }
        return file;
    }

    private static byte[] readLimited(File file, int max) throws Exception {
        if (file.length() > max) {
            throw new Exception("文件过大");
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new Exception("文件过大");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static final class Pending {
        String id;
        long dialogId;
        RequestListener listener;
        Runnable timeout;
    }
}
