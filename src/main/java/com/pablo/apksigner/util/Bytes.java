package com.pablo.apksigner.util;

import java.nio.ByteOrder;
import java.util.Objects;

/**
 * A simplified utility class for byte array operations, focusing only on
 * wrap(byte[]) and encodeHex().
 */
public class Bytes {

    private static final Bytes EMPTY = Bytes.wrap(new byte[0]);

    private final byte[] byteArray;
    private final ByteOrder byteOrder;
    private final BytesFactory factory;

    Bytes(byte[] byteArray, ByteOrder byteOrder, BytesFactory factory) {
        this.byteArray = byteArray;
        this.byteOrder = byteOrder;
        this.factory = factory;
    }

    Bytes(byte[] byteArray, ByteOrder byteOrder) {
        this(byteArray, byteOrder, new Factory());
    }

    public static Bytes wrap(byte[] array) {
        return wrap(array, ByteOrder.BIG_ENDIAN);
    }

    public static Bytes wrap(byte[] array, ByteOrder byteOrder) {
        return new Bytes(Objects.requireNonNull(array, "passed array must not be null"), byteOrder);
    }

    public static Bytes empty() {
        return EMPTY;
    }

    public String encodeHex() {
        return encodeHex(false);
    }

    String encodeHex(boolean upperCase) {
        return encode(new Hex(upperCase));
    }

    String encode(Encoder encoder) {
        return encoder.encode(internalArray(), byteOrder);
    }

    byte[] internalArray() {
        return byteArray;
    }

    public ByteOrder byteOrder() {
        return byteOrder;
    }

    protected interface BytesFactory {
        Bytes wrap(byte[] array, ByteOrder byteOrder);
    }

    private static class Factory implements BytesFactory {
        @Override
        public Bytes wrap(byte[] array, ByteOrder byteOrder) {
            return new Bytes(array, byteOrder);
        }
    }

    protected interface Encoder {
        String encode(byte[] array, ByteOrder byteOrder);
    }

    protected static class Hex implements Encoder {

        private static final char[] LOOKUP_TABLE_LOWER = new char[]{
                0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66
        };
        private static final char[] LOOKUP_TABLE_UPPER = new char[]{
                0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46
        };
        private final boolean upperCase;

        public Hex(boolean upperCase) {
            this.upperCase = upperCase;
        }

        @Override
        public String encode(byte[] byteArray, ByteOrder byteOrder) {
            final char[] buffer = new char[byteArray.length * 2];
            final char[] lookup = upperCase ? LOOKUP_TABLE_UPPER : LOOKUP_TABLE_LOWER;

            int index;
            for (int i = 0; i < byteArray.length; i++) {
                // Determining the reading order based on byte order
                index = (byteOrder == ByteOrder.BIG_ENDIAN) ? i : byteArray.length - i - 1;

                // Process upper 4 bits
                buffer[i << 1] = lookup[(byteArray[index] >> 4) & 0xF];
                // Process lower 4 bits
                buffer[(i << 1) + 1] = lookup[(byteArray[index] & 0xF)];
            }
            return new String(buffer);
        }
    }
}