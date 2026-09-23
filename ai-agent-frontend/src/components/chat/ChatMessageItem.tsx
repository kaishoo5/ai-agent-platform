import {useState} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {Prism as SyntaxHighlighter} from "react-syntax-highlighter";
import {vscDarkPlus} from "react-syntax-highlighter/dist/esm/styles/prism";

import type {ChatMessage} from "../../types/chat";
import type {ExecutionStep} from "../../store/chatStore";
import SourceChips from "./SourceChips";
import VideoResultCard from "./VideoResultCard";

interface ChatMessageItemProps {
    message: ChatMessage;
    isLastAssistant: boolean;
    isGenerating: boolean;
    executionSteps: ExecutionStep[];
    onRegenerate: () => void;
    onEdit: (
        content: string,
    ) => Promise<void>;
}

interface CodeBlockProps {
    language: string;
    code: string;
}

function getLanguageLabel(
    language: string,
): string {
    const languageLabels: Record<string, string> = {
        js: "JavaScript",
        javascript: "JavaScript",
        ts: "TypeScript",
        typescript: "TypeScript",
        tsx: "TSX",
        jsx: "JSX",
        java: "Java",
        sql: "SQL",
        json: "JSON",
        html: "HTML",
        css: "CSS",
        xml: "XML",
        bash: "Bash",
        shell: "Shell",
        sh: "Shell",
        yaml: "YAML",
        yml: "YAML",
        python: "Python",
        py: "Python",
        text: "Text",
        plaintext: "Text",
    };

    return languageLabels[language.toLowerCase()]
        ?? language.toUpperCase();
}

function CodeBlock({
                       language,
                       code,
                   }: CodeBlockProps) {
    const [isCopied, setIsCopied] =
        useState(false);

    const handleCopy =
        async (): Promise<void> => {
            try {
                await navigator.clipboard.writeText(
                    code,
                );

                setIsCopied(true);

                window.setTimeout(() => {
                    setIsCopied(false);
                }, 1500);
            } catch (error) {
                console.error(
                    "코드 복사 중 오류가 발생했습니다.",
                    error,
                );
            }
        };

    return (
        <div className="code-block">
            <div className="code-block-header">
                <span className="code-block-language">
                    {getLanguageLabel(
                        language,
                    )}
                </span>

                <button
                    type="button"
                    className="code-copy-button"
                    onClick={() => {
                        void handleCopy();
                    }}
                >
                    {isCopied
                        ? "복사됨"
                        : "복사"}
                </button>
            </div>

            <SyntaxHighlighter
                language={language}
                style={vscDarkPlus}
                PreTag="div"
                customStyle={{
                    margin: 0,
                    padding: "16px",
                    background: "transparent",
                }}
                codeTagProps={{
                    style: {
                        fontFamily:
                            "Consolas, Monaco, monospace",
                    },
                }}
            >
                {code}
            </SyntaxHighlighter>
        </div>
    );
}

