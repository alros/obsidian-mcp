package eu.rossotto.obsidian.vault;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterTest {

    @Test
    void blockList() {
        Frontmatter fm = Frontmatter.parse("---\ntags:\n  - movies\n  - cinema\n  - list\n---\n\nthis is the body of the note\n");
        assertEquals(List.of("movies", "cinema", "list"), fm.tags());
        assertEquals("\nthis is the body of the note\n", fm.body());
    }

    @Test
    void inlineListStringAndHashes() {
        assertEquals(List.of("a", "b"), Frontmatter.parse("---\ntags: [a, \"#b\"]\n---\n").tags());
        assertEquals(List.of("a", "b", "c"), Frontmatter.parse("---\ntags: a, b c\n---\n").tags());
        assertEquals(List.of("x"), Frontmatter.parse("---\ntag: x\n---\n").tags());
    }

    @Test
    void crlfAndBom() {
        Frontmatter fm = Frontmatter.parse("﻿---\r\ntags:\r\n  - win\r\ntitle: T\r\n---\r\nbody");
        assertEquals(List.of("win"), fm.tags());
        assertEquals("T", fm.title().orElseThrow());
        assertEquals("body", fm.body());
    }

    @Test
    void noFrontmatter() {
        Frontmatter fm = Frontmatter.parse("# Title\n---\ntags: [x]\n---\n");
        assertTrue(fm.tags().isEmpty());
    }

    @Test
    void malformedYamlMeansUntagged() {
        Frontmatter fm = Frontmatter.parse("---\ntags: [unclosed\n---\nbody");
        assertTrue(fm.tags().isEmpty());
        assertEquals("body", fm.body());
    }

    @Test
    void unterminatedIsBody() {
        Frontmatter fm = Frontmatter.parse("---\ntags: [x]\nno end");
        assertTrue(fm.tags().isEmpty());
    }
}
