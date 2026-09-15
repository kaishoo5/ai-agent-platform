package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class VideoFrameDeduplicator {

    private static final int SAMPLE_SIZE = 16;

    // 0 = 완전히 동일, 값이 커질수록 다른 이미지
    private static final double DUPLICATE_THRESHOLD = 8.0;

    public List<VideoFrame> filter(List<VideoFrame> frames) {

        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        List<VideoFrame> result = new ArrayList<>();

        VideoFrame previousAcceptedFrame = null;
        double[] previousFingerprint = null;

        for (VideoFrame frame : frames) {

            double[] fingerprint = createFingerprint(frame);

            if (previousAcceptedFrame == null) {
                result.add(frame);
                previousAcceptedFrame = frame;
                previousFingerprint = fingerprint;
                continue;
            }

            double difference = calculateDifference(
                    previousFingerprint,
                    fingerprint
            );

            if (difference <= DUPLICATE_THRESHOLD) {
                log.info(
                        "유사 영상 프레임 Vision 분석 제외. timestampMillis={}, previousTimestampMillis={}, difference={}",
                        frame.timestampMillis(),
                        previousAcceptedFrame.timestampMillis(),
                        difference
                );
                continue;
            }

            result.add(frame);
            previousAcceptedFrame = frame;
            previousFingerprint = fingerprint;
        }

        log.info(
                "영상 프레임 중복 제거 완료. originalCount={}, filteredCount={}, removedCount={}",
                frames.size(),
                result.size(),
                frames.size() - result.size()
        );

        return result;
    }

    private double[] createFingerprint(VideoFrame frame) {

        BufferedImage source;

        try {
            source = ImageIO.read(frame.path().toFile());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "영상 프레임을 읽을 수 없습니다. path=" + frame.path(),
                    e
            );
        }

        if (source == null) {
            throw new IllegalStateException(
                    "지원하지 않는 영상 프레임 이미지입니다. path=" + frame.path()
            );
        }

        BufferedImage resized = new BufferedImage(
                SAMPLE_SIZE,
                SAMPLE_SIZE,
                BufferedImage.TYPE_BYTE_GRAY
        );

        Graphics2D graphics = resized.createGraphics();

        try {
            graphics.drawImage(
                    source,
                    0,
                    0,
                    SAMPLE_SIZE,
                    SAMPLE_SIZE,
                    null
            );
        } finally {
            graphics.dispose();
        }

        double[] fingerprint = new double[SAMPLE_SIZE * SAMPLE_SIZE];

        int index = 0;

        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                fingerprint[index++] =
                        resized.getRaster().getSample(x, y, 0);
            }
        }

        return fingerprint;
    }

    private double calculateDifference(
            double[] first,
            double[] second
    ) {

        double totalDifference = 0;

        for (int i = 0; i < first.length; i++) {
            totalDifference += Math.abs(first[i] - second[i]);
        }

        return totalDifference / first.length;
    }
}