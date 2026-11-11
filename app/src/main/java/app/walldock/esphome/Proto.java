package app.walldock.esphome;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Encodage / décodage Protocol Buffers minimal, suffisant pour l'API native ESPHome. */
final class Proto {

    private Proto() {}

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_LEN = 2;
    static final int WIRE_FIXED32 = 5;

    /** Construit un message champ par champ. Les valeurs par défaut (0, false, "") sont omises. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer varint(int field, long value) {
            if (value == 0) return this;
            tag(field, WIRE_VARINT);
            writeVarint(out, value);
            return this;
        }

        Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        Writer fixed32(int field, int value) {
            if (value == 0) return this;
            tag(field, WIRE_FIXED32);
            writeFixed32(value);
            return this;
        }

        Writer floatValue(int field, float value) {
            if (value == 0f && Float.floatToRawIntBits(value) == 0) return this;
            tag(field, WIRE_FIXED32);
            writeFixed32(Float.floatToIntBits(value));
            return this;
        }

        Writer string(int field, String value) {
            if (value == null || value.isEmpty()) return this;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            tag(field, WIRE_LEN);
            writeVarint(out, bytes.length);
            out.write(bytes, 0, bytes.length);
            return this;
        }

        /** Champ « message imbriqué » ou chaîne déjà encodée (toujours écrit, même vide). */
        Writer message(int field, byte[] value) {
            tag(field, WIRE_LEN);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        /** Chaîne écrite même si elle est vide (éléments de listes répétées). */
        Writer stringAlways(int field, String value) {
            return message(field, value.getBytes(StandardCharsets.UTF_8));
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void tag(int field, int wire) {
            writeVarint(out, ((long) field << 3) | wire);
        }

        private void writeFixed32(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
        }
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /** Champs décodés d'un message : numéro de champ → valeur brute (Long pour varint/fixed, byte[] pour len). */
    static Map<Integer, Object> read(byte[] data) {
        Map<Integer, Object> fields = new HashMap<>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long tag = readVarint(data, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (wire) {
                case WIRE_VARINT:
                    fields.put(field, readVarint(data, pos));
                    break;
                case WIRE_FIXED64: {
                    long v = 0;
                    for (int i = 0; i < 8; i++) v |= ((long) (data[pos[0] + i] & 0xFF)) << (8 * i);
                    pos[0] += 8;
                    fields.put(field, v);
                    break;
                }
                case WIRE_LEN: {
                    int len = (int) readVarint(data, pos);
                    byte[] value = new byte[len];
                    System.arraycopy(data, pos[0], value, 0, len);
                    pos[0] += len;
                    fields.put(field, value);
                    break;
                }
                case WIRE_FIXED32: {
                    long v = 0;
                    for (int i = 0; i < 4; i++) v |= ((long) (data[pos[0] + i] & 0xFF)) << (8 * i);
                    pos[0] += 4;
                    fields.put(field, v);
                    break;
                }
                default:
                    return fields; // type inconnu : on s'arrête là
            }
        }
        return fields;
    }

    /** Toutes les occurrences d'un champ répété de type « len » (messages imbriqués, chaînes). */
    static List<byte[]> readRepeated(byte[] data, int wantedField) {
        List<byte[]> values = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long tag = readVarint(data, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == WIRE_VARINT) {
                readVarint(data, pos);
            } else if (wire == WIRE_FIXED64) {
                pos[0] += 8;
            } else if (wire == WIRE_FIXED32) {
                pos[0] += 4;
            } else if (wire == WIRE_LEN) {
                int len = (int) readVarint(data, pos);
                if (field == wantedField) {
                    byte[] value = new byte[len];
                    System.arraycopy(data, pos[0], value, 0, len);
                    values.add(value);
                }
                pos[0] += len;
            } else {
                break;
            }
        }
        return values;
    }

    static String getString(Map<Integer, Object> fields, int field) {
        Object v = fields.get(field);
        return v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : "";
    }

    static long readVarint(byte[] data, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < data.length) {
            int b = data[pos[0]++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    static int getInt(Map<Integer, Object> fields, int field) {
        Object v = fields.get(field);
        return v instanceof Long ? (int) (long) (Long) v : 0;
    }

    static boolean getBool(Map<Integer, Object> fields, int field) {
        return getInt(fields, field) != 0;
    }

    static float getFloat(Map<Integer, Object> fields, int field) {
        return Float.intBitsToFloat(getInt(fields, field));
    }
}
