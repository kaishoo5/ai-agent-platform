import {useState,} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {Prism as SyntaxHighlighter,} from "react-syntax-highlighter";
import {vscDarkPlus,} from "react-syntax-highlighter/dist/esm/styles/prism";

import type {ChatMessage} from "../../types/chat";

interface ChatMessageItemProps {
    message: ChatMessage;
    isLastAssistant: boolean;
    isGenerating: boolean;
    onRegenerate: () => void;
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
    const [isCopied, setIsCopied] = useState(false);

    const handleCopy = async (): Promise<void> => {
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
                    {getLanguageLabel(language)}
                </span>

                <button
                    type="button"
                    className="code-copy-button"
                    onClick={() => {
                        void handleCopy();
                    }}
                >
                    {
                        isCopied
                            ? "복사됨"
                            : "복사"
                    }
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
                             onRegenerate,
                         }: ChatMessageItemProps) {
    const [isMessageCopied, setIsMessageCopied] =
        useState(false);

    const isUser = message.role === "USER";

    const isLoading =
        !isUser
        && message.content.length === 0;

    const handleMessageCopy = async (): Promise<void> => {
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
                "AI 답변 복사 중 오류가 발생했습니다.",
                error,
            );
        }
    };

    return (
        <article
            className={
                isUser
                    ? "chat-message chat-message-user"
                    : "chat-message chat-message-assistant"
            }
        >
            <div className="message-avatar">
                {isUser ? "나" : "AI"}
            </div>

            <div className="message-content">
                <div className="message-role">
                    {isUser ? "사용자" : "AI Agent"}
                </div>

                <div className="message-text">
                    {
                        isLoading
                            ? (
                                <div
                                    className="message-loading"
                                    aria-label="AI가 답변을 생성하고 있습니다."
                                >
                                    <span />
                                    <span />
                                    <span />
                                </div>
                            )
                            : isUser
                                ? message.content
                                : (
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
                                                        String(children)
                                                            .replace(
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
                                )
                    }
                </div>

                {
                    !isUser
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
                                {
                                    isMessageCopied
                                        ? "복사됨"
                                        : "복사"
                                }
                            </button>

                            {
                                isLastAssistant
                                && !isGenerating
                                && (
                                    <button
                                        type="button"
                                        className="message-action-button"
                                        onClick={onRegenerate}
                                    >
                                        ↻ 다시 생성
                                    </button>
                                )
                            }
                        </div>
                    )
                }
            </div>
        </article>
    );
}

export default ChatMessageItem;