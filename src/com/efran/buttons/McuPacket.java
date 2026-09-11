package com.efran.buttons;

/** Read-only decoder at EventCenter's packet dispatch boundary. */
public final class McuPacket {
    public static boolean isButton(byte[] data) {
        return data != null && data.length >= 7 && data[1] == 0
                && (data[2] & 255) == 0xA1 && (data[4] & 255) == 0x17;
    }
    public static String describe(byte[] data) {
        if (isButton(data)) return "MCU button " + (data[5] & 255) + " / state " + (data[6] & 255);
        if (isSourceSwitch(data)) return "MCU display switch / value " + (data[5] & 255);
        return "MCU packet";
    }
    public static boolean isSourceSwitch(byte[] data) {
        return data != null && data.length >= 6 && data[1] == 0
                && (data[2] & 255) == 0xA1 && (data[4] & 255) == 0x1A;
    }
    public static String hex(byte[] data) {
        if (data == null) return "null";
        char[] alphabet = "0123456789ABCDEF".toCharArray();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 64); i++) {
            if (i > 0) out.append(' ');
            out.append(alphabet[(data[i] & 255) >>> 4]).append(alphabet[data[i] & 15]);
        }
        if (data.length > 64) out.append(" ...");
        return out.toString();
    }
}
