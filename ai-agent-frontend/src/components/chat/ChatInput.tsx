import {type ChangeEvent, type FormEvent, type KeyboardEvent, useEffect, useRef, useState,} from "react";

import {uploadChatFile} from "../../api/chatApi";
import {streamChat} from "../../services/chatStreamService";
import {useChatStore} from "../../store/chatStore";
import type {ChatMessage} from "../../types/chat";

const MAX_FILE_COUNT = 5;

const MAX_FILE_SIZE =
    10 * 1024 * 1024;

const MAX_VIDEO_FILE_SIZE =
    4 * 1024 * 1024 * 1024;

const VIDEO_EXTENSIONS = new Set([
    "mp4",
]);

const IMAGE_EXTENSIONS = new Set([
    "png",
    "jpg",
    "jpeg",
    "gif",
    "webp",
]);

const ALLOWED_FILE_EXTENSIONS = new Set([
    "txt",
    "md",
    "java",
    "js",
    "ts",
    "tsx",
    "json",
    "sql",
    "xml",
    "yaml",
    "yml",
    "properties",
    "pdf",
    "docx",
    "xlsx",
    "png",
    "jpg",
    "jpeg",
    "gif",
    "webp",
    "mp4",
]);

function getFileExtension(
    fileName: string,
): string {
    const extensionIndex =
        fileName.lastIndexOf(".");

    if (
        extensionIndex < 0
        || extensionIndex === fileName.length - 1
    ) {
        return "";
    }

    return fileName
        .substring(extensionIndex + 1)
        .toLowerCase();
}

function formatFileSize(
    size: number,
): string {
    if (size < 1024) {
        return `${size} B`;
    }

    if (size < 1024 * 1024) {
        return `${(
            size / 1024
        ).toFixed(1)} KB`;
    }

    if (size < 1024 * 1024 * 1024) {
        return `${(
            size
            / 1024
            / 1024
        ).toFixed(1)} MB`;
    }

    return `${(
        size
        / 1024
        / 1024
        / 1024
    ).toFixed(2)} GB`;
}

function isImageFile(
    fileName: string,
): boolean {
    return IMAGE_EXTENSIONS.has(
        getFileExtension(fileName),
    );
}

function getFileLabel(
    fileName: string,
): string {
    const extension =
        getFileExtension(fileName);

    if (!extension) {
        return "FILE";
    }

    return extension.toUpperCase();
}

type SelectedFilePreviewProps = {
    file: File;
};

function SelectedFilePreview({
                                 file,
                             }: SelectedFilePreviewProps) {
    const [previewUrl, setPreviewUrl] =
        useState<string | null>(null);

    useEffect(() => {
        if (!isImageFile(file.name)) {
            setPreviewUrl(null);

            return;
        }

        const objectUrl =
            URL.createObjectURL(file);

        setPreviewUrl(
            objectUrl,
        );

        return () => {
            URL.revokeObjectURL(
                objectUrl,
            );
        };
    }, [
        file,
    ]);

    if (!previewUrl) {
        return null;
    }

    return (
        <img
            className="selected-file-preview"
            src={previewUrl}
            alt={file.name}
        />
    );
}

