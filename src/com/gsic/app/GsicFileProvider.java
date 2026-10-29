package com.gsic.app;

import android.content.ContentProvider;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * v3.74: 原生文件分享 Provider，只服务 inbox/outbox（不走邮箱）。
 * authority: com.gsic.app.fileprovider
 * uri 形如：content://com.gsic.app.fileprovider/inbox/<相对路径>
 * 或 content://com.gsic.app.fileprovider/outbox/<相对路径>
 * 防路径穿越：解出相对路径后 canonical 必须在 inbox/outbox 根内。
 */
public class GsicFileProvider extends ContentProvider {

    private static final String AUTH = "com.gsic.app.fileprovider";

    public static Uri uriFor(Context ctx, File f) {
        try {
            String inboxCanon = new File(ctx.getFilesDir(), "inbox").getCanonicalPath();
            String outboxCanon = new File(ctx.getFilesDir(), "outbox").getCanonicalPath();
            String canon = f.getCanonicalPath();
            if (canon.equals(inboxCanon) || canon.startsWith(inboxCanon + "/")) {
                return Uri.parse("content://" + AUTH + "/inbox/" + canon.substring(inboxCanon.length() + 1));
            }
            if (canon.equals(outboxCanon) || canon.startsWith(outboxCanon + "/")) {
                return Uri.parse("content://" + AUTH + "/outbox/" + canon.substring(outboxCanon.length() + 1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        java.util.List<String> segs = uri.getPathSegments();
        if (segs == null || segs.size() < 2) throw new FileNotFoundException();
        String rootName = segs.get(0);
        if (!rootName.equals("inbox") && !rootName.equals("outbox")) throw new FileNotFoundException();
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < segs.size(); i++) {
            if (i > 1) rel.append("/");
            rel.append(segs.get(i));
        }
        try {
            File root = new File(getContext().getFilesDir(), rootName);
            File f = new File(root, rel.toString());
            String rootCanon = root.getCanonicalPath();
            String canon = f.getCanonicalPath();
            if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + "/")) throw new FileNotFoundException();
            if (!f.exists() || f.isDirectory()) throw new FileNotFoundException();
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException();
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveFile(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            File f = resolveFile(uri);
            return FileInbox.mimeFor(f.getName());
        } catch (Exception e) {
            return "*/*";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"_display_name", "_size"});
        try {
            File f = resolveFile(uri);
            c.addRow(new Object[]{f.getName(), f.length()});
        } catch (Exception ignored) {
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
