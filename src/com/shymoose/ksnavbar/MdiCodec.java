// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Decodes the font and name table that tools/gen_mdi.py packs into MdiData. */
final class MdiCodec {
    private MdiCodec() {}

    static byte[] decode(String[] chunks) {
        StringBuilder text = new StringBuilder();
        for (String chunk : chunks) text.append(chunk);
        byte[] deflated = Base64.getDecoder().decode(text.toString());
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(deflated);
            ByteArrayOutputStream out = new ByteArrayOutputStream(deflated.length * 2);
            byte[] buffer = new byte[64 * 1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalStateException("Truncated icon data");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (DataFormatException error) {
            throw new IllegalStateException("Corrupt icon data", error);
        } finally {
            inflater.end();
        }
    }

    /** name to codepoint, from lines of name=HEX. */
    static Map<String, Integer> names(byte[] table) {
        Map<String, Integer> result = new HashMap<>(16384);
        for (String line : new String(table, StandardCharsets.US_ASCII).split("\n")) {
            int split = line.indexOf('=');
            if (split > 0) result.put(line.substring(0, split), Integer.parseInt(line.substring(split + 1), 16));
        }
        return result;
    }

    /** {@code mdi:home}, {@code home} or {@code mdi-home} to the bare MDI name. Null when it is not an MDI icon. */
    static String bareName(String icon) {
        if (icon == null) return null;
        String name = icon.trim();
        if (name.startsWith("mdi:")) name = name.substring(4);
        else if (name.startsWith("mdi-")) name = name.substring(4);
        else if (name.contains(":")) return null;
        return name.isEmpty() ? null : name;
    }
}
