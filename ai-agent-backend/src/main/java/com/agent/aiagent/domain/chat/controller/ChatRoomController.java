package com.agent.aiagent.domain.chat.controller;

import com.agent.aiagent.domain.chat.dto.*;
import com.agent.aiagent.domain.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/rooms")
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatRoomResponse createRoom(
            @RequestBody(required = false) ChatRoomCreateRequest request
    ) {
        return chatRoomService.createRoom(request);
    }

    @GetMapping
    public List<ChatRoomResponse> getRooms() {
        return chatRoomService.getRooms();
    }

    @GetMapping("/{roomId}")
    public ChatRoomResponse getRoom(
            @PathVariable String roomId
    ) {
        return chatRoomService.getRoom(roomId);
    }

    @GetMapping("/{roomId}/messages")
    public List<ChatMessageResponse> getMessages(
            @PathVariable String roomId
    ) {
        return chatRoomService.getMessages(roomId);
    }

    @PatchMapping("/{roomId}/messages/{messageId}")
    public List<ChatMessageResponse> editUserMessage(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestBody ChatMessageEditRequest request
    ) {
        return chatRoomService.editUserMessage(
                roomId,
                messageId,
                request
        );
    }

    @PatchMapping("/{roomId}/title")
    public ChatRoomResponse updateTitle(
            @PathVariable String roomId,
            @RequestBody ChatRoomTitleUpdateRequest request
    ) {
        return chatRoomService.updateTitle(
                roomId,
                request
        );
    }

    @PatchMapping("/{roomId}/pinned")
    public ChatRoomResponse updatePinned(
            @PathVariable String roomId,
            @RequestBody ChatRoomPinnedUpdateRequest request
    ) {
        return chatRoomService.updatePinned(
                roomId,
                request
        );
    }

    @DeleteMapping("/{roomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRoom(
            @PathVariable String roomId
    ) {
        chatRoomService.deleteRoom(roomId);
    }
}