function ChatMessageItem({
                             message,
                             isLastAssistant,
                             isGenerating,
                             executionSteps,
                             onRegenerate,
                             onEdit,
                         }: ChatMessageItemProps) {
    const [
        isMessageCopied,
        setIsMessageCopied,
    ] = useState(false);

    const [
        isEditing,
        setIsEditing,
    ] = useState(false);

    const [
        editContent,
        setEditContent,
    ] = useState(
        message.content,
    );

    const [
        isSavingEdit,
        setIsSavingEdit,
    ] = useState(false);

    const isUser =
        message.role === "USER";

    const isLoading =
        !isUser
        && message.content.length === 0
        && (
            !message.videoResult
            || message.videoResult.length === 0
        );

    const hasExecutionSteps =
        executionSteps.length > 0;

    const handleMessageCopy =
        async (): Promise<void> => {
            try {
                await navigator.clipboard.writeText(
                    message.content,
                );

                setIsMessageCopied(true);

                window.setTimeout(() => {
                    setIsMessageCopied(false);
                }, 1500);
            } catch (error) {
                console.error(
                    "메시지 복사 중 오류가 발생했습니다.",
                    error,
                );
            }
        };

    const handleEditStart = (): void => {
        setEditContent(
            message.content,
        );

        setIsEditing(
            true,
        );
    };

    const handleEditCancel = (): void => {
        setEditContent(
            message.content,
        );

        setIsEditing(
            false,
        );
    };

    const handleEditSubmit =
        async (): Promise<void> => {
            const normalizedContent =
                editContent.trim();

            if (
                !normalizedContent
                || normalizedContent === message.content
                || isSavingEdit
            ) {
                if (
                    normalizedContent === message.content
                ) {
                    setIsEditing(
                        false,
                    );
                }

                return;
            }

            setIsSavingEdit(
                true,
            );

            try {
                await onEdit(
                    normalizedContent,
                );

                setIsEditing(
                    false,
                );
            } finally {
                setIsSavingEdit(
                    false,
                );
            }
        };

    return (
        <article
            className={
                isUser
                    ? "message-item user"
                    : "message-item assistant"
            }
        >
            <div className="message-row">
                {!isUser && (
                    <div className="message-avatar assistant-avatar">
                        A
                    </div>
                )}

                <div className="message-body">
                    <div className="message-meta">
                        <span className="message-author">
                            {isUser
                                ? "You"
                                : "AI Agent"}
                        </span>
                    </div>

                    <div className="message-bubble">
                        {isEditing
                            ? (
                                <div className="message-edit">
                                    <textarea
                                        className="message-edit-textarea"
                                        value={editContent}
                                        disabled={isSavingEdit}
                                        autoFocus
                                        rows={4}
                                        onChange={(event) => {
                                            setEditContent(
                                                event.target.value,
                                            );
                                        }}
                                        onKeyDown={(event) => {
                                            if (
                                                event.key === "Escape"
                                            ) {
                                                event.preventDefault();

                                                handleEditCancel();
                                            }

                                            if (
                                                event.key === "Enter"
                                                && (
                                                    event.ctrlKey
                                                    || event.metaKey
                                                )
                                            ) {
                                                event.preventDefault();

                                                void handleEditSubmit();
                                            }
                                        }}
                                    />

                                    <div className="message-edit-actions">
                                        <button
                                            type="button"
                                            className="message-edit-button secondary"
                                            disabled={isSavingEdit}
                                            onClick={
                                                handleEditCancel
                                            }
                                        >
                                            취소
                                        </button>

                                        <button
                                            type="button"
                                            className="message-edit-button primary"
                                            disabled={
                                                isSavingEdit
                                                || !editContent.trim()
                                            }
                                            onClick={() => {
                                                void handleEditSubmit();
                                            }}
                                        >
                                            {isSavingEdit
                                                ? "처리 중..."
                                                : "수정 후 보내기"}
                                        </button>
                                    </div>
                                </div>
                            )
                            : isLoading
                                ? (
                                    hasExecutionSteps
                                        ? (
                                            <div className="agent-execution-steps">
                                                {executionSteps.map(
                                                    (step) => (
                                                        <div
                                                            key={step.id}
                                                            className={
                                                                `agent-execution-step ${step.status}`
                                                            }
                                                        >
                                                            <span className="agent-execution-step-icon">
                                                                {step.status === "running"
                                                                    ? (
                                                                        <span className="agent-execution-spinner" />
                                                                    )
                                                                    : step.status === "completed"
                                                                        ? "✓"
                                                                        : "!"}
                                                            </span>

                                                            <span className="agent-execution-step-message">
                                                                {step.message}
                                                            </span>
                                                        </div>
                                                    ),
                                                )}
                                            </div>
                                        )
                                        : (
                                            <div
                                                className="message-loading"
                                                aria-label="AI가 답변을 생성하고 있습니다."
                                            >
                                                <span />
                                                <span />
                                                <span />
                                            </div>
                                        )
                                )
                                : isUser
                                    ? (
                                        <div className="user-message-text">
                                            {message.content}
                                        </div>
                                    )
                                    : (
                                        <>
                                            {message.content && (
                                                <div className="markdown-content">
                                                    <ReactMarkdown
                                                        remarkPlugins={[
                                                            remarkGfm,
                                                        ]}
                                                        components={{
                                                            code({
                                                                     className,
                                                                     children,
                                                                     ...props
                                                                 }) {
                                                                const languageMatch =
                                                                    /language-(\w+)/.exec(
                                                                        className ?? "",
                                                                    );

                                                                const language =
                                                                    languageMatch?.[1];

                                                                if (!language) {
                                                                    return (
                                                                        <code
                                                                            className={
                                                                                className
                                                                            }
                                                                            {...props}
                                                                        >
                                                                            {children}
                                                                        </code>
                                                                    );
                                                                }

                                                                const code =
                                                                    String(
                                                                        children,
                                                                    ).replace(
                                                                        /\n$/,
                                                                        "",
                                                                    );

                                                                return (
                                                                    <CodeBlock
                                                                        language={
                                                                            language
                                                                        }
                                                                        code={
                                                                            code
                                                                        }
                                                                    />
                                                                );
                                                            },
                                                        }}
                                                    >
                                                        {message.content}
                                                    </ReactMarkdown>
                                                </div>
                                            )}

                                            {message.sources.length > 0 && (
                                                <SourceChips
                                                    sources={
                                                        message.sources
                                                    }
                                                />
                                            )}

                                            {message.videoResult?.map(
                                                (videoResult) => (
                                                    <VideoResultCard
                                                        key={
                                                            videoResult.fileId
                                                            + ":"
                                                            + videoResult.fileName
                                                        }
                                                        videoResult={
                                                            videoResult
                                                        }
                                                    />
                                                ),
                                            )}
                                        </>
                                    )}
                    </div>

                    {!isEditing
                        && !isLoading
                        && (
                            <div className="message-actions">
                                <button
                                    type="button"
                                    className="message-action-button"
                                    onClick={() => {
                                        void handleMessageCopy();
                                    }}
                                >
                                    <span className="message-action-icon">
                                        ⧉
                                    </span>

                                    {isMessageCopied
                                        ? "복사됨"
                                        : "복사"}
                                </button>

                                {isUser
                                    && !isGenerating
                                    && (
                                        <button
                                            type="button"
                                            className="message-action-button"
                                            onClick={
                                                handleEditStart
                                            }
                                        >
                                            <span className="message-action-icon">
                                                ✎
                                            </span>

                                            수정
                                        </button>
                                    )}

                                {!isUser
                                    && isLastAssistant
                                    && !isGenerating
                                    && (
                                        <button
                                            type="button"
                                            className="message-action-button"
                                            onClick={
                                                onRegenerate
                                            }
                                        >
                                            <span className="message-action-icon">
                                                ↻
                                            </span>

                                            다시 생성
                                        </button>
                                    )}
                            </div>
                        )}
                </div>
            </div>
        </article>
    );
}

export default ChatMessageItem;
