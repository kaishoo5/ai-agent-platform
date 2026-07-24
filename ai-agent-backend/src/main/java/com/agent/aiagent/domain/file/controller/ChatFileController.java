package com.agent.aiagent.domain.file.controller;

import com.agent.aiagent.domain.file.dto.ChatFileResponse;
import com.agent.aiagent.domain.file.dto.ChatFileUploadResponse;
import com.agent.aiagent.domain.file.service.ChatFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class ChatFileController {

    private final ChatFileService chatFileService;

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ChatFileUploadResponse upload(
            @RequestParam String roomId,
            @RequestPart MultipartFile file
    ) {
        return chatFileService.upload(
                roomId,
                file
        );
    }

    @GetMapping
    public List<ChatFileResponse> findAll(
            @RequestParam String roomId
    ) {
        return chatFileService.findAllByRoomId(
                roomId
        );
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String fileId,
            @RequestParam String roomId
    ) {
        chatFileService.delete(
                roomId,
                fileId
        );
    }
}