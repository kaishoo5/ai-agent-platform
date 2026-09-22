import {useCallback, useEffect, useMemo, useState} from "react";

import {getRuntimeSettings, updateModelSettings,} from "../../api/settingsApi";
import type {RuntimeSettings} from "../../types/settings";

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
