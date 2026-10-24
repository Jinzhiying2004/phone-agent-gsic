package com.gsic.app;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;

import dalvik.system.DexClassLoader;

/**
 * Nuwa 式 dex 注入：把补丁 dex 的 dexElements 插到主 ClassLoader 最前面，
 * 同名类优先命中补丁版本，实现整类替换。失败时抛异常，由调用方降级（不加载补丁）。
 */
public final class DexInjector {

    private DexInjector() {
    }

    public static void injectFirst(Context ctx, File patchDex) throws Exception {
        File optDir = new File(ctx.getFilesDir(), "patch_opt");
        if (!optDir.exists() && !optDir.mkdirs()) {
            throw new IllegalStateException("无法创建补丁优化目录");
        }
        DexClassLoader patchLoader = new DexClassLoader(
                patchDex.getAbsolutePath(), optDir.getAbsolutePath(), null,
                ctx.getClassLoader());
        Object patchPathList = getField(patchLoader, "pathList");
        Object basePathList = getField(ctx.getClassLoader(), "pathList");
        Object patchElems = getField(patchPathList, "dexElements");
        Object baseElems = getField(basePathList, "dexElements");
        Object merged = mergeFirst(patchElems, baseElems);
        setField(basePathList, "dexElements", merged);
    }

    private static Object mergeFirst(Object first, Object second) {
        Class<?> comp = first.getClass().getComponentType();
        int n1 = Array.getLength(first);
        int n2 = Array.getLength(second);
        Object out = Array.newInstance(comp, n1 + n2);
        for (int i = 0; i < n1; i++) Array.set(out, i, Array.get(first, i));
        for (int i = 0; i < n2; i++) Array.set(out, n1 + i, Array.get(second, i));
        return out;
    }

    private static Object getField(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object o, String name, Object v) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, v);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
