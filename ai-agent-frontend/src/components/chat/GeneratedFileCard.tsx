import API_BASE_URL from "../../config/api";

import type {GeneratedFile,} from "../../types/chat";

interface GeneratedFileCardProps {
    file: GeneratedFile;
}

function formatFileSize(
    size: number,
): string {
    if (size < 1024) {
        return `${size} B`;
    }

    if (size < 1024 * 1024) {
        return `${(size / 1024).toFixed(1)} KB`;
    }

    return `${(
        size
        / 1024
        / 1024
    ).toFixed(1)} MB`;
}

function getFileTypeLabel(
    extension: string,
): string {
    switch (extension.toLowerCase()) {
        case "xlsx":
            return "Excel 파일";

        case "pdf":
            return "PDF 파일";

        case "docx":
            return "Word 파일";

        case "txt":
            return "텍스트 파일";

        default:
            return `${extension.toUpperCase()} 파일`;
    }
}

function GeneratedFileCard({
                               file,
                           }: GeneratedFileCardProps) {
    const downloadUrl =
        `${API_BASE_URL}/api/generated-files/${file.id}/download`;

    return (
        <div className="generated-file-card">
            <div className="generated-file-icon">
                {file.extension.toLowerCase() === "xlsx"
                    ? "X"
                    : "↓"}
            </div>

            <div className="generated-file-info">
                <div className="generated-file-name">
                    {file.originalName}
                </div>

                <div className="generated-file-meta">
                    {getFileTypeLabel(
                        file.extension,
                    )}
                    {" · "}
                    {formatFileSize(
                        file.size,
                    )}
                </div>
            </div>

            <a
                className="generated-file-download"
                href={downloadUrl}
                download={file.originalName}
            >
                다운로드
            </a>
        </div>
    );
}

export default GeneratedFileCard;