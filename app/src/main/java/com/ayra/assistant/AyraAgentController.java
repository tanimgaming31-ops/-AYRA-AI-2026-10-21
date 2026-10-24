package com.ayra.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bridges the local planner and SQLite. All replies are JSON so WebView can show real errors.
 * AI-generated text never executes Android actions; the human must approve a saved plan.
 */
public final class AyraAgentController {
    private final AyraMemoryStore db;

    public AyraAgentController(Context context) {
        db = new AyraMemoryStore(context);
    }

    private static String fail(Exception error) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", error.getMessage() == null ? "Unknown error" : error.getMessage())
                    .toString();
        } catch (Exception ignored) {
            return "{\"ok\":false,\"error\":\"Unexpected storage error\"}";
        }
    }

    public String saveMemory(String value) {
        try {
            long id = db.saveNote(value);
            return new JSONObject().put("ok", true).put("id", id).toString();
        } catch (Exception e) { return fail(e); }
    }

    public String removeMemory(String id) {
        try {
            boolean deleted = db.deleteNote(Long.parseLong(id));
            return new JSONObject().put("ok", deleted)
                    .put("error", deleted ? "" : "Memory not found.").toString();
        } catch (Exception e) { return fail(e); }
    }

    public String listMemories() {
        try { return new JSONObject().put("ok", true)
                .put("notes", db.listNotes()).toString();
        } catch (Exception e) { return fail(e); }
    }

    public String relevantMemories(String query) {
        try {
            JSONArray found = db.relevantNotes(query, 4);
            return new JSONObject().put("ok", true).put("notes", found).toString();
        } catch (Exception e) { return fail(e); }
    }

    public String planTask(String text) {
        try {
            AyraCommandRouter.Plan plan = AyraCommandRouter.plan(text);
            long id = db.createTask(text, plan);
            return new JSONObject().put("ok", true).put("id", id)
                    .put("supported", plan.supported).put("action", plan.action)
                    .put("title", plan.title).put("payload", plan.payload)
                    .put("reason", plan.reason).toString();
        } catch (Exception e) { return fail(e); }
    }

    public String listTasks() {
        try { return new JSONObject().put("ok", true)
                .put("tasks", db.listTasks()).toString();
        } catch (Exception e) { return fail(e); }
    }

    /**
     * The returned action is from persisted validated planner output, not caller-supplied data.
     * This records a REQUEST; it does not assert Android finished the action.
     */
    public String requestDispatch(String rawId) {
        try {
            long id = Long.parseLong(rawId);
            JSONObject task = db.getTask(id);
            if (task == null) throw new IllegalArgumentException("Plan not found.");
            String action = task.optString("action");
            if (!AyraCommandRouter.isAllowedAction(action)
                    || !"planned".equals(task.optString("status"))) {
                throw new IllegalArgumentException("Task is not executable or was already requested.");
            }
            if (!db.markRequested(id)) throw new IllegalStateException("Plan changed; refresh history.");
            return new JSONObject().put("ok", true).put("id", id)
                    .put("action", action).put("payload", task.optString("payload"))
                    .put("status", "requested")
                    .put("message", "Android dispatch requested; outcome not verified.").toString();
        } catch (Exception e) { return fail(e); }
    }
}
