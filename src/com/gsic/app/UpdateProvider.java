package com.gsic.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 更新 APK 的文件分享 Provider（不依赖 androidx），只服务 updates 目录。
 * authority: com.gsic.app.updateprovider
 * uri 形如：content://com.gsic.app.updateprovider/updates/update.apk
 * 供系统安装器读取（FLAG_GRANT_READ_URI_PERMISSION）。
 */
public class UpdateProvider extends ContentProvider {

    private static final String AUTH = "com.gsic.app.updateprovider";
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTH, "updates/*", 1);
    }

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTH + "/updates/" + f.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (MATCHER.match(uri) != 1) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        // 防路径穿越
        if (name == null || name.contains("/") || name.contains(".."))
            throw new FileNotFoundException();
        File dir = new File(getContext().getFilesDir(), "updates");
        File f = new File(dir, name);
        if (!f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(
                new String[]{"_display_name", "_size"});
        try {
            String name = uri.getLastPathSegment();
            File f = new File(new File(getContext().getFilesDir(), "updates"), name);
            if (f.exists()) c.addRow(new Object[]{name, f.length()});
        } catch (Exception ignored) {
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
