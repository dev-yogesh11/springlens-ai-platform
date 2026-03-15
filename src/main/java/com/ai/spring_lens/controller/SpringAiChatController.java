package com.ai.spring_lens.controller;

import com.ai.spring_lens.model.request.ChatRequest;
import com.ai.spring_lens.model.response.ErrorResponse;
import com.ai.spring_lens.service.SpringAiChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/ai/chat")
public class SpringAiChatController {

    private final SpringAiChatService chatService;

    public SpringAiChatController(SpringAiChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Mono<ResponseEntity<Object>> chat(
            @RequestBody ChatRequest request
    ) {
        return chatService.chat(request.message())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(ex -> {
                    String message = ex.getMessage() != null
                            ? ex.getMessage()
                            : "Unexpected error occurred";
                    return Mono.just(ResponseEntity
                            .internalServerError()
                            .body((Object) new ErrorResponse("LLM_ERROR", message)));
                });
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message) {
        return chatService.stream(message);
    }

    @PostMapping("/query")
    public Mono<ResponseEntity<Object>> query(
            @RequestBody ChatRequest request
    ) {
        return chatService.query(request.message())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(ex -> Mono.just(
                        ResponseEntity.internalServerError()
                                .body((Object) new ErrorResponse("QUERY_ERROR", ex.getMessage()))
                ));
    }
}