package app.builderx.ogfa.videoapichecker.Util;

import android.net.TrafficStats;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class InternetSpeedRecorderUtil {
    private long startedAtMs;
    private long startedAtWallClockMs;
    private long startRxBytes;
    private long startTxBytes;
    private boolean recording;
    private final List<Result> internetRecords = new ArrayList<>();

    public void start() {
        startedAtMs = SystemClock.elapsedRealtime();
        startedAtWallClockMs = System.currentTimeMillis();
        startRxBytes = getUidRxBytes();
        startTxBytes = getUidTxBytes();
        recording = true;
    }

    @NonNull
    public Result stop() {
        if (!recording) {
            return Result.empty();
        }

        long endedAtMs = SystemClock.elapsedRealtime();
        long endRxBytes = getUidRxBytes();
        long endTxBytes = getUidTxBytes();

        Result result = new Result(
                Math.max(0L, endRxBytes - startRxBytes),
                Math.max(0L, endTxBytes - startTxBytes),
                Math.max(0L, endedAtMs - startedAtMs),
                startedAtWallClockMs,
                System.currentTimeMillis());

        internetRecords.add(result);
        reset();
        return result;
    }

    @NonNull
    public Result snapshot() {
        if (!recording) {
            return Result.empty();
        }

        long nowMs = SystemClock.elapsedRealtime();
        return new Result(
                Math.max(0L, getUidRxBytes() - startRxBytes),
                Math.max(0L, getUidTxBytes() - startTxBytes),
                Math.max(0L, nowMs - startedAtMs),
                startedAtWallClockMs,
                System.currentTimeMillis());
    }

    @NonNull
    public List<Result> getInternetRecords() {
        return Collections.unmodifiableList(internetRecords);
    }

    @Nullable
    public Result getLastInternetRecord() {
        if (internetRecords.isEmpty()) {
            return null;
        }
        return internetRecords.get(internetRecords.size() - 1);
    }

    public int getInternetRecordCount() {
        return internetRecords.size();
    }

    public void clearInternetRecords() {
        internetRecords.clear();
    }

    public boolean isRecording() {
        return recording;
    }

    public void reset() {
        startedAtMs = 0L;
        startedAtWallClockMs = 0L;
        startRxBytes = 0L;
        startTxBytes = 0L;
        recording = false;
    }

    private long getUidRxBytes() {
        long bytes = TrafficStats.getUidRxBytes(Process.myUid());
        return bytes == TrafficStats.UNSUPPORTED ? 0L : bytes;
    }

    private long getUidTxBytes() {
        long bytes = TrafficStats.getUidTxBytes(Process.myUid());
        return bytes == TrafficStats.UNSUPPORTED ? 0L : bytes;
    }

    public static final class Result {
        private final long downloadedBytes;
        private final long uploadedBytes;
        private final long durationMs;
        private final long startedAtMs;
        private final long endedAtMs;

        private Result(
                long downloadedBytes,
                long uploadedBytes,
                long durationMs,
                long startedAtMs,
                long endedAtMs
        ) {
            this.downloadedBytes = downloadedBytes;
            this.uploadedBytes = uploadedBytes;
            this.durationMs = durationMs;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }

        @NonNull
        public static Result empty() {
            return new Result(0L, 0L, 0L, 0L, 0L);
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public long getUploadedBytes() {
            return uploadedBytes;
        }

        public long getTotalBytes() {
            return downloadedBytes + uploadedBytes;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public double getDurationSeconds() {
            return durationMs / 1000D;
        }

        public long getStartedAtMs() {
            return startedAtMs;
        }

        public long getEndedAtMs() {
            return endedAtMs;
        }

        public double getDownloadedMb() {
            return bytesToMb(downloadedBytes);
        }

        public double getUploadedMb() {
            return bytesToMb(uploadedBytes);
        }

        public double getTotalMb() {
            return bytesToMb(getTotalBytes());
        }

        public double getAverageMbps() {
            return getAverageTotalMbps();
        }

        public double getAverageDownloadMbps() {
            return bytesToMbps(downloadedBytes, durationMs);
        }

        public double getAverageUploadMbps() {
            return bytesToMbps(uploadedBytes, durationMs);
        }

        public double getAverageTotalMbps() {
            return bytesToMbps(getTotalBytes(), durationMs);
        }

        private static double bytesToMbps(long bytes, long durationMs) {
            if (durationMs <= 0L) {
                return 0D;
            }
            return (bytes * 8D) / (durationMs / 1000D) / 1_000_000D;
        }

        @NonNull
        public String toReadableText() {
            return String.format(
                    Locale.US,
                    "Downloaded: %.2f MB, Uploaded: %.2f MB, Total: %.2f MB, Avg Down: %.2f Mbps, Avg Up: %.2f Mbps, Avg Total: %.2f Mbps",
                    getDownloadedMb(),
                    getUploadedMb(),
                    getTotalMb(),
                    getAverageDownloadMbps(),
                    getAverageUploadMbps(),
                    getAverageTotalMbps());
        }

        private static double bytesToMb(long bytes) {
            return bytes / 1024D / 1024D;
        }
    }
}
