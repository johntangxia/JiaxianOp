package com.jiaxian.analytics.people;

public record AccountTypeRow(String type, String label, long creators, long posts, long pv, long interactions,
                             Double interactionRate) {
}
