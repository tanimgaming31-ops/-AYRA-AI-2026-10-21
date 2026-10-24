package com.ayra.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent v1 SQLite store. Does not touch or migrate legacy WebView localStorage.
 * Database stays in private app storage across ordinary app updates.
 */
public final class AyraMemoryStore extends SQLiteOpenHelper {
    private static final String DATABASE = "ayra_agent_v1.db";
    private static final int VERSION = 1;
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{M}\\p{N}]{2,}");
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "that", "this", "with", "from", "what", "when", "where",
            "please", "about", "tell", "remember", "should", "could",
            "আমার", "তুমি", "একটা", "বলবে", "কখন", "কোথায়", "এটা"));

    public AyraMemoryStore(Context context) {
        super(context.getApplicationContext(), DATABASE, null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE agent_notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "body TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE agent_tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "instruction TEXT NOT NULL, action TEXT NOT NULL, payload TEXT NOT NULL," +
                "title TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL," +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_agent_tasks_created ON agent_tasks(created_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Add non-destructive migrations here for future schema versions.
        // Never drop tables containing user memories or task history.
    }

    public synchronized long saveNote(String raw) {
        String body = raw == null ? "" : raw.trim();
        if (body.isEmpty() || body.length() > 1000)
            throw new IllegalArgumentException("Memory must contain 1–1000 characters.");
        ContentValues v = new ContentValues();
        v.put("body", body);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("agent_notes", null, v);
    }

    public synchronized boolean deleteNote(long id) {
        if (id <= 0) return false;
        return getWritableDatabase().delete("agent_notes", "id=?", new String[]{Long.toString(id)}) > 0;
    }

    private JSONObject noteFrom(Cursor c) throws JSONException {
        return new JSONObject()
                .put("id", c.getLong(0))
                .put("body", c.getString(1))
                .put("createdAt", c.getLong(2));
    }

    public synchronized JSONArray listNotes() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().query("agent_notes",
                new String[]{"id", "body", "created_at"}, null, null, null, null,
                "id DESC", "200")) {
            while (c.moveToNext()) out.put(noteFrom(c));
        }
        return out;
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<>();
        Matcher m = WORDS.matcher(value.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String term = m.group();
            if (!STOP.contains(term) && term.length() > 1) out.add(term);
        }
        return out;
    }

    private static final class Scored {
        final JSONObject data;
        final double score;
        Scored(JSONObject data, double score) { this.data = data; this.score = score; }
    }

    /**
     * Keyword relevance, not vector/semantic search. Never calls a remote endpoint.
     * Fetch is bounded to avoid blocking the WebView bridge on large histories.
     */
    public synchronized JSONArray relevantNotes(String question, int maxResults) throws JSONException {
        JSONArray out = new JSONArray();
        if (question == null || question.trim().isEmpty()) return out;
        Set<String> query = tokens(question);
        if (query.isEmpty()) return out;
        List<Scored> scored = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("agent_notes",
                new String[]{"id", "body", "created_at"}, null, null, null, null,
                "id DESC", "300")) {
            while (c.moveToNext()) {
                String body = c.getString(1);
                Set<String> noteTerms = tokens(body);
                double score = 0;
                for (String term : query) {
                    if (noteTerms.contains(term)) score += 2;
                    else if (body.toLowerCase(Locale.ROOT).contains(term)) score += 0.5;
                }
                if (score > 0) scored.add(new Scored(noteFrom(c), score));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        int cap = Math.max(0, Math.min(5, maxResults));
        for (int i = 0; i < Math.min(cap, scored.size()); i++) out.put(scored.get(i).data);
        return out;
    }

    public synchronized long createTask(String instruction, AyraCommandRouter.Plan plan) {
        String raw = instruction == null ? "" : instruction.trim();
        if (raw.length() > 240) raw = raw.substring(0, 240);
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("instruction", raw);
        v.put("action", plan.action);
        v.put("payload", plan.payload);
        v.put("title", plan.title);
        v.put("status", plan.supported ? "planned" : "unsupported");
        v.put("detail", plan.reason);
        v.put("created_at", now);
        v.put("updated_at", now);
        return getWritableDatabase().insertOrThrow("agent_tasks", null, v);
    }

    private JSONObject taskFrom(Cursor c) throws JSONException {
        return new JSONObject().put("id", c.getLong(0))
                .put("instruction", c.getString(1))
                .put("action", c.getString(2))
                .put("payload", c.getString(3))
                .put("title", c.getString(4))
                .put("status", c.getString(5))
                .put("detail", c.getString(6))
                .put("createdAt", c.getLong(7))
                .put("updatedAt", c.getLong(8));
    }

    private static final String[] TASK_COLS = {"id", "instruction", "action", "payload",
            "title", "status", "detail", "created_at", "updated_at"};

    public synchronized JSONObject getTask(long id) throws JSONException {
        if (id <= 0) return null;
        try (Cursor c = getReadableDatabase().query("agent_tasks", TASK_COLS,
                "id=?", new String[]{Long.toString(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? taskFrom(c) : null;
        }
    }

    public synchronized JSONArray listTasks() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().query("agent_tasks", TASK_COLS,
                null, null, null, null, "id DESC", "40")) {
            while (c.moveToNext()) out.put(taskFrom(c));
        }
        return out;
    }

    /**
     * Record only dispatch, NOT success. A separate Android permission dialog or activity
     * may fail or be canceled. Never mark an action completed without a verified result.
     */
    public synchronized boolean markRequested(long id) {
        ContentValues v = new ContentValues();
        v.put("status", "requested");
        v.put("detail", "Android dispatch requested; final outcome unverified.");
        v.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update("agent_tasks", v,
                "id=? AND status='planned'", new String[]{Long.toString(id)}) == 1;
    }
}
