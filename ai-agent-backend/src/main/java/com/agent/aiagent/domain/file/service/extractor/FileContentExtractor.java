package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;

public interface FileContentExtractor {

    boolean supports(String extension);

    String extract(ChatFile chatFile);

}