import type {VideoSummaryResult,} from "../../types/chat";

interface VideoResultCardProps {
    videoResult: VideoSummaryResult;
}

const API_BASE_URL =
    "http://localhost:8080";

function formatDuration(
    durationSeconds: number,
): string {
    if (durationSeconds < 60) {
        return `${durationSeconds} sec`;
    }

    const minutes =
        Math.floor(
            durationSeconds / 60,
        );

    const seconds =
        durationSeconds % 60;

    if (seconds === 0) {
        return `${minutes} min`;
    }

    return `${minutes} min ${seconds} sec`;
}

function VideoResultCard({
                             videoResult,
                         }: VideoResultCardProps) {
    const streamUrl =
        `${API_BASE_URL}${videoResult.streamUrl}`;

    const downloadUrl =
        `${API_BASE_URL}${videoResult.downloadUrl}`;

    const handleDownload = (): void => {
        window.open(
            downloadUrl,
            "_blank",
        );
    };

    return (
        <div className="video-result-card">
            <div className="video-result-header">
                <div className="video-result-icon">
                    <svg
                        viewBox="0 0 24 24"
                        aria-hidden="true"
                    >
                        <path
                            d="M15 10.5V6.75A1.75 1.75 0 0 0 13.25 5h-8.5A1.75 1.75 0 0 0 3 6.75v10.5A1.75 1.75 0 0 0 4.75 19h8.5A1.75 1.75 0 0 0 15 17.25V13.5l5 3v-9l-5 3Z"
                            fill="currentColor"
                        />
                    </svg>
                </div>

                <div className="video-result-title">
                    <strong>
                        AI Video Summary
                    </strong>

                    <span>
                        Generated summary video
                    </span>
                </div>
            </div>

            <div className="video-result-player">
                <video
                    controls
                    preload="metadata"
                    src={streamUrl}
                >
                    브라우저에서 영상을 재생할 수 없습니다.
                </video>
            </div>

            <div className="video-result-info">
                <div className="video-result-file">
                    <span className="video-result-file-name">
                        {videoResult.fileName}
                    </span>

                    <span className="video-result-duration">
                        Target duration ·{" "}
                        {formatDuration(
                            videoResult.durationSeconds,
                        )}
                    </span>
                </div>

                <button
                    type="button"
                    className="video-result-download"
                    onClick={handleDownload}
                    title="요약 영상 다운로드"
                >
                    <svg
                        viewBox="0 0 24 24"
                        aria-hidden="true"
                    >
                        <path
                            d="M12 3v12m0 0 5-5m-5 5-5-5M5 20h14"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    </svg>

                    <span>
                        Download
                    </span>
                </button>
            </div>
        </div>
    );
}

export default VideoResultCard;