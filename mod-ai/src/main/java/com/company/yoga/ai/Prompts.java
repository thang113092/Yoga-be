package com.company.yoga.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * System instructions live in {@code resources/ai/prompts/<name>.md}, not in Java code. Each file starts with
 * {@code version: <name>/<n>} and a {@code ---} line; the version is stored with every result so prompt changes can
 * be compared.
 */
@Component
public class Prompts {

    public record Prompt(String version, String text) {
    }

    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public Prompt get(String name) {
        return cache.computeIfAbsent(name, Prompts::load);
    }

    static Prompt load(String name) {
        String path = "ai/prompts/" + name + ".md";
        try (InputStream in = Prompts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing prompt " + path);
            return parse(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Prompt parse(String path, String content) {
        String normalized = content.replace("\r\n", "\n");
        int separator = normalized.indexOf("\n---\n");
        String header = separator < 0 ? "" : normalized.substring(0, separator).trim();
        if (!header.startsWith("version:")) throw new IllegalStateException(path + " must start with 'version: <name>/<n>' and '---'");
        String text = normalized.substring(separator + 5).trim();
        if (text.isEmpty()) throw new IllegalStateException(path + " has no instruction text");
        return new Prompt(header.substring("version:".length()).trim(), text);
    }
}
