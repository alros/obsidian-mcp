package eu.rossotto.obsidian.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The YAML properties block at the top of a note, delimited by {@code ---} lines.
 * Only {@code tags} (alias {@code tag}) and {@code title} are used.
 */
public record Frontmatter(List<String> tags, Optional<String> title, String body) {

    private static final ObjectMapper YAML = new YAMLMapper();

    public static Frontmatter parse(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        if (!(s.startsWith("---\n") || s.startsWith("---\r\n"))) {
            return new Frontmatter(List.of(), Optional.empty(), s);
        }
        int yamlStart = s.indexOf('\n') + 1;
        int pos = yamlStart;
        int yamlEnd = -1;
        int bodyStart = -1;
        while (pos < s.length()) {
            int nl = s.indexOf('\n', pos);
            int end = nl < 0 ? s.length() : nl;
            if (s.substring(pos, end).stripTrailing().equals("---")) {
                yamlEnd = pos;
                bodyStart = nl < 0 ? s.length() : nl + 1;
                break;
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        if (yamlEnd < 0) {
            // Unterminated block: Obsidian renders it as plain text.
            return new Frontmatter(List.of(), Optional.empty(), s);
        }
        String yaml = s.substring(yamlStart, yamlEnd);
        String body = s.substring(bodyStart);

        JsonNode root;
        try {
            root = yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (Exception e) {
            // Malformed properties: Obsidian ignores them too, so the note counts as untagged.
            return new Frontmatter(List.of(), Optional.empty(), body);
        }
        if (root == null || !root.isObject()) {
            return new Frontmatter(List.of(), Optional.empty(), body);
        }
        Set<String> tags = new LinkedHashSet<>();
        collectTags(root.get("tags"), tags);
        collectTags(root.get("tag"), tags);
        JsonNode t = root.get("title");
        Optional<String> title = t != null && t.isValueNode() && !t.asText().isBlank()
                ? Optional.of(t.asText().strip())
                : Optional.empty();
        return new Frontmatter(List.copyOf(tags), title, body);
    }

    private static void collectTags(JsonNode node, Set<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> {
                if (n.isValueNode()) {
                    addTag(n.asText(), out);
                }
            });
        } else if (node.isValueNode()) {
            // "tags: a, b" and "tags: a b" are both accepted by Obsidian
            for (String part : node.asText().split("[,\\s]+")) {
                addTag(part, out);
            }
        }
    }

    private static void addTag(String raw, Set<String> out) {
        String tag = raw.strip();
        while (tag.startsWith("#")) {
            tag = tag.substring(1);
        }
        if (!tag.isEmpty()) {
            out.add(tag);
        }
    }
}
