package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.ChatAskDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ChatAnswerDtoOut;
import com.nexaplatform.dropshipping.application.chat.ChatAnswer;
import com.nexaplatform.dropshipping.application.chat.ChatContext;
import com.nexaplatform.dropshipping.application.usecase.ChatUseCase;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Asistente conversacional del escaparate. */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Asistente conversacional de la tienda")
public class ChatController {

    private final ChatUseCase chatUseCase;

    @PostMapping
    @Operation(summary = "Envía un mensaje al asistente y obtiene su respuesta")
    public ResponseEntity<ChatAnswerDtoOut> ask(@Valid @RequestBody ChatAskDtoIn body) {
        ChatContext context = new ChatContext(usuarioActual(), body.getLang());
        ChatAnswer answer = chatUseCase.ask(body.getConversationId(), body.getMessage(), context);
        return ResponseEntity.ok(ChatAnswerDtoOut.builder().conversationId(answer.conversationId())
                .reply(answer.reply()).products(answer.products()).degraded(answer.degraded()).reason(answer.reason())
                .searchQuery(answer.searchQuery()).searchTotal(answer.searchTotal()).build());
    }

    /**
     * Usuario de la sesión, si lo hay. Se toma del contexto de seguridad y NUNCA
     * del cuerpo de la petición: es lo que impide que una conversación pida los
     * pedidos de otra persona sin más que nombrar su identificador.
     */
    private UUID usuarioActual() {
        String subject = SecurityUtils.currentSubject();
        if (subject == null || subject.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
