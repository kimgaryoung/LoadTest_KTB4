package com.ktb.chatapp.util;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {
    
    private final Set<String> bannedWords;
    private final TrieNode root = new TrieNode();
    
    public BannedWordChecker(Set<String> bannedWords) {
        this.bannedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(this.bannedWords, "Banned words set must not be empty");
        this.bannedWords.forEach(this::insert);
    }
    
    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        for (int start = 0; start < normalizedMessage.length(); start++) {
            TrieNode node = root;
            for (int cursor = start; cursor < normalizedMessage.length(); cursor++) {
                node = node.children.get(normalizedMessage.charAt(cursor));
                if (node == null) {
                    break;
                }
                if (node.terminal) {
                    return true;
                }
            }
        }
        return false;
    }

    private void insert(String word) {
        TrieNode node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), ignored -> new TrieNode());
        }
        node.terminal = true;
    }

    private static final class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private boolean terminal;
    }
}
