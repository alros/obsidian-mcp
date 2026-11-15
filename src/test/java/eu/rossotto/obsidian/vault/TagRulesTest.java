package eu.rossotto.obsidian.vault;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagRulesTest {

    private final TagRules rules = new TagRules(List.of("project", "howto"), List.of("private"), false);

    @Test
    void allowedTagIsVisible() {
        assertTrue(rules.isVisible(List.of("project")));
        assertTrue(rules.isVisible(List.of("misc", "howto")));
    }

    @Test
    void nestedTagsInheritRules() {
        assertTrue(rules.isVisible(List.of("project/alpha")));
        assertTrue(rules.isVisible(List.of("Project/Alpha/Deep")));
        assertFalse(rules.isVisible(List.of("project", "private/finance")));
    }

    @Test
    void prefixWithoutSlashDoesNotMatch() {
        assertFalse(rules.isVisible(List.of("projects")));
        assertTrue(new TagRules(List.of(), List.of("private"), false).isVisible(List.of("privateer")));
    }

    @Test
    void ignoredWinsOverAllowed() {
        assertFalse(rules.isVisible(List.of("project", "private")));
    }

    @Test
    void tagNotInAllowListIsHidden() {
        assertFalse(rules.isVisible(List.of("misc")));
    }

    @Test
    void emptyAllowListMeansEverything() {
        TagRules open = new TagRules(List.of(), List.of("journal"), false);
        assertTrue(open.isVisible(List.of("anything")));
        assertFalse(open.isVisible(List.of("journal/daily")));
    }

    @Test
    void untaggedFollowsFlag() {
        assertFalse(rules.isVisible(List.of()));
        assertTrue(new TagRules(List.of("project"), List.of(), true).isVisible(List.of()));
    }

    @Test
    void caseAndHashAreIgnored() {
        assertTrue(rules.isVisible(List.of("#PROJECT")));
    }
}
