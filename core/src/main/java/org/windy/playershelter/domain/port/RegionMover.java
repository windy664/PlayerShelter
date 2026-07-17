package org.windy.playershelter.domain.port;

/**
 * 平台区域复制端口。
 *
 * <p>坐标使用 chunk 坐标，复制区域为 {@code sizeChunks x sizeChunks} 的正方形。
 * 本端口只负责复制方块、实体和方块实体数据；是否更新 PS/GS 数据库由调用方在复制成功后处理。
 */
public interface RegionMover {

    RegionMover UNSUPPORTED = (fromWorld, fromCX, fromCZ, sizeChunks, toWorld, toCX, toCZ, progress) -> false;

    default boolean copyRegion(String fromWorld, int fromCX, int fromCZ, int sizeChunks,
                               String toWorld, int toCX, int toCZ) {
        return copyRegion(fromWorld, fromCX, fromCZ, sizeChunks, toWorld, toCX, toCZ, ProgressListener.NOOP);
    }

    boolean copyRegion(String fromWorld, int fromCX, int fromCZ, int sizeChunks,
                       String toWorld, int toCX, int toCZ, ProgressListener progress);

    default void copyRegionAsync(String fromWorld, int fromCX, int fromCZ, int sizeChunks,
                                 String toWorld, int toCX, int toCZ,
                                 ProgressListener progress, java.util.function.Consumer<Boolean> whenDone) {
        boolean copied = copyRegion(fromWorld, fromCX, fromCZ, sizeChunks, toWorld, toCX, toCZ, progress);
        if (whenDone != null) {
            whenDone.accept(copied);
        }
    }

    @FunctionalInterface
    interface ProgressListener {
        ProgressListener NOOP = progress -> {
        };

        void onProgress(Progress progress);
    }

    record Progress(String phase, String detail, int current, int total, double overall) {
        public Progress {
            phase = phase == null ? "" : phase;
            detail = detail == null ? "" : detail;
            total = Math.max(0, total);
            current = total > 0 ? Math.max(0, Math.min(current, total)) : Math.max(0, current);
            overall = Double.isFinite(overall) ? Math.max(0.0D, Math.min(1.0D, overall)) : 0.0D;
        }

        public static Progress of(String phase, String detail, int current, int total, double overall) {
            return new Progress(phase, detail, current, total, overall);
        }
    }
}
