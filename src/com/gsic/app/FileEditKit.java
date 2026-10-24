package com.gsic.app;

/**
 * v3.57 本地编程能力：文件编辑纯函数工具集。
 * 纯 Java、无 Android 依赖，可在 JVM 上单测；TaskRunner 只做委托。
 */
public final class FileEditKit {
    private FileEditKit() {}

    /**
     * 路径是否在应用自家域内。输入须为已 canonical 化路径；
     * 语义：等于 dataDir 或在其之下；等于 extDir 或在其之下。
     * 前缀攻击（如 dataDir=/data/data/com.a，path=/data/data/com.a.evil/x）被 "/" 边界挡住。
     */
    public static boolean isInAppDomain(String canonPath, String dataDirCanon, String extDirCanon) {
        if (canonPath == null || canonPath.isEmpty()) return false;
        if (dataDirCanon != null && !dataDirCanon.isEmpty()) {
            if (canonPath.equals(dataDirCanon) || canonPath.startsWith(dataDirCanon + "/")) return true;
        }
        if (extDirCanon != null && !extDirCanon.isEmpty()) {
            if (canonPath.equals(extDirCanon) || canonPath.startsWith(extDirCanon + "/")) return true;
        }
        return false;
    }

    /** shell 单引号转义：'a'b' -> 'a'"'"'b'。 */
    public static String shQuote(String s) {
        return s == null ? "" : s.replace("'", "'\"'\"'");
    }

    /** 纯函数：找 text 中 old 的第 occ 次出现位置；找不到返回 -1。 */
    public static int findNth(String text, String old, int occ) {
        if (text == null || old == null || old.isEmpty() || occ < 1) return -1;
        int idx = -1, from = 0;
        for (int i = 0; i < occ; i++) {
            idx = text.indexOf(old, from);
            if (idx < 0) return -1;
            from = idx + old.length();
        }
        return idx;
    }

    public static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * edit_file 替换核心：把 text 中 old 的第 occ 次出现换成 newS。
     * 找到返回替换后全文；找不到返回 null（调用方转候选行提示）。
     */
    public static String applyEdit(String text, String old, String newS, int occ) {
        int idx = findNth(text, old, occ);
        if (idx < 0) return null;
        return text.substring(0, idx) + newS + text.substring(idx + old.length());
    }

    /**
     * 找不到片段时的候选行提示：取 old 首个非空行，列出 text 中包含它的行
     *（1-based 行号 "L<n>: <行>"，每行 trim 后截 80 字），最多 maxLines 行。
     * 无命中返回空串。
     */
    public static String editHints(String text, String old, int maxLines) {
        if (text == null || old == null || maxLines < 1) return "";
        String first = "";
        for (String ln : old.split("\n")) {
            if (!ln.trim().isEmpty()) { first = ln.trim(); break; }
        }
        if (first.isEmpty()) return "";
        StringBuilder hint = new StringBuilder();
        String[] lines = text.split("\n", -1);
        int shown = 0;
        for (int i = 0; i < lines.length && shown < maxLines; i++) {
            if (lines[i].contains(first)) {
                String t = lines[i].trim();
                hint.append("L").append(i + 1).append(": ")
                    .append(t.length() > 80 ? t.substring(0, 80) + "..." : t)
                    .append("\n");
                shown++;
            }
        }
        return hint.toString();
    }
}
