package com.gsic.app;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * v3.74: 原生文件输入输出底座（用户亲定：不走邮箱等外部通道）。
 * 收件箱 files/inbox：用户从 App 聊天附件按钮喂进来的文件（zip/apk/
 * 办公文档/任意类型），Agent 的 read_file/write_file/edit_file
 * 本来就能读写应用域内文件，喂进来后 Agent 知道路径就能处理。
 * 发件箱 files/outbox：Agent 产出的文件放这里，聊天文件列表卡里
 * 打开/分享，打开走系统查看器、分享走系统分享（均在本机，不经邮箱）。
 * zip：Java 自带解压，列目录 + 解压到 inbox/unzipped/<名>/，
 * 防 Zip Slip（解压路径必须在目标目录内，否则跳过该条目）。
 * apk：不静默安装，文件列表卡里点安装只调系统安装器，
 * 由系统再确认一次。
 * 办公文档深度处理（docx/xlsx 文本抽取与改写、pdf）不在本版，
 * 本版先保证喂得进、打得开、分享得出；深度处理排下一版。
 */
public class FileInbox {

    public static class Item {
        public String name = "";
        public String rel = "";
        public long size = 0;
        public boolean dir = false;
    }

    public static File inboxDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "inbox");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File outboxDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "outbox");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "file";
        name = name.replace('\\', '_').replace('/', '_');
        name = name.replace("..", "_");
        return name;
    }

    public static File uniqueFile(File dir, String name) {
        name = sanitize(name);
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + "-" + i + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    /** 从系统文件选择器 Uri 流式拷进收件箱（不整文件读进内存）。 */
    public static File importUri(Context ctx, Uri uri, String displayName) throws Exception {
        File dir = inboxDir(ctx);
        String name = displayName;
        if (name == null || name.isEmpty()) {
            String p = uri.getLastPathSegment();
            name = p != null ? p : "file";
        }
        File out = uniqueFile(dir, name);
        InputStream in = null;
        OutputStream os = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("打不开所选文件");
            os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    public static List<Item> listDir(File dir, String prefix) {
        List<Item> out = new ArrayList<>();
        if (dir == null || !dir.exists()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            Item it = new Item();
            it.name = f.getName();
            it.rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            it.size = f.isDirectory() ? 0 : f.length();
            it.dir = f.isDirectory();
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static String zipList(File zip) throws Exception {
        StringBuilder sb = new StringBuilder();
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            int count = 0;
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                sb.append(en.getName());
                if (!en.isDirectory()) sb.append(" (").append(en.getSize()).append(" 字节)");
                sb.append("\n");
                count++;
                if (count >= 200) { sb.append("…（仅列前 200 条）\n"); break; }
            }
            if (count == 0) sb.append("（空 zip）\n");
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /** 解压到 inbox/unzipped/<zip名>/，返回解压出的文件数；Zip Slip 条目跳过。 */
    public static int unzipTo(Context ctx, File zip) throws Exception {
        String base = zip.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File dest = new File(new File(inboxDir(ctx), "unzipped"), sanitize(base));
        dest.mkdirs();
        String destCanon = dest.getCanonicalPath();
        int count = 0;
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new java.io.FileInputStream(zip));
            ZipEntry en;
            byte[] buf = new byte[65536];
            while ((en = zin.getNextEntry()) != null) {
                File f = new File(dest, en.getName());
                String canon = f.getCanonicalPath();
                if (!canon.equals(destCanon) && !canon.startsWith(destCanon + "/")) {
                    continue; // Zip Slip：跳过
                }
                if (en.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    OutputStream os = null;
                    try {
                        os = new FileOutputStream(f);
                        int n;
                        while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                        count++;
                    } finally {
                        if (os != null) try { os.close(); } catch (Exception ignored) {}
                    }
                }
                zin.closeEntry();
            }
        } finally {
            if (zin != null) try { zin.close(); } catch (Exception ignored) {}
        }
        return count;
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")) return "text/plain";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "*/*";
    }
}
