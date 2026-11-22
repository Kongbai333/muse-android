package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class MobileStudio {
    private static final int HISTORY_LIMIT = 60;
    private static final String STARTING = "正在连接账号…";
    private final Context context;
    private final MuseWeb browser;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<StudioSession> sessions = new ArrayList<>();
    private final AtomicFile history;
    private final AtomicFile sessionFile;
    private final File mediaRoot;
    private Job job;
    private String script;
    private String selectedSessionId;

    static final class Entry {
        final String id, sessionId, owner, name, prompt, ratio;
        final int seconds;
        final boolean video;
        final long created;
        String status, text = "", extension = "";

        Entry(String id, String sessionId, String owner, String name, String prompt, String ratio, int seconds, boolean video, long created, String status) {
            this.id = id; this.sessionId = sessionId; this.owner = owner; this.name = name; this.prompt = prompt; this.ratio = ratio;
            this.seconds = seconds; this.video = video; this.created = created; this.status = status;
        }
    }

    private static final class Job {
        final Entry entry;
        final long started = SystemClock.elapsedRealtime();
        MuseWeb.WorkPage page;
        StudioMediaTransfer transfer;
        String extension;
        int width, height;
        long called;
        boolean watching, awaiting, stopping;
        // 参考图按顺序逐张上传；全部放进输入区后再确认附件数量。
        final java.util.ArrayList<ReferenceUpload> references = new java.util.ArrayList<>();
        int referenceIndex;
        boolean referencesConfirmed, locating;
        long locatingSince, confirmingSince;

        Job(Entry entry) { this.entry = entry; }

        ReferenceUpload reference() { return referenceIndex < references.size() ? references.get(referenceIndex) : null; }
        boolean confirmingReferences() {
            return !references.isEmpty() && referenceIndex >= references.size() && !referencesConfirmed;
        }
        boolean preparingReferences() { return reference() != null || confirmingReferences(); }
    }

    MobileStudio(Context context, MuseWeb browser, Runnable changed) {
        this.context = context;
        this.browser = browser;
        this.changed = changed;
        history = new AtomicFile(new File(context.getFilesDir(), "studio-history.json"));
        sessionFile = new AtomicFile(new File(context.getFilesDir(), "studio-sessions.json"));
        mediaRoot = new File(context.getFilesDir(), "studio-media");
        restoreSessions();
        restore();
    }

    List<Entry> entries() { return Collections.unmodifiableList(entries); }
    List<Entry> currentEntries() {
        StudioSession session = currentSession();
        if (session == null) return Collections.emptyList();
        ArrayList<Entry> selected = new ArrayList<>();
        for (Entry entry : entries) if (session.id.equals(entry.sessionId)) selected.add(entry);
        return selected;
    }
    List<StudioSession> sessions() {
        ArrayList<StudioSession> ordered = new ArrayList<>(sessions);
        ordered.sort((a, b) -> Long.compare(b.updated, a.updated));
        return ordered;
    }
    String currentSessionId() { return selectedSessionId; }
    StudioSession currentSession() {
        if (selectedSessionId == null) return null;
        for (StudioSession session : sessions) if (selectedSessionId.equals(session.id)) return session;
        return null;
    }
    StudioSession session(String id) { return findSession(id); }
    StudioSession ensureSession(AccountStore.Account account) {
        if (account == null) return null;
        StudioSession selected = currentSession();
        if (selected != null && selected.owner.equals(account.id)) return selected;
        StudioSession latest = null;
        for (StudioSession session : sessions) {
            if (!session.owner.equals(account.id)) continue;
            if (latest == null || session.updated > latest.updated) latest = session;
        }
        if (latest == null) {
            latest = StudioSession.defaultSession(account.id, account.name, System.currentTimeMillis());
            sessions.add(latest);
        }
        selectedSessionId = latest.id;
        saveSessions();
        return latest;
    }
    StudioSession createSession(AccountStore.Account account) {
        if (account == null) return null;
        StudioSession session = StudioSession.create(account.id, account.name, System.currentTimeMillis());
        sessions.add(session);
        selectedSessionId = session.id;
        saveSessions();
        return session;
    }
    boolean selectSession(String id) {
        for (StudioSession session : sessions) {
            if (session.id.equals(id)) {
                selectedSessionId = id;
                saveSessions();
                return true;
            }
        }
        return false;
    }
    void updateDraft(StudioSession session, String draft, boolean video, String ratio, int seconds) {
        if (session == null) return;
        session.draft = draft == null ? "" : draft;
        session.video = video;
        session.ratio = ratio == null ? "Auto" : ratio;
        session.seconds = seconds;
        session.touch(System.currentTimeMillis());
        saveSessions();
    }
    void updateComposerHeight(StudioSession session, int heightDp) {
        if (session == null) return;
        session.setComposerHeight(heightDp, System.currentTimeMillis());
        saveSessions();
    }
    void flushSessions() { saveSessions(); }

    int entryCount(String sessionId) {
        int count = 0;
        for (Entry entry : entries) if (entry.sessionId.equals(sessionId)) count++;
        return count;
    }

    /**
     * 删除会话及其生成记录、App 内保存的图片/视频和待发送参考图（不影响 Muse 网站上的对话）。
     * 正在生成的会话会被跳过。删掉当前会话时，优先改选同一账号最近的会话；没有则不选（由调用方新建）。
     * 返回实际删除的数量。
     */
    int deleteSessions(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String busy = job == null ? null : job.entry.sessionId;
        StudioSession selected = currentSession();
        String selectedOwner = selected == null ? null : selected.owner;
        java.util.HashSet<String> doomed = new java.util.HashSet<>();
        for (StudioSession session : sessions) if (ids.contains(session.id) && !session.id.equals(busy)) doomed.add(session.id);
        if (doomed.isEmpty()) return 0;
        sessions.removeIf(session -> doomed.contains(session.id));
        java.util.Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!doomed.contains(entry.sessionId)) continue;
            File saved = media(entry);
            if (saved != null && !saved.delete()) saved.deleteOnExit();
            iterator.remove();
        }
        File referenceDirectory = StudioReferenceStore.directory(context);
        for (String id : doomed) {
            for (int index = 0; index < StudioReferenceStore.MAX_REFERENCES; index++) {
                File reference = StudioReferenceStore.fileFor(referenceDirectory, id, index);
                if (reference != null && reference.exists() && !reference.delete()) reference.deleteOnExit();
            }
        }
        if (selectedSessionId != null && doomed.contains(selectedSessionId)) {
            StudioSession next = null;
            for (StudioSession session : sessions) {
                if (!session.owner.equals(selectedOwner)) continue;
                if (next == null || session.updated > next.updated) next = session;
            }
            selectedSessionId = next == null ? null : next.id;
        }
        save();
        saveSessions();
        changed.run();
        return doomed.size();
    }
    Entry running() { return job == null ? null : job.entry; }
    boolean accountInUse(String id) { return job != null && job.entry.owner.equals(id); }

    void start(AccountStore.Account account, boolean profiles, String prompt, boolean video, String ratio, int seconds,
            StudioSession session) {
        if (job != null || prompt.trim().isEmpty() || session == null || !session.belongsTo(account.id)) return;
        String text = prompt.trim();
        long now = System.currentTimeMillis();
        session.adoptPrompt(text);
        session.touch(now);
        saveSessions();
        Entry entry = new Entry(UUID.randomUUID().toString(), session.id, account.id, account.name, text, ratio, seconds,
            video, System.currentTimeMillis(), STARTING);
        Job next = new Job(entry);
        String referenceFailure = null;
        File referenceDirectory = StudioReferenceStore.directory(context);
        for (int index = 0; index < session.referenceCount; index++) {
            File source = StudioReferenceStore.fileFor(referenceDirectory, session.id, index);
            try {
                next.references.add(new ReferenceUpload(source, "reference-" + (index + 1) + ".jpg", "image/jpeg"));
            } catch (IOException error) {
                referenceFailure = "第 " + (index + 1) + " 张参考图不可读取，请重新选择。";
                break;
            }
        }
        job = next;
        entries.add(0, entry);
        if (entries.size() > HISTORY_LIMIT) entries.remove(entries.size() - 1);
        save();
        changed.run();
        if (referenceFailure != null) {
            finish(next, referenceFailure);
            return;
        }
        try {
            if (script == null) script = MuseAdapters.source(context, "muse-studio.js");
            String startUrl = StudioDraft.trustedUrl(session.url) ? session.url : StudioDraft.NEW_THREAD;
            next.page = browser.openWorker(account, profiles, startUrl, script, reason -> finish(next, reason));
            main.postDelayed(() -> poll(next), 400);
        } catch (IOException error) {
            finish(next, "无法读取工作室组件，请重新安装 Muse。");
        }
    }

    private void poll(Job current) {
        if (job != current || current.stopping) return;
        long time = SystemClock.elapsedRealtime();
        long elapsed = time - current.started;
        if (elapsed > 15 * 60_000L || (current.awaiting && time - current.called > 8000)) {
            finish(current, "等待 Muse 响应超时，请重试。");
            return;
        }
        if (current.page.needsLogin() || (!current.watching && elapsed > 45_000)) {
            finish(current, "未找到创作输入框，请先在「网页」登录这个账号。");
            return;
        }
        if (current.page.ready() && !current.awaiting) {
            Entry entry = current.entry;
            String id = JSONObject.quote(entry.id);
            String api = "window.__museStudio?.";
            String expression;
            ReferenceUpload upload = current.reference();
            if (upload != null) {
                try {
                    if (upload.complete()) {
                        expression = api + "commitUpload(" + id + "," + JSONObject.quote(upload.name)
                            + "," + JSONObject.quote(upload.mime) + "," + (current.referenceIndex == 0) + ")";
                    } else {
                        byte[] chunk = upload.chunk();
                        expression = api + "uploadChunk(" + id + "," + upload.offset() + ","
                            + JSONObject.quote(Base64.getEncoder().encodeToString(chunk)) + "," + upload.eof() + ")";
                    }
                } catch (IOException error) {
                    finish(current, "第 " + (current.referenceIndex + 1) + " 张参考图读取失败，请重新选择图片。");
                    return;
                }
            } else if (current.confirmingReferences())
                expression = api + "attachedCount(" + id + "," + current.references.size() + ")";
            else if (current.transfer != null) expression = api + "read(" + id + "," + current.transfer.offset() + ")";
            else if (current.watching) expression = api + "poll(" + id + ")";
            else {
                String prompt = StudioDraft.format(entry.video, entry.prompt, entry.ratio, entry.seconds);
                expression = api + "begin(" + id + "," + (entry.video ? "'video'" : "'image'") + "," + JSONObject.quote(prompt) + ")";
            }
            current.awaiting = true;
            current.called = time;
            current.page.evaluate(expression, raw -> {
                if (job != current || current.stopping) return;
                current.awaiting = false;
                receive(current, raw);
            });
        }
        if (job == current) main.postDelayed(() -> poll(current),
            current.locating || current.confirmingReferences() ? 400
                : current.reference() != null ? 25 : current.transfer == null ? 440 : 25);
    }

    private void receive(Job current, String raw) {
        JSONObject reply = MuseAdapters.reply(raw);
        if (reply == null) return;
        String state = reply.optString("state");
        String id = reply.optString("id", "");
        if (!current.entry.id.equals(id) && !(id.isEmpty() && (state.equals("waiting") || state.equals("error")))) {
            finish(current, "任务会话已更改，请重新生成。");
            return;
        }
        Entry entry = current.entry;
        String previousText = entry.text, previousStatus = entry.status;
        String text = limit(reply.optString("text"), 24_000);
        if (!text.isEmpty()) entry.text = text;
        try {
            switch (state) {
                case "waiting" -> entry.status = "等待 Muse 创作页就绪…";
                case "uploading" -> {
                    ReferenceUpload upload = current.reference();
                    if (upload == null) throw new IOException("参考图状态无效");
                    upload.ack(reply.optInt("offset", -1));
                    entry.status = "正在上传参考图" + referenceLabel(current) + " · "
                        + (100L * upload.offset() / upload.length()) + "%";
                }
                case "uploaded" -> {
                    ReferenceUpload upload = current.reference();
                    if (upload == null) throw new IOException("参考图状态无效");
                    upload.ack(reply.optInt("offset", -1));
                    entry.status = "正在添加参考图" + referenceLabel(current) + "…";
                }
                case "locating" -> {
                    // 上传控件还没出现（页面仍在加载）或刚清掉旧附件：稍后重试，20 秒后放弃。
                    long now = SystemClock.elapsedRealtime();
                    if (!current.locating) { current.locating = true; current.locatingSince = now; }
                    if (now - current.locatingSince > 20_000) {
                        finish(current, limit(reply.optString("error", "Muse 页面没有可用的图片上传控件，请刷新页面后重试"), 240));
                        return;
                    }
                    entry.status = "正在等待 Muse 上传控件…";
                }
                case "attached" -> {
                    ReferenceUpload upload = current.reference();
                    if (upload == null || !upload.complete()) throw new IOException("参考图状态无效");
                    current.locating = false;
                    current.referenceIndex++;
                    if (current.referenceIndex >= current.references.size()) {
                        current.confirmingSince = SystemClock.elapsedRealtime();
                        entry.status = "正在确认参考图…";
                    } else entry.status = "正在上传参考图" + referenceLabel(current) + "…";
                }
                case "attachments" -> {
                    int expected = current.references.size();
                    int count = reply.optInt("count", 0);
                    long waited = SystemClock.elapsedRealtime() - current.confirmingSince;
                    // 和 MUSE2API 一样：必须在输入区数到全部参考图才发送，否则停止，绝不丢图生成。
                    if (count >= expected) {
                        current.referencesConfirmed = true;
                        releaseReferences(current);
                        entry.status = "正在准备生成…";
                    } else if (waited > 30_000) {
                        String diag = limit(reply.optString("diag"), 420);
                        finish(current, "参考图没有进入 Muse 输入框（" + count + " / " + expected + "），已停止生成，参考图仍保留。"
                            + (diag.isEmpty() ? "" : "\n诊断：" + diag));
                        return;
                    } else entry.status = "正在确认参考图 · " + count + " / " + expected
                        + ("input".equals(reply.optString("method", "input")) ? "" : "（改用" +
                            ("drop".equals(reply.optString("method")) ? "拖放" : "粘贴") + "方式）");
                }
                case "preparing", "sending" -> { current.watching = true; entry.status = "正在发送…"; }
                case "running" -> entry.status = "正在生成…";
                case "downloading" -> entry.status = "正在取回生成文件…";
                case "media" -> prepareMedia(current, reply);
                case "chunk" -> acceptChunk(current, reply);
                case "cancelled" -> finish(current, "已停止");
                case "error" -> finish(current, limit(reply.optString("error", "Muse 没有返回结果，请重试。"), 240));
                default -> finish(current, "Muse 没有返回可用的图片或视频。");
            }
        } catch (IOException | IllegalArgumentException error) {
            finish(current, "未能保存生成文件，请重试。");
        }
        if (job == current && (!entry.text.equals(previousText) || !entry.status.equals(previousStatus))) changed.run();
    }

    private static String referenceLabel(Job current) {
        int total = current.references.size();
        return total > 1 ? " " + Math.min(total, current.referenceIndex + 1) + " / " + total : "";
    }

    /** 参考图已进入 Muse 输入区：清空会话里的待发送参考图。 */
    private void releaseReferences(Job current) {
        StudioSession session = findSession(current.entry.sessionId);
        if (session == null) return;
        session.setReferenceCount(0, System.currentTimeMillis());
        saveSessions();
        for (ReferenceUpload upload : current.references) {
            if (!upload.file.delete()) upload.file.deleteOnExit();
        }
    }

    private void prepareMedia(Job current, JSONObject reply) throws IOException {
        if (current.transfer != null) throw new IOException("媒体响应重复");
        boolean video = current.entry.video;
        String mime = reply.optString("mime");
        int bytes = reply.optInt("bytes");
        current.width = reply.optInt("width");
        current.height = reply.optInt("height");
        current.extension = switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "";
        };
        if (current.extension.isEmpty() || (video ? !mime.startsWith("video/") : !mime.startsWith("image/"))) {
            finish(current, "Muse 返回的文件与所选类型不符。");
            return;
        }
        if (!video && (!reply.optBoolean("original") || current.width <= 0 || current.height <= 0)) {
            finish(current, "尚未取得原图，没有保存缩略图。");
            return;
        }
        if ((!mediaRoot.isDirectory() && !mediaRoot.mkdirs()) || (!video && bytes > 32 * 1024 * 1024)) {
            throw new IOException("无法保存媒体");
        }
        current.transfer = new StudioMediaTransfer(new File(mediaRoot, current.entry.id + "." + current.extension), bytes);
        current.entry.status = "正在保存 · 0%";
    }

    private void acceptChunk(Job current, JSONObject reply) throws IOException {
        StudioMediaTransfer transfer = current.transfer;
        if (transfer == null) throw new IOException("没有待保存的文件");
        boolean last = reply.optBoolean("eof");
        byte[] bytes = Base64.getDecoder().decode(reply.optString("data"));
        transfer.write(reply.optInt("offset", -1), bytes, last);
        if (!last) {
            current.entry.status = "正在保存 · " + (100L * transfer.offset() / transfer.total()) + "%";
            return;
        }
        if (!current.entry.video) {
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(transfer.partial().getPath(), size);
            if (size.outWidth != current.width || size.outHeight != current.height) {
                finish(current, "原图校验失败，请重新生成。");
                return;
            }
        }
        transfer.commit();
        current.entry.extension = current.extension;
        finish(current, "已完成 · 已保存到本机");
    }

    void stop() {
        Job current = job;
        if (current == null || current.stopping) return;
        current.stopping = true;
        if (current.page != null && current.page.ready()) {
            current.page.evaluate("window.__museStudio?.cancel(" + JSONObject.quote(current.entry.id) + ")",
                raw -> finish(current, "已停止"));
            main.postDelayed(() -> finish(current, "已停止"), 1300);
        } else finish(current, "已停止");
    }

    void close() {
        if (job != null) finish(job, "上次任务已中断");
        main.removeCallbacksAndMessages(null);
    }

    private void finish(Job current, String status) {
        if (job != current) return;
        job = null;
        if (current.page != null) {
            String url = current.page.url();
            StudioSession session = findSession(current.entry.sessionId);
            if (session != null && StudioDraft.trustedUrl(url) && !StudioDraft.loginPage(url)) {
                session.url = url;
                session.touch(System.currentTimeMillis());
                saveSessions();
            }
        }
        if (current.page != null) current.page.close();
        if (current.transfer != null) current.transfer.close();
        current.entry.status = status.isEmpty() ? "未取得结果，请重试。" : status;
        save();
        changed.run();
    }

    File media(Entry entry) {
        if (!entry.extension.matches("png|jpg|webp|gif|mp4|webm|mov")) return null;
        File saved = new File(mediaRoot, entry.id + "." + entry.extension);
        return saved.isFile() ? saved : null;
    }

    private void restore() {
        boolean sessionChanged = false;
        try {
            if (history.getBaseFile().length() > 8_000_000) return;
            JSONArray saved = new JSONArray(new String(history.readFully(), StandardCharsets.UTF_8));
            for (int index = 0; index < Math.min(saved.length(), HISTORY_LIMIT); index++) {
                JSONObject row = saved.optJSONObject(index);
                if (row == null) continue;
                try {
                    String id = UUID.fromString(row.getString("id")).toString();
                    String owner = UUID.fromString(row.getString("owner")).toString();
                    String sessionId = row.optString("session", StudioSession.defaultId(owner));
                    if (findSession(sessionId) == null) {
                        sessions.add(StudioSession.defaultSession(owner, row.getString("name"), row.optLong("created")));
                        StudioSession legacy = sessions.get(sessions.size() - 1);
                        sessionId = legacy.id;
                        sessionChanged = true;
                    }
                    Entry entry = new Entry(id, sessionId, owner, row.getString("name"),
                        row.getString("prompt"), row.optString("ratio", "Auto"), row.optInt("seconds", 5), row.optBoolean("video"),
                        row.optLong("created"), row.optBoolean("running") ? "上次任务已中断" : row.optString("status"));
                    entry.text = limit(row.optString("text"), 24_000);
                    entry.extension = row.optString("extension");
                    entries.add(entry);
                } catch (JSONException | IllegalArgumentException ignored) { }
            }
        } catch (IOException | JSONException ignored) { }
        if (selectedSessionId == null && !sessions.isEmpty()) selectedSessionId = sessions.get(0).id;
        if (sessionChanged || selectedSessionId != null) saveSessions();
    }

    private void restoreSessions() {
        try {
            if (sessionFile.getBaseFile().length() > 1_000_000) return;
            JSONObject saved = new JSONObject(new String(sessionFile.readFully(), StandardCharsets.UTF_8));
            JSONArray items = saved.optJSONArray("items");
            if (items == null) return;
            String selected = saved.optString("selected", "");
            selectedSessionId = selected.isEmpty() ? null : selected;
            for (int index = 0; index < items.length(); index++) {
                JSONObject row = items.optJSONObject(index);
                if (row == null) continue;
                try {
                    String id = row.getString("id").trim();
                    String owner = UUID.fromString(row.getString("owner")).toString();
                    String ownerName = row.optString("ownerName", "Muse 账号");
                    StudioSession session = StudioSession.restore(id, owner, ownerName, row.optString("name"),
                        row.optString("url", StudioDraft.NEW_THREAD), row.optLong("created", System.currentTimeMillis()));
                    session.draft = limit(row.optString("draft"), 6000);
                    session.video = row.optBoolean("video");
                    session.ratio = row.optString("ratio", "Auto");
                    session.seconds = row.optInt("seconds", 5);
                    session.hasReference = row.optBoolean("hasReference");
                    session.referenceCount = Math.max(0, Math.min(StudioReferenceStore.MAX_REFERENCES,
                        row.optInt("referenceCount", session.hasReference ? 1 : 0)));
                    session.hasReference = session.referenceCount > 0;
                    session.composerHeightDp = ComposerHeight.clamp(row.optInt("composerHeightDp", ComposerHeight.DEFAULT_DP), 720);
                    session.updated = row.optLong("updated", session.created);
                    sessions.add(session);
                } catch (JSONException | IllegalArgumentException ignored) { }
            }
        } catch (IOException | JSONException ignored) { }
    }

    private StudioSession findSession(String id) {
        if (id == null || id.isEmpty()) return null;
        for (StudioSession session : sessions) if (id.equals(session.id)) return session;
        return null;
    }

    private void saveSessions() {
        FileOutputStream output = null;
        try {
            JSONArray items = new JSONArray();
            for (StudioSession session : sessions) items.put(new JSONObject()
                .put("id", session.id).put("owner", session.owner).put("ownerName", session.ownerName)
                .put("name", session.name).put("url", session.url).put("draft", session.draft)
                .put("video", session.video).put("ratio", session.ratio).put("seconds", session.seconds)
                .put("hasReference", session.hasReference).put("referenceCount", session.referenceCount)
                .put("composerHeightDp", session.composerHeightDp)
                .put("created", session.created).put("updated", session.updated));
            JSONObject saved = new JSONObject().put("selected", selectedSessionId == null ? "" : selectedSessionId)
                .put("items", items);
            output = sessionFile.startWrite();
            output.write(saved.toString().getBytes(StandardCharsets.UTF_8));
            sessionFile.finishWrite(output);
        } catch (IOException | JSONException error) {
            sessionFile.failWrite(output);
        }
    }

    private void save() {
        FileOutputStream output = null;
        try {
            JSONArray saved = new JSONArray();
            for (Entry entry : entries) saved.put(new JSONObject().put("id", entry.id).put("session", entry.sessionId)
                .put("owner", entry.owner).put("name", entry.name)
                .put("prompt", entry.prompt).put("ratio", entry.ratio).put("seconds", entry.seconds).put("video", entry.video)
                .put("created", entry.created).put("running", entry == running()).put("status", entry.status).put("text", entry.text)
                .put("extension", entry.extension));
            output = history.startWrite();
            output.write(saved.toString().getBytes(StandardCharsets.UTF_8));
            history.finishWrite(output);
        } catch (IOException | JSONException error) {
            history.failWrite(output);
        }
    }

    private static String limit(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }
}
