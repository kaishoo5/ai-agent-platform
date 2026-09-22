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
}

interface CodeBlockProps {
    language: string;
    code: string;
}


interface MarkdownNode {
    type: string;
    value?: string;
    children?: MarkdownNode[];
}

function remarkRepairStrongMarkdown() {
    return (tree: MarkdownNode): void => {
        repairStrongMarkdownNodes(
            tree,
        );
    };
}

function repairStrongMarkdownNodes(
    node: MarkdownNode,
): void {
    if (
        !node.children
        || node.type === "code"
        || node.type === "inlineCode"
    ) {
        return;
    }

    const repairedChildren: MarkdownNode[] = [];

    node.children.forEach((child) => {
        if (
            child.type !== "text"
            || !child.value
            || !child.value.includes("**")
        ) {
            repairStrongMarkdownNodes(
                child,
            );

            repairedChildren.push(
                child,
            );

            return;
        }

        const pattern =
            /\*\*([^*\n]+?)\*\*/g;

        let lastIndex = 0;
        let match: RegExpExecArray | null;

        while (
            (match = pattern.exec(child.value)) !== null
            ) {
            if (match.index > lastIndex) {
                repairedChildren.push({
                    type: "text",
                    value: child.value.slice(
                        lastIndex,
                        match.index,
                    ),
                });
            }

            repairedChildren.push({
                type: "strong",
                children: [
                    {
                        type: "text",
                        value: match[1],
                    },
                ],
            });

            lastIndex =
                match.index + match[0].length;
        }

        if (lastIndex === 0) {
            repairedChildren.push(
                child,
            );

            return;
        }

        if (lastIndex < child.value.length) {
            repairedChildren.push({
                type: "text",
                value: child.value.slice(
                    lastIndex,
                ),
            });
        }
    });

    node.children =
        repairedChildren;
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
                         }: ChatMessageItemProps) {
    const [
        isMessageCopied,
        setIsMessageCopied,
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
                    isUser
                        ? "사용자 질문 복사 중 오류가 발생했습니다."
                        : "AI 답변 복사 중 오류가 발생했습니다.",
                    error,
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
                        {isLoading
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
                                                        remarkRepairStrongMarkdown,
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

                    {!isLoading
                        && (
                            <div className="message-actions">
                                <button
                                    type="button"
                                    className="message-action-button"
                                    onClick={() => {
                                        void handleMessageCopy();
                                    }}
                                    aria-label={
                                        isMessageCopied
                                            ? isUser
                                                ? "질문 복사됨"
                                                : "답변 복사됨"
                                            : isUser
                                                ? "질문 복사"
                                                : "답변 복사"
                                    }
                                    title={
                                        isMessageCopied
                                            ? "복사됨"
                                            : isUser
                                                ? "질문 복사"
                                                : "답변 복사"
                                    }
                                >
                                    <span className="message-action-icon">
                                        ⧉
                                    </span>

                                    {isMessageCopied
                                        ? "복사됨"
                                        : "복사"}
                                </button>

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
                                            aria-label="답변 다시 생성"
                                            title="답변 다시 생성"
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