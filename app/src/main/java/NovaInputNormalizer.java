package com.aircontrol;

import java.text.Normalizer;

/** Normalizes transport noise without rewriting user intent or expanding command dictionaries. */
public final class NovaInputNormalizer {
    private NovaInputNormalizer() { }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201B', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replaceAll("\\s+", " ")
                .trim();
        return value;
    }
}
