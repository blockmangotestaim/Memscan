package com.memscan;

import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * READ-ONLY memory scanner. Launched as root:
 *   CLASSPATH=base.apk app_process /system/bin com.memscan.Scanner <pid> <type> <value> <aligned 1|0>
 * Reads /proc/<pid>/maps and /proc/<pid>/mem. Never writes to the target.
 * Output protocol (stdout): "P <pct>", "R <addr> <value>", "DONE <count> <bytesScanned>"
 */
public class Scanner {
    static final int CH = 4 * 1024 * 1024;   // multiple of 8 -> alignment is preserved across chunks
    static final int MAX_RESULTS = 1000;

    static boolean skip(String perms, String path) {
        if (perms.charAt(0) != 'r') return true;          // must be readable
        if (perms.indexOf('x') >= 0) return true;         // code, no values live here
        if (!path.isEmpty()) {
            if (perms.indexOf('w') < 0) return true;      // read-only file mappings (libs, assets)
            if (path.startsWith("/dev/") || path.startsWith("[vvar") || path.startsWith("[vsyscall")) return true;
            if (path.startsWith("/system") || path.startsWith("/apex") || path.startsWith("/vendor")
                    || path.startsWith("/product") || path.startsWith("/odm")) return true;
        }
        return false;
    }

    static String fmt(ByteBuffer bb, byte[] buf, int i, int kind, int sz) {
        if (kind == 1) return Float.toString(bb.getFloat(i));
        if (kind == 2) return Double.toString(bb.getDouble(i));
        switch (sz) {
            case 1: return Byte.toString(buf[i]);
            case 2: return Short.toString(bb.getShort(i));
            case 4: return Integer.toString(bb.getInt(i));
            default: return Long.toString(bb.getLong(i));
        }
    }

    public static void main(String[] a) throws Exception {
        int pid = Integer.parseInt(a[0]);
        String type = a[1];
        String val = a[2];
        boolean aligned = a.length < 4 || !a[3].equals("0");

        int sz, kind;
        long iv = 0;
        double dv = 0;
        switch (type) {
            case "i8":  sz = 1; kind = 0; iv = Long.parseLong(val); break;
            case "i16": sz = 2; kind = 0; iv = Long.parseLong(val); break;
            case "i32": sz = 4; kind = 0; iv = Long.parseLong(val); break;
            case "i64": sz = 8; kind = 0; iv = Long.parseLong(val); break;
            case "f32": sz = 4; kind = 1; dv = Double.parseDouble(val); break;
            case "f64": sz = 8; kind = 2; dv = Double.parseDouble(val); break;
            default: System.out.println("ERR bad type"); return;
        }
        final float fv = (float) dv;
        final int step = aligned ? sz : 1;

        // ---- collect regions ----
        List<long[]> regions = new ArrayList<>();
        long totalBytes = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split("\\s+", 6);
                if (p.length < 5) continue;
                String path = p.length > 5 ? p[5] : "";
                if (skip(p[1], path)) continue;
                String[] r = p[0].split("-");
                long s = Long.parseUnsignedLong(r[0], 16), e = Long.parseUnsignedLong(r[1], 16);
                if (e <= s) continue;
                regions.add(new long[]{s, e});
                totalBytes += e - s;
            }
        }

        RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r");
        byte[] buf = new byte[CH + 8];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

        long total = 0, done = 0;
        int lastPct = -1;
        StringBuilder out = new StringBuilder();
        int stored = 0;

        for (long[] reg : regions) {
            long s = reg[0], e = reg[1];
            for (long off = s; off < e; off += CH) {
                int want = (int) Math.min((long) CH + sz - 1, e - off);
                int got = 0;
                try {
                    f.seek(off);
                    while (got < want) {
                        int n = f.read(buf, got, want - got);
                        if (n <= 0) break;
                        got += n;
                    }
                } catch (IOException ex) {
                    break;                                   // unreadable page range: skip rest of region
                }
                if (got < sz) break;
                int lim = Math.min(CH, got - sz + 1);
                for (int i = 0; i < lim; i += step) {
                    boolean m;
                    if (kind == 0) {
                        switch (sz) {
                            case 1:  m = buf[i] == (byte) iv; break;
                            case 2:  m = bb.getShort(i) == (short) iv; break;
                            case 4:  m = bb.getInt(i) == (int) iv; break;
                            default: m = bb.getLong(i) == iv; break;
                        }
                    } else if (kind == 1) {
                        m = Math.abs(bb.getFloat(i) - fv) < 0.0001f;
                    } else {
                        m = Math.abs(bb.getDouble(i) - dv) < 0.0000001;
                    }
                    if (m) {
                        total++;
                        if (stored < MAX_RESULTS) {
                            out.append("R 0x").append(Long.toHexString(off + i).toUpperCase())
                               .append(' ').append(fmt(bb, buf, i, kind, sz)).append('\n');
                            stored++;
                        }
                    }
                }
                done += Math.min((long) CH, e - off);
                int pct = totalBytes == 0 ? 100 : (int) (done * 100 / totalBytes);
                if (pct != lastPct) { lastPct = pct; System.out.println("P " + pct); System.out.flush(); }
            }
        }
        f.close();
        System.out.print(out);
        System.out.println("DONE " + total + " " + done);
        System.out.flush();
    }
}
