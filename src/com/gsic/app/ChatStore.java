package com.gsic.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 对话记录持久化：当前会话实时保存，历史会话可回看。
 * 存 files/chat_history.json：{sessions:[{id,t,goal,msgs:[{role,text,time}]}]}}
 * role: user / agent / system
 */
public class ChatStore {

    public static class Msg {
        public String role, text, time;
    }

    public static class Session {
        public long id, t;
        public String goal;
        public List<Msg> msgs = new ArrayList<>();
    }

    private final File file;
    private final List<Session> sessions = new ArrayList<>();
    private Session current;

    public ChatStore(Context ctx) {
        file = new File(ctx.getFilesDir(), "chat_history.json");
        load();
        if (current == null) newSession();
    }

    public synchronized void newSession() {
        current = new Session();
        current.id = System.currentTimeMillis();
        current.t = current.id;
        current.goal = "";
        sessions.add(0, current);
        trim();
        save();
    }

    public synchronized void setGoal(String goal) {
        if (current != null && current.goal.isEmpty()) {
            current.goal = goal;
            save();
        }
    }

    public synchronized void append(String role, String text, String time) {
        if (current == null) newSession();
        Msg m = new Msg();
        m.role = role;
        m.text = text;
        m.time = time;
        current.msgs.add(m);
        // 只保留最近 200 条，防止文件膨胀
        if (current.msgs.size() > 200) {
            current.msgs = new ArrayList<>(current.msgs.subList(
                    current.msgs.size() - 200, current.msgs.size()));
        }
        save();
    }

    public synchronized List<Msg> currentMsgs() {
        return new ArrayList<>(current.msgs);
    }

    public synchronized List<Session> listSessions() {
        return new ArrayList<>(sessions);
    }

    public synchronized Session getSession(long id) {
        for (Session s : sessions) if (s.id == id) return s;
        return null;
    }

    /** 删除一条历史记录 */
    public synchronized boolean deleteSession(long id) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id == id) {
                sessions.remove(i);
                if (current != null && current.id == id) {
                    current = sessions.isEmpty() ? null : sessions.get(0);
                    if (current == null) newSession();
                }
                save();
                return true;
            }
        }
        return false;
    }

    private void trim() {
        while (sessions.size() > 30) sessions.remove(sessions.size() - 1);
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Session s : sessions) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("t", s.t);
                o.put("goal", s.goal);
                JSONArray ma = new JSONArray();
                for (Msg m : s.msgs) {
                    JSONObject mo = new JSONObject();
                    mo.put("role", m.role);
                    mo.put("text", m.text);
                    mo.put("time", m.time);
                    ma.put(mo);
                }
                o.put("msgs", ma);
                arr.put(o);
            }
            root.put("sessions", arr);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            if (!file.exists()) return;
            String txt = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(txt).optJSONArray("sessions");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Session s = new Session();
                s.id = o.optLong("id");
                s.t = o.optLong("t");
                s.goal = o.optString("goal", "");
                JSONArray ma = o.optJSONArray("msgs");
                if (ma != null) {
                    for (int j = 0; j < ma.length(); j++) {
                        JSONObject mo = ma.getJSONObject(j);
                        Msg m = new Msg();
                        m.role = mo.optString("role");
                        m.text = mo.optString("text");
                        m.time = mo.optString("time");
                        s.msgs.add(m);
                    }
                }
                sessions.add(s);
            }
            if (!sessions.isEmpty()) current = sessions.get(0);
        } catch (Exception ignored) {
        }
    }

    public static String sessionTitle(Session s) {
        String g = s.goal.isEmpty() ? "(空对话)" : s.goal;
        if (g.length() > 18) g = g.substring(0, 18) + "…";
        String t = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(s.t));
        return g + "\n" + t + " · " + s.msgs.size() + " 条";
    }
}