function ChatInput() {
    const [input, setInput] =
        useState("");

    const [selectedFiles, setSelectedFiles] =
        useState<File[]>([]);

    const [fileError, setFileError] =
        useState<string | null>(null);

    const [deletingFileIds, setDeletingFileIds] =
        useState<Set<string>>(
            new Set(),
        );

    const inputRef =
        useRef<HTMLTextAreaElement | null>(null);

    const fileInputRef =
        useRef<HTMLInputElement | null>(null);

    const refreshRooms = useChatStore(
        (state) => state.refreshRooms,
    );

    const activeRoomId = useChatStore(
        (state) => state.activeRoomId,
    );

    const activeRoom = useChatStore(
        (state) => state.rooms.find(
            (room) =>
                room.id === state.activeRoomId,
        ),
    );

    const activeRoomFiles =
        activeRoom?.files ?? [];

    const createRoom = useChatStore(
        (state) => state.createRoom,
    );

    const addMessage = useChatStore(
        (state) => state.addMessage,
    );

    const appendMessageContent = useChatStore(
        (state) => state.appendMessageContent,
    );

    const updateMessageContent = useChatStore(
        (state) => state.updateMessageContent,
    );

    const setActiveRoom = useChatStore(
        (state) => state.setActiveRoom,
    );

    const isGenerating = useChatStore(
        (state) => state.isGenerating,
    );

    const startGenerating = useChatStore(
        (state) => state.startGenerating,
    );

    const finishGenerating = useChatStore(
        (state) => state.finishGenerating,
    );

    const stopGenerating = useChatStore(
        (state) => state.stopGenerating,
    );

    const deleteFile = useChatStore(
        (state) => state.deleteFile,
    );

    const updateMessageVideoResult = useChatStore(
        (state) => state.updateMessageVideoResult,
    );

    useEffect(() => {
        if (isGenerating) {
            return;
        }

        requestAnimationFrame(() => {
            inputRef.current?.focus();
        });
    }, [
        isGenerating,
        activeRoomId,
    ]);

    const handleChange = (
        event: ChangeEvent<HTMLTextAreaElement>,
    ): void => {
        setInput(
            event.target.value,
        );

        const textarea =
            event.target;

        textarea.style.height =
            "auto";

        textarea.style.height =
            `${Math.min(
                textarea.scrollHeight,
                180,
            )}px`;
    };

    const handleFileChange = (
        event: ChangeEvent<HTMLInputElement>,
    ): void => {
        const files =
            Array.from(
                event.target.files ?? [],
            );

        event.target.value = "";

        if (files.length === 0) {
            return;
        }

        setFileError(null);

        const remainingFileCount =
            MAX_FILE_COUNT
            - selectedFiles.length;

        if (remainingFileCount <= 0) {
            setFileError(
                "파일은 최대 5개까지 첨부할 수 있습니다.",
            );

            return;
        }

        const acceptedFiles: File[] = [];

        let errorMessage: string | null =
            null;

        for (const file of files) {
            if (
                acceptedFiles.length
                >= remainingFileCount
            ) {
                errorMessage =
                    "파일은 최대 5개까지 첨부할 수 있습니다.";

                break;
            }

            const extension =
                getFileExtension(
                    file.name,
                );

            if (
                !ALLOWED_FILE_EXTENSIONS.has(
                    extension,
                )
            ) {
                errorMessage =
                    `지원하지 않는 파일 형식입니다: ${file.name}`;

                continue;
            }

            const maxFileSize =
                VIDEO_EXTENSIONS.has(extension)
                    ? MAX_VIDEO_FILE_SIZE
                    : MAX_FILE_SIZE;

            if (file.size > maxFileSize) {
                errorMessage =
                    VIDEO_EXTENSIONS.has(extension)
                        ? `영상 파일 크기는 4GB를 초과할 수 없습니다: ${file.name}`
                        : `파일 크기는 10MB를 초과할 수 없습니다: ${file.name}`;

                continue;
            }

            const isDuplicated =
                selectedFiles.some(
                    (selectedFile) =>
                        selectedFile.name === file.name
                        && selectedFile.size === file.size
                        && selectedFile.lastModified
                        === file.lastModified,
                )
                || acceptedFiles.some(
                    (acceptedFile) =>
                        acceptedFile.name === file.name
                        && acceptedFile.size === file.size
                        && acceptedFile.lastModified
                        === file.lastModified,
                );

            if (isDuplicated) {
                errorMessage =
                    `이미 선택한 파일입니다: ${file.name}`;

                continue;
            }

            acceptedFiles.push(
                file,
            );
        }

        if (errorMessage) {
            setFileError(
                errorMessage,
            );
        }

        if (acceptedFiles.length === 0) {
            return;
        }

        setSelectedFiles(
            (currentFiles) => [
                ...currentFiles,
                ...acceptedFiles,
            ],
        );
    };

    const handleFileButtonClick = (): void => {
        if (isGenerating) {
            return;
        }

        fileInputRef.current?.click();
    };

    const handleRemoveFile = (
        targetIndex: number,
    ): void => {
        if (isGenerating) {
            return;
        }

        setSelectedFiles(
            (currentFiles) =>
                currentFiles.filter(
                    (_, index) =>
                        index !== targetIndex,
                ),
        );

        setFileError(null);
    };

    const handleDeleteUploadedFile = async (
        fileId: string,
    ): Promise<void> => {
        if (
            !activeRoomId
            || isGenerating
            || deletingFileIds.has(fileId)
        ) {
            return;
        }

        setFileError(null);

        setDeletingFileIds(
            (currentIds) => {
                const nextIds =
                    new Set(
                        currentIds,
                    );

                nextIds.add(
                    fileId,
                );

                return nextIds;
            },
        );

        try {
            await deleteFile(
                activeRoomId,
                fileId,
            );
        } catch (error) {
            console.error(
                "업로드된 파일을 삭제하지 못했습니다.",
                error,
            );

            setFileError(
                "파일 삭제 중 오류가 발생했습니다.",
            );
        } finally {
            setDeletingFileIds(
                (currentIds) => {
                    const nextIds =
                        new Set(
                            currentIds,
                        );

                    nextIds.delete(
                        fileId,
                    );

                    return nextIds;
                },
            );
        }
    };

    const sendMessage = async (): Promise<void> => {
        const trimmedInput =
            input.trim();

        if (
            !trimmedInput
            || isGenerating
        ) {
            return;
        }

        let targetRoomId =
            activeRoomId;

        if (!targetRoomId) {
            targetRoomId =
                await createRoom();
        }

        const targetRoom = useChatStore
            .getState()
            .rooms
            .find(
                (room) =>
                    room.id === targetRoomId,
            );

        const currentMessages =
            targetRoom?.messages ?? [];

        const userMessage: ChatMessage = {
            id: crypto.randomUUID(),
            roomId: targetRoomId,
            role: "USER",
            content: trimmedInput,
            videoResult: null,
            createdAt: new Date().toISOString(),
        };

        const assistantMessage: ChatMessage = {
            id: crypto.randomUUID(),
            roomId: targetRoomId,
            role: "ASSISTANT",
            content: "",
            videoResult: null,
            createdAt: new Date().toISOString(),
        };

        const requestMessages: ChatMessage[] = [
            ...currentMessages,
            userMessage,
        ];

        const filesToUpload = [
            ...selectedFiles,
        ];

        addMessage(
            targetRoomId,
            userMessage,
        );

        addMessage(
            targetRoomId,
            assistantMessage,
        );

        setInput("");

        if (inputRef.current) {
            inputRef.current.style.height =
                "auto";
        }

        const abortController =
            new AbortController();

        startGenerating(
            abortController,
        );

        try {
            const uploadedFiles =
                await Promise.all(
                    filesToUpload.map(
                        (file) =>
                            uploadChatFile(
                                targetRoomId,
                                file,
                            ),
                    ),
                );

            const fileIds =
                uploadedFiles.map(
                    (uploadedFile) =>
                        uploadedFile.id,
                );

            await streamChat(
                targetRoomId,
                requestMessages,
                {
                    onChunk: (chunk) => {
                        appendMessageContent(
                            targetRoomId,
                            assistantMessage.id,
                            chunk,
                        );
                    },

                    onVideoResult: (videoResult) => {
                        updateMessageVideoResult(
                            targetRoomId,
                            assistantMessage.id,
                            videoResult,
                        );
                    },
                },
                abortController.signal,
                false,
                fileIds,
            );

            setSelectedFiles([]);
            setFileError(null);

            await refreshRooms();

            await setActiveRoom(
                targetRoomId,
            );
        } catch (error) {
            if (
                error instanceof DOMException
                && error.name === "AbortError"
            ) {
                console.log(
                    "사용자가 AI 응답 생성을 중지했습니다.",
                );

                const currentRoom = useChatStore
                    .getState()
                    .rooms
                    .find(
                        (room) =>
                            room.id === targetRoomId,
                    );

                const currentAssistantMessage =
                    currentRoom?.messages.find(
                        (message) =>
                            message.id
                            === assistantMessage.id,
                    );

                if (
                    !currentAssistantMessage
                    || currentAssistantMessage
                        .content
                        .length === 0
                ) {
                    updateMessageContent(
                        targetRoomId,
                        assistantMessage.id,
                        "응답이 중단되었습니다.",
                    );
                }

                return;
            }

            console.error(
                "파일 업로드 또는 AI 응답 생성 중 오류가 발생했습니다.",
                error,
            );

            updateMessageContent(
                targetRoomId,
                assistantMessage.id,
                "파일 업로드 또는 응답 생성 중 오류가 발생했습니다.",
            );
        } finally {
            finishGenerating();
        }
    };

    const handleStop = (): void => {
        stopGenerating();
    };

    const handleSubmit = (
        event: FormEvent<HTMLFormElement>,
    ): void => {
        event.preventDefault();

        void sendMessage();
    };

    const handleKeyDown = (
        event: KeyboardEvent<HTMLTextAreaElement>,
    ): void => {
        if (
            event.key === "Enter"
            && !event.shiftKey
        ) {
            event.preventDefault();

            void sendMessage();
        }
    };

    return (
        <form
            className="chat-input-area"
            onSubmit={handleSubmit}
        >
            <input
                ref={fileInputRef}
                type="file"
                className="file-input-hidden"
                multiple
                accept=".txt,.md,.java,.js,.ts,.tsx,.json,.sql,.xml,.yaml,.yml,.properties,.pdf,.docx,.xlsx,.png,.jpg,.jpeg,.gif,.webp,.mp4"
                disabled={isGenerating}
                onChange={handleFileChange}
            />

            <div className="chat-composer">
                {activeRoomFiles.length > 0 && (
                    <div className="composer-file-section">
                        <div className="composer-file-section-header">
                            <span>Workspace files</span>

                            <span>
                                {activeRoomFiles.length}
                            </span>
                        </div>

                        <div className="composer-file-list">
                            {activeRoomFiles.map((file) => {
                                const isDeleting =
                                    deletingFileIds.has(
                                        file.id,
                                    );

                                return (
                                    <div
                                        key={file.id}
                                        className="composer-file-item uploaded"
                                    >
                                        <div className="composer-file-type">
                                            {file.extension.toUpperCase()}
                                        </div>

                                        <div className="composer-file-info">
                                            <span className="composer-file-name">
                                                {file.originalName}
                                            </span>

                                            <span className="composer-file-size">
                                                {formatFileSize(
                                                    file.size,
                                                )}
                                            </span>
                                        </div>

                                        <button
                                            type="button"
                                            className="composer-file-remove"
                                            disabled={
                                                isGenerating
                                                || isDeleting
                                            }
                                            onClick={() => {
                                                void handleDeleteUploadedFile(
                                                    file.id,
                                                );
                                            }}
                                            aria-label={`${file.originalName} 삭제`}
                                            title="채팅방에서 파일 삭제"
                                        >
                                            {isDeleting
                                                ? "..."
                                                : "×"}
                                        </button>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                )}

                {selectedFiles.length > 0 && (
                    <div className="composer-file-section selected">
                        <div className="composer-file-section-header">
                            <span>Attachments</span>

                            <span>
                                {selectedFiles.length}/{MAX_FILE_COUNT}
                            </span>
                        </div>

                        <div className="composer-file-list">
                            {selectedFiles.map((
                                file,
                                index,
                            ) => {
                                const isImage =
                                    isImageFile(
                                        file.name,
                                    );

                                return (
                                    <div
                                        key={`${file.name}-${file.size}-${file.lastModified}`}
                                        className={
                                            isImage
                                                ? "composer-file-item image"
                                                : "composer-file-item"
                                        }
                                    >
                                        {isImage
                                            ? (
                                                <SelectedFilePreview
                                                    file={file}
                                                />
                                            )
                                            : (
                                                <div className="composer-file-type">
                                                    {getFileLabel(
                                                        file.name,
                                                    )}
                                                </div>
                                            )}

                                        <div className="composer-file-info">
                                            <span className="composer-file-name">
                                                {file.name}
                                            </span>

                                            <span className="composer-file-size">
                                                {formatFileSize(
                                                    file.size,
                                                )}
                                            </span>
                                        </div>

                                        <button
                                            type="button"
                                            className="composer-file-remove"
                                            disabled={isGenerating}
                                            onClick={() => {
                                                handleRemoveFile(
                                                    index,
                                                );
                                            }}
                                            aria-label={`${file.name} 삭제`}
                                            title="첨부 파일 삭제"
                                        >
                                            ×
                                        </button>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                )}

                {fileError && (
                    <div className="composer-file-error">
                        <span>!</span>

                        {fileError}
                    </div>
                )}

                <textarea
                    ref={inputRef}
                    className="chat-input"
                    value={input}
                    placeholder={
                        isGenerating
                            ? "AI Agent가 응답을 생성하고 있습니다..."
                            : "AI Agent에게 메시지를 보내세요..."
                    }
                    rows={1}
                    disabled={isGenerating}
                    onChange={handleChange}
                    onKeyDown={handleKeyDown}
                />

                <div className="chat-composer-actions">
                    <div className="chat-composer-actions-left">
                        <button
                            type="button"
                            className="composer-icon-button"
                            disabled={
                                isGenerating
                                || selectedFiles.length
                                >= MAX_FILE_COUNT
                            }
                            onClick={handleFileButtonClick}
                            aria-label="파일 첨부"
                            title="파일 첨부"
                        >
                            +
                        </button>

                        <span className="composer-file-hint">
                            TXT · PDF · DOCX · XLSX · Image · MP4
                        </span>
                    </div>

                    <div className="chat-composer-actions-right">
                        {isGenerating
                            ? (
                                <button
                                    type="button"
                                    className="composer-stop-button"
                                    onClick={handleStop}
                                >
                                    <span className="composer-stop-icon" />

                                    중지
                                </button>
                            )
                            : (
                                <button
                                    type="submit"
                                    className="composer-send-button"
                                    disabled={!input.trim()}
                                    aria-label="메시지 전송"
                                    title="전송"
                                >
                                    ↑
                                </button>
                            )}
                    </div>
                </div>
            </div>

            <div className="chat-composer-footer">
                Enter로 전송 · Shift + Enter로 줄바꿈
            </div>
        </form>
    );
}

export default ChatInput;