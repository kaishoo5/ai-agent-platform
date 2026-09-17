import type {ChatSource} from "../../types/chat";

interface SourceChipsProps {
    sources: ChatSource[];
}

function formatTimestamp(
    millis: number,
): string {
    const totalSeconds =
        Math.floor(
            millis / 1000,
        );

    const hours =
        Math.floor(
            totalSeconds / 3600,
        );

    const minutes =
        Math.floor(
            (totalSeconds % 3600) / 60,
        );

    const seconds =
        totalSeconds % 60;

    if (hours > 0) {
        return [
            hours,
            minutes.toString().padStart(2, "0"),
            seconds.toString().padStart(2, "0"),
        ].join(":");
    }

    return [
        minutes,
        seconds.toString().padStart(2, "0"),
    ].join(":");
}

function getSourceLocation(
    source: ChatSource,
): string {
    if (
        source.startMillis !== null
        && source.endMillis !== null
    ) {
        return `${formatTimestamp(source.startMillis)}–${formatTimestamp(source.endMillis)}`;
    }

    return `Chunk ${source.chunkIndex}`;
}

function SourceChips({
                         sources,
                     }: SourceChipsProps) {
    if (sources.length === 0) {
        return null;
    }

    return (
        <div className="source-chips">
            <div className="source-chips-header">
                Sources · {sources.length}
            </div>

            <div className="source-chips-list">
                {sources.map((source, index) => (
                    <div
                        key={`${source.fileId}-${source.chunkIndex}-${index}`}
                        className="source-chip"
                        title={`${source.fileName} · ${getSourceLocation(source)}`}
                    >
                        <span className="source-chip-type">
                            {source.extension.toUpperCase()}
                        </span>

                        <span className="source-chip-name">
                            {source.fileName}
                        </span>

                        <span className="source-chip-location">
                            {getSourceLocation(source)}
                        </span>
                    </div>
                ))}
            </div>
        </div>
    );
}

export default SourceChips;