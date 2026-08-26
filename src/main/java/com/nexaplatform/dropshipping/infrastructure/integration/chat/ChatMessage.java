package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import java.util.List;

/**
 * Un turno de la conversación. Según el papel se usan unos campos u otros:
 * <ul>
 * <li>{@code SYSTEM}, {@code USER}: solo {@code content}.</li>
 * <li>{@code ASSISTANT}: {@code content} y/o {@code toolCalls}.</li>
 * <li>{@code TOOL}: {@code content} con el resultado y {@code toolCallId} con
 * el identificador de la llamada que lo pidió.</li>
 * </ul>
 */
public record ChatMessage(ChatRole role, String content, String toolCallId, List<ChatToolCall> toolCalls) {

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, List.of());
    }

    public static ChatMessage assistant(String content, List<ChatToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, toolCalls == null ? List.of() : toolCalls);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, toolCallId, List.of());
    }
}
