package com.example.airmouse;

import android.os.Build;
import android.os.Trace;


public final class TraceMarks {
    private TraceMarks() {}

    public static void begin(String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Trace.beginSection(name);
        }
    }

    public static void end() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Trace.endSection();
        }
    }
}
