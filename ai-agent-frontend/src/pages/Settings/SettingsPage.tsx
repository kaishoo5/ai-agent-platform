import {useCallback, useEffect, useMemo, useState} from "react";

import {
    deleteAgentMemory,
    deleteAllAgentMemories,
    getAgentMemories,
    getAgentMemoryEnabled,
    getRuntimeSettings,
    updateAgentMemory,
    updateAgentMemoryEnabled,
    updateModelSettings,
} from "../../api/settingsApi";
import type {AgentMemory, RuntimeSettings} from "../../types/settings";

function SettingsPage() {
    const [settings, setSettings] =
        useState<RuntimeSettings | null>(null);

    const [textModel, setTextModel] =
        useState("");

    const [visionModel, setVisionModel] =
        useState("");

    const [embeddingModel, setEmbeddingModel] =
        useState("");

    const [isLoading, setIsLoading] =
        useState(true);

    const [isSaving, setIsSaving] =
        useState(false);

    const [errorMessage, setErrorMessage] =
        useState<string | null>(null);

    const [savedMessage, setSavedMessage] =
        useState<string | null>(null);

    const [memories, setMemories] =
        useState<AgentMemory[]>([]);

    const [memoryEnabled, setMemoryEnabled] =
        useState(true);

    const [isMemoryLoading, setIsMemoryLoading] =
        useState(true);

    const [isMemorySaving, setIsMemorySaving] =
        useState(false);

    const [editingMemoryId, setEditingMemoryId] =
        useState<string | null>(null);

    const [editingCategory, setEditingCategory] =
        useState("");

    const [editingContent, setEditingContent] =
        useState("");

    const [deleteConfirmId, setDeleteConfirmId] =
        useState<string | null>(null);

    const [isDeleteAllConfirming, setIsDeleteAllConfirming] =
        useState(false);

    const [memoryMessage, setMemoryMessage] =
        useState<string | null>(null);

    const [memoryError, setMemoryError] =
        useState<string | null>(null);

    const applySettings = (
        response: RuntimeSettings,
    ): void => {
        setSettings(response);
        setTextModel(response.textModel);
        setVisionModel(response.visionModel);
        setEmbeddingModel(response.embeddingModel);
    };

    const loadSettings = useCallback(
        async (): Promise<void> => {
            setIsLoading(true);
            setErrorMessage(null);
            setSavedMessage(null);

            try {
                const response =
                    await getRuntimeSettings();

                applySettings(response);
            } catch (error) {
                console.error(
                    "Runtime 설정 조회 중 오류가 발생했습니다.",
                    error,
                );

                setSettings(null);
                setErrorMessage(
                    "백엔드에 연결할 수 없습니다.",
                );
            } finally {
                setIsLoading(false);
            }
        },
        [],
    );

    useEffect(() => {
        let isMounted = true;

        getRuntimeSettings()
            .then((response) => {
                if (!isMounted) {
                    return;
                }

                applySettings(
                    response,
                );
            })
            .catch((error) => {
                if (!isMounted) {
                    return;
                }

                console.error(
                    "Runtime 설정 조회 중 오류가 발생했습니다.",
                    error,
                );

                setSettings(
                    null,
                );

                setErrorMessage(
                    "백엔드에 연결할 수 없습니다.",
                );
            })
            .finally(() => {
                if (!isMounted) {
                    return;
                }

                setIsLoading(
                    false,
                );
            });

        return () => {
            isMounted = false;
        };
    }, []);

    const loadMemories = useCallback(
        async (): Promise<void> => {
            setIsMemoryLoading(true);
            setMemoryError(null);

            try {
                const [
                    memoryList,
                    enabled,
                ] = await Promise.all([
                    getAgentMemories(),
                    getAgentMemoryEnabled(),
                ]);

                setMemories(memoryList);
                setMemoryEnabled(enabled);
            } catch (error) {
                console.error(
                    "Agent Memory 조회 중 오류가 발생했습니다.",
                    error,
                );

                setMemoryError(
                    "Agent Memory를 불러오지 못했습니다.",
                );
            } finally {
                setIsMemoryLoading(false);
            }
        },
        [],
    );

    useEffect(() => {
        void loadMemories();
    }, [loadMemories]);

    const handleMemoryEnabledChange = async (
        enabled: boolean,
    ): Promise<void> => {
        if (isMemorySaving) {
            return;
        }

        const previousEnabled =
            memoryEnabled;

        setMemoryEnabled(enabled);
        setIsMemorySaving(true);
        setMemoryError(null);
        setMemoryMessage(null);

        try {
            const updatedEnabled =
                await updateAgentMemoryEnabled(
                    enabled,
                );

            setMemoryEnabled(
                updatedEnabled,
            );

            setMemoryMessage(
                updatedEnabled
                    ? "Agent Memory를 활성화했습니다."
                    : "Agent Memory를 비활성화했습니다.",
            );
        } catch (error) {
            console.error(
                "Agent Memory 설정 변경 중 오류가 발생했습니다.",
                error,
            );

            setMemoryEnabled(
                previousEnabled,
            );

            setMemoryError(
                "Agent Memory 설정을 변경하지 못했습니다.",
            );
        } finally {
            setIsMemorySaving(false);
        }
    };

    const startMemoryEdit = (
        memory: AgentMemory,
    ): void => {
        setEditingMemoryId(
            memory.id,
        );

        setEditingCategory(
            memory.category,
        );

        setEditingContent(
            memory.content,
        );

        setDeleteConfirmId(null);
        setIsDeleteAllConfirming(false);
        setMemoryMessage(null);
        setMemoryError(null);
    };

    const cancelMemoryEdit = (): void => {
        setEditingMemoryId(null);
        setEditingCategory("");
        setEditingContent("");
    };

    const handleMemoryUpdate = async (): Promise<void> => {
        if (
            !editingMemoryId
            || !editingCategory.trim()
            || !editingContent.trim()
            || isMemorySaving
        ) {
            return;
        }

        setIsMemorySaving(true);
        setMemoryMessage(null);
        setMemoryError(null);

        try {
            const updatedMemory =
                await updateAgentMemory(
                    editingMemoryId,
                    {
                        category:
                            editingCategory.trim(),
                        content:
                            editingContent.trim(),
                    },
                );

            setMemories((currentMemories) =>
                currentMemories.map((memory) =>
                    memory.id === updatedMemory.id
                        ? updatedMemory
                        : memory
                ),
            );

            cancelMemoryEdit();

            setMemoryMessage(
                "메모리를 수정했습니다.",
            );
        } catch (error) {
            console.error(
                "Agent Memory 수정 중 오류가 발생했습니다.",
                error,
            );

            setMemoryError(
                "메모리를 수정하지 못했습니다.",
            );
        } finally {
            setIsMemorySaving(false);
        }
    };

    const handleMemoryDelete = async (
        memoryId: string,
    ): Promise<void> => {
        if (isMemorySaving) {
            return;
        }

        if (deleteConfirmId !== memoryId) {
            setDeleteConfirmId(
                memoryId,
            );
            setIsDeleteAllConfirming(false);

            return;
        }

        setIsMemorySaving(true);
        setMemoryMessage(null);
        setMemoryError(null);

        try {
            await deleteAgentMemory(
                memoryId,
            );

            setMemories((currentMemories) =>
                currentMemories.filter(
                    (memory) =>
                        memory.id !== memoryId,
                ),
            );

            setDeleteConfirmId(null);

            if (editingMemoryId === memoryId) {
                cancelMemoryEdit();
            }

            setMemoryMessage(
                "메모리를 삭제했습니다.",
            );
        } catch (error) {
            console.error(
                "Agent Memory 삭제 중 오류가 발생했습니다.",
                error,
            );

            setMemoryError(
                "메모리를 삭제하지 못했습니다.",
            );
        } finally {
            setIsMemorySaving(false);
        }
    };

    const handleDeleteAllMemories = async (): Promise<void> => {
        if (
            memories.length === 0
            || isMemorySaving
        ) {
            return;
        }

        if (!isDeleteAllConfirming) {
            setIsDeleteAllConfirming(true);
            setDeleteConfirmId(null);

            return;
        }

        setIsMemorySaving(true);
        setMemoryMessage(null);
        setMemoryError(null);

        try {
            await deleteAllAgentMemories();

            setMemories([]);
            setIsDeleteAllConfirming(false);
            cancelMemoryEdit();

            setMemoryMessage(
                "저장된 메모리를 모두 삭제했습니다.",
            );
        } catch (error) {
            console.error(
                "Agent Memory 전체 삭제 중 오류가 발생했습니다.",
                error,
            );

            setMemoryError(
                "메모리를 전체 삭제하지 못했습니다.",
            );
        } finally {
            setIsMemorySaving(false);
        }
    };

    const hasChanges = useMemo(
        () =>
            settings !== null
            && (
                textModel !== settings.textModel
                || visionModel !== settings.visionModel
                || embeddingModel !== settings.embeddingModel
            ),
        [
            embeddingModel,
            settings,
            textModel,
            visionModel,
        ],
    );

    const handleSave = async (): Promise<void> => {
        if (
            !settings
            || !hasChanges
            || isSaving
        ) {
            return;
        }

        setIsSaving(true);
        setErrorMessage(null);
        setSavedMessage(null);

        try {
            const response =
                await updateModelSettings({
                    textModel,
                    visionModel,
                    embeddingModel,
                });

            applySettings(response);

            setSavedMessage(
                "모델 설정을 저장했습니다.",
            );
        } catch (error) {
            console.error(
                "모델 설정 저장 중 오류가 발생했습니다.",
                error,
            );

            setErrorMessage(
                "모델 설정을 저장하지 못했습니다.",
            );
        } finally {
            setIsSaving(false);
        }
    };

    const installedModels =
        settings?.installedModels ?? [];

    return (
        <section className="settings-page">
            <header className="settings-header">
                <div>
                    <h2>
                        Settings
                    </h2>

                    <p>
                        현재 AI Agent의 실행 상태와 모델 구성을 관리합니다.
                    </p>
                </div>

                <button
                    type="button"
                    className="settings-refresh-button"
                    disabled={isLoading || isSaving}
                    onClick={() => {
                        void loadSettings();
                        void loadMemories();
                    }}
                >
                    {isLoading
                        ? "확인 중..."
                        : "새로고침"}
                </button>
            </header>

            {errorMessage && !settings && (
                <div className="settings-error-card">
                    <div>
                        <strong>
                            Backend Offline
                        </strong>

                        <span>
                            {errorMessage}
                        </span>
                    </div>

                    <button
                        type="button"
                        onClick={() => {
                            void loadSettings();
                        }}
                    >
                        다시 시도
                    </button>
                </div>
            )}

            {settings && (
                <div className="settings-content">
                    <section className="settings-section">
                        <div className="settings-section-heading">
                            <div>
                                <h3>
                                    Runtime Status
                                </h3>

                                <p>
                                    백엔드와 Ollama의 현재 연결 상태입니다.
                                </p>
                            </div>
                        </div>

                        <div className="settings-status-grid">
                            <div className="settings-status-card">
                                <div className="settings-status-card-heading">
                                    <span>
                                        Backend
                                    </span>

                                    <span className="settings-status-badge online">
                                        <i />
                                        Online
                                    </span>
                                </div>

                                <strong>
                                    AI Agent API
                                </strong>

                                <p>
                                    Spring Boot backend
                                </p>
                            </div>

                            <div className="settings-status-card">
                                <div className="settings-status-card-heading">
                                    <span>
                                        Ollama
                                    </span>

                                    <span
                                        className={`settings-status-badge ${settings.ollamaStatus === "UP" ? "online" : "offline"}`}
                                    >
                                        <i />
                                        {settings.ollamaStatus === "UP"
                                            ? "Online"
                                            : "Offline"}
                                    </span>
                                </div>

                                <strong>
                                    Local Model Server
                                </strong>

                                <p>
                                    {settings.ollamaEndpoint}
                                </p>
                            </div>
                        </div>
                    </section>

                    <section className="settings-section">
                        <div className="settings-section-heading">
                            <div>
                                <h3>
                                    Model Configuration
                                </h3>

                                <p>
                                    이후 요청부터 사용할 Ollama 모델을 선택합니다.
                                </p>
                            </div>

                            <button
                                type="button"
                                className="settings-save-button"
                                disabled={
                                    !hasChanges
                                    || isSaving
                                    || settings.ollamaStatus !== "UP"
                                }
                                onClick={() => {
                                    void handleSave();
                                }}
                            >
                                {isSaving
                                    ? "저장 중..."
                                    : "변경사항 저장"}
                            </button>
                        </div>

                        <div className="settings-model-form">
                            <label className="settings-model-field">
                                <span>
                                    Text model
                                </span>

                                <select
                                    value={textModel}
                                    disabled={
                                        isSaving
                                        || settings.ollamaStatus !== "UP"
                                    }
                                    onChange={(event) => {
                                        setTextModel(
                                            event.target.value,
                                        );
                                        setSavedMessage(null);
                                    }}
                                >
                                    {installedModels.map((model) => (
                                        <option
                                            key={model}
                                            value={model}
                                        >
                                            {model}
                                        </option>
                                    ))}
                                </select>
                            </label>

                            <label className="settings-model-field">
                                <span>
                                    Vision model
                                </span>

                                <select
                                    value={visionModel}
                                    disabled={
                                        isSaving
                                        || settings.ollamaStatus !== "UP"
                                    }
                                    onChange={(event) => {
                                        setVisionModel(
                                            event.target.value,
                                        );
                                        setSavedMessage(null);
                                    }}
                                >
                                    {installedModels.map((model) => (
                                        <option
                                            key={model}
                                            value={model}
                                        >
                                            {model}
                                        </option>
                                    ))}
                                </select>
                            </label>

                            <label className="settings-model-field">
                                <span>
                                    Embedding model
                                </span>

                                <select
                                    value={embeddingModel}
                                    disabled={
                                        isSaving
                                        || settings.ollamaStatus !== "UP"
                                    }
                                    onChange={(event) => {
                                        setEmbeddingModel(
                                            event.target.value,
                                        );
                                        setSavedMessage(null);
                                    }}
                                >
                                    {installedModels.map((model) => (
                                        <option
                                            key={model}
                                            value={model}
                                        >
                                            {model}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        </div>

                        {savedMessage && (
                            <div className="settings-save-message success">
                                {savedMessage}
                            </div>
                        )}

                        {errorMessage && (
                            <div className="settings-save-message error">
                                {errorMessage}
                            </div>
                        )}
                    </section>

                    <section className="settings-section">
                        <div className="settings-section-heading">
                            <div>
                                <h3>
                                    Agent Memory
                                </h3>

                                <p>
                                    채팅방을 넘어 재사용되는 장기 메모리를 관리합니다.
                                </p>
                            </div>

                            <label className="settings-memory-toggle">
                                <input
                                    type="checkbox"
                                    checked={memoryEnabled}
                                    disabled={isMemorySaving}
                                    onChange={(event) => {
                                        void handleMemoryEnabledChange(
                                            event.target.checked,
                                        );
                                    }}
                                />

                                <span className="settings-memory-toggle-track">
                                    <span />
                                </span>

                                <strong>
                                    {memoryEnabled
                                        ? "ON"
                                        : "OFF"}
                                </strong>
                            </label>
                        </div>

                        <div className="settings-memory-toolbar">
                            <span>
                                저장된 메모리 {memories.length}개
                            </span>

                            <button
                                type="button"
                                className={`settings-memory-delete-all ${isDeleteAllConfirming ? "confirming" : ""}`}
                                disabled={
                                    memories.length === 0
                                    || isMemorySaving
                                }
                                onClick={() => {
                                    void handleDeleteAllMemories();
                                }}
                            >
                                {isDeleteAllConfirming
                                    ? "한 번 더 눌러 전체 삭제"
                                    : "전체 삭제"}
                            </button>
                        </div>

                        {!memoryEnabled && (
                            <div className="settings-memory-disabled-notice">
                                Memory가 꺼져 있습니다. 기존 메모리는 유지되지만 검색과 자동 저장은 수행하지 않습니다.
                            </div>
                        )}

                        {isMemoryLoading ? (
                            <div className="settings-memory-empty">
                                메모리를 불러오는 중입니다.
                            </div>
                        ) : memories.length === 0 ? (
                            <div className="settings-memory-empty">
                                저장된 장기 메모리가 없습니다.
                            </div>
                        ) : (
                            <div className="settings-memory-list">
                                {memories.map((memory) => {
                                    const isEditing =
                                        editingMemoryId === memory.id;

                                    const isDeleting =
                                        deleteConfirmId === memory.id;

                                    return (
                                        <article
                                            key={memory.id}
                                            className="settings-memory-item"
                                        >
                                            {isEditing ? (
                                                <div className="settings-memory-edit">
                                                    <input
                                                        type="text"
                                                        value={editingCategory}
                                                        maxLength={50}
                                                        disabled={isMemorySaving}
                                                        aria-label="메모리 카테고리"
                                                        onChange={(event) => {
                                                            setEditingCategory(
                                                                event.target.value,
                                                            );
                                                        }}
                                                    />

                                                    <textarea
                                                        value={editingContent}
                                                        maxLength={1000}
                                                        disabled={isMemorySaving}
                                                        aria-label="메모리 내용"
                                                        onChange={(event) => {
                                                            setEditingContent(
                                                                event.target.value,
                                                            );
                                                        }}
                                                    />

                                                    <div className="settings-memory-edit-actions">
                                                        <button
                                                            type="button"
                                                            disabled={isMemorySaving}
                                                            onClick={cancelMemoryEdit}
                                                        >
                                                            취소
                                                        </button>

                                                        <button
                                                            type="button"
                                                            className="primary"
                                                            disabled={
                                                                isMemorySaving
                                                                || !editingCategory.trim()
                                                                || !editingContent.trim()
                                                            }
                                                            onClick={() => {
                                                                void handleMemoryUpdate();
                                                            }}
                                                        >
                                                            저장
                                                        </button>
                                                    </div>
                                                </div>
                                            ) : (
                                                <>
                                                    <div className="settings-memory-item-content">
                                                        <div className="settings-memory-item-meta">
                                                            <span>
                                                                {memory.category}
                                                            </span>

                                                            <time>
                                                                {new Date(
                                                                    memory.updatedAt,
                                                                ).toLocaleString()}
                                                            </time>
                                                        </div>

                                                        <p>
                                                            {memory.content}
                                                        </p>
                                                    </div>

                                                    <div className="settings-memory-item-actions">
                                                        <button
                                                            type="button"
                                                            disabled={isMemorySaving}
                                                            onClick={() => {
                                                                startMemoryEdit(
                                                                    memory,
                                                                );
                                                            }}
                                                        >
                                                            수정
                                                        </button>

                                                        <button
                                                            type="button"
                                                            className={isDeleting ? "danger confirming" : "danger"}
                                                            disabled={isMemorySaving}
                                                            onClick={() => {
                                                                void handleMemoryDelete(
                                                                    memory.id,
                                                                );
                                                            }}
                                                        >
                                                            {isDeleting
                                                                ? "삭제 확인"
                                                                : "삭제"}
                                                        </button>
                                                    </div>
                                                </>
                                            )}
                                        </article>
                                    );
                                })}
                            </div>
                        )}

                        {memoryMessage && (
                            <div className="settings-save-message success">
                                {memoryMessage}
                            </div>
                        )}

                        {memoryError && (
                            <div className="settings-save-message error">
                                {memoryError}
                            </div>
                        )}
                    </section>

                    <section className="settings-section">
                        <div className="settings-section-heading">
                            <div>
                                <h3>
                                    Installed Models
                                </h3>

                                <p>
                                    Ollama에서 현재 확인되는 로컬 모델 목록입니다.
                                </p>
                            </div>

                            <span className="settings-model-count">
                                {installedModels.length}
                            </span>
                        </div>

                        <div className="settings-installed-models">
                            {installedModels.map((model) => (
                                <span
                                    key={model}
                                    className="settings-installed-model"
                                >
                                    {model}
                                </span>
                            ))}
                        </div>
                    </section>
                </div>
            )}
        </section>
    );
}

export default SettingsPage;
