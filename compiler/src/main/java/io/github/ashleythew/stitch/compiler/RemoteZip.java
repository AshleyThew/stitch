package io.github.ashleythew.stitch.compiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A zip file on a web server read with HTTP range requests: its directory, then only the entries
 * asked for, so a few shader files come out of a client jar of tens of megabytes without downloading
 * it.
 */
final class RemoteZip {

    record Entry(String name, long offset, long compressed, long size, int method) {
    }

    private final String url;
    private final long length;
    final Map<String, Entry> entries = new LinkedHashMap<>();

    RemoteZip(String url, long length) throws IOException {
        this.url = url;
        this.length = length;
        int tail = (int) Math.min(length, 65557);
        ByteBuffer end = little(range(length - tail, length - 1));
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (end.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException(url + " isn't a zip file");
        }
        long cdSize = Integer.toUnsignedLong(end.getInt(eocd + 12));
        long cdOffset = Integer.toUnsignedLong(end.getInt(eocd + 16));
        if (cdOffset == 0xFFFFFFFFL && eocd >= 20 && end.getInt(eocd - 20) == 0x07064b50) {
            long zip64 = end.getLong(eocd - 20 + 8);
            ByteBuffer z = little(range(zip64, zip64 + 55));
            cdSize = z.getLong(40);
            cdOffset = z.getLong(48);
        }
        ByteBuffer cd = little(range(cdOffset, cdOffset + cdSize - 1));
        int at = 0;
        while (at + 46 <= cd.limit() && cd.getInt(at) == 0x02014b50) {
            int method = Short.toUnsignedInt(cd.getShort(at + 10));
            long compressed = Integer.toUnsignedLong(cd.getInt(at + 20));
            long size = Integer.toUnsignedLong(cd.getInt(at + 24));
            int nameLength = Short.toUnsignedInt(cd.getShort(at + 28));
            int extraLength = Short.toUnsignedInt(cd.getShort(at + 30));
            int commentLength = Short.toUnsignedInt(cd.getShort(at + 32));
            long offset = Integer.toUnsignedLong(cd.getInt(at + 42));
            byte[] name = new byte[nameLength];
            cd.get(at + 46, name);
            // zip64 sizes and offset, where the 32-bit fields are full
            int extra = at + 46 + nameLength;
            int extraEnd = extra + extraLength;
            while (extra + 4 <= extraEnd) {
                int id = Short.toUnsignedInt(cd.getShort(extra));
                int len = Short.toUnsignedInt(cd.getShort(extra + 2));
                if (id == 1) {
                    int p = extra + 4;
                    if (size == 0xFFFFFFFFL) {
                        size = cd.getLong(p);
                        p += 8;
                    }
                    if (compressed == 0xFFFFFFFFL) {
                        compressed = cd.getLong(p);
                        p += 8;
                    }
                    if (offset == 0xFFFFFFFFL) {
                        offset = cd.getLong(p);
                    }
                }
                extra += 4 + len;
            }
            String n = new String(name, StandardCharsets.UTF_8);
            entries.put(n, new Entry(n, offset, compressed, size, method));
            at += 46 + nameLength + extraLength + commentLength;
        }
    }

    /** The entry's bytes, or null if the zip has no such entry. */
    byte[] read(String name) throws IOException {
        Entry e = entries.get(name);
        if (e == null) {
            return null;
        }
        ByteBuffer local = little(range(e.offset(), e.offset() + 29));
        if (local.getInt(0) != 0x04034b50) {
            throw new IOException(name + ": no local header");
        }
        long data = e.offset() + 30 + Short.toUnsignedInt(local.getShort(26)) + Short.toUnsignedInt(local.getShort(28));
        byte[] raw = e.compressed() == 0 ? new byte[0] : range(data, data + e.compressed() - 1);
        if (e.method() == 0) {
            return raw;
        }
        if (e.method() != 8) {
            throw new IOException(name + ": compression method " + e.method() + " isn't supported");
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(raw);
            byte[] out = new byte[(int) e.size()];
            int done = 0;
            while (done < out.length && !inflater.finished()) {
                int n = inflater.inflate(out, done, out.length - done);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                done += n;
            }
            if (done != out.length) {
                throw new IOException(name + ": inflated " + done + " of " + out.length + " bytes");
            }
            return out;
        } catch (DataFormatException ex) {
            throw new IOException(name + ": " + ex.getMessage(), ex);
        } finally {
            inflater.end();
        }
    }

    private byte[] range(long from, long to) throws IOException {
        if (from < 0 || to >= length || to < from) {
            throw new IOException("range " + from + "-" + to + " is outside " + url);
        }
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        c.setRequestProperty("User-Agent", Minecraft.USER_AGENT);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 206) {
                throw new IOException(url + " answered " + c.getResponseCode() + " to a range request");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) (to - from + 1));
            in.transferTo(out);
            byte[] bytes = out.toByteArray();
            if (bytes.length != to - from + 1) {
                throw new IOException(url + " sent " + bytes.length + " bytes of " + (to - from + 1));
            }
            return bytes;
        } finally {
            c.disconnect();
        }
    }

    private static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
