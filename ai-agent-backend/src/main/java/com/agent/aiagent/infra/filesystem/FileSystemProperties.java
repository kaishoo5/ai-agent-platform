package com.agent.aiagent.infra.filesystem;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.filesystem")
public class FileSystemProperties {

    private String rootDirectory;

    public String getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(
            String rootDirectory
    ) {
        this.rootDirectory =
                rootDirectory;
    }
}