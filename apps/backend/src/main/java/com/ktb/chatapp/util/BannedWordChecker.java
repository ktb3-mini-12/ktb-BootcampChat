package com.ktb.chatapp.util;

import java.util.Locale;
import java.util.Set;
import org.ahocorasick.trie.Trie;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Trie trie;

    public BannedWordChecker(Set<String> bannedWords) {
        Assert.notEmpty(bannedWords, "Banned words set must not be empty");

        // Aho-Corasick Trie 구축 - O(N) 전처리, 이후 검색은 O(m)
        Trie.TrieBuilder builder = Trie.builder()
                .ignoreCase()
                .ignoreOverlaps();

        bannedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .forEach(builder::addKeyword);

        this.trie = builder.build();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        // O(m) 검색 - m은 메시지 길이
        return trie.containsMatch(message);
    }
}
