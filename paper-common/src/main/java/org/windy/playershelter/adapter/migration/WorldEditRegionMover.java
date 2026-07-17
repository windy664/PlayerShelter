package org.windy.playershelter.adapter.migration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.windy.playershelter.domain.port.RegionMover;

final class WorldEditRegionMover implements RegionMover {

    @Override
    public boolean copyRegion(String fromWorld, int fromCX, int fromCZ, int sizeChunks,
                              String toWorld, int toCX, int toCZ, ProgressListener progress) {
        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;
        listener.onProgress(RegionMover.Progress.of("resolve", "准备 WorldEdit 迁移", 0, 3, 0.0D));
        World src = Bukkit.getWorld(fromWorld);
        World dst = Bukkit.getWorld(toWorld);
        if (src == null || dst == null || sizeChunks <= 0) {
            return false;
        }

        int srcMinX = fromCX << 4;
        int srcMinZ = fromCZ << 4;
        int srcMaxX = ((fromCX + sizeChunks) << 4) - 1;
        int srcMaxZ = ((fromCZ + sizeChunks) << 4) - 1;
        int minY = Math.max(src.getMinHeight(), dst.getMinHeight());
        int maxY = Math.min(src.getMaxHeight(), dst.getMaxHeight()) - 1;
        if (maxY < minY) {
            return false;
        }
        listener.onProgress(RegionMover.Progress.of("blocks", "准备复制 WorldEdit 片段", 1, 3, 1D / 3D));

        int dstX = toCX << 4;
        int dstZ = toCZ << 4;
        com.sk89q.worldedit.world.World weSrc = BukkitAdapter.adapt(src);
        com.sk89q.worldedit.world.World weDst = BukkitAdapter.adapt(dst);
        WorldEdit worldEdit = WorldEdit.getInstance();
        CuboidRegion region = new CuboidRegion(weSrc,
                BlockVector3.at(srcMinX, minY, srcMinZ),
                BlockVector3.at(srcMaxX, maxY, srcMaxZ));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        try (EditSession srcSession = worldEdit.newEditSessionBuilder().world(weSrc).build()) {
            ForwardExtentCopy copy = new ForwardExtentCopy(srcSession, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(true);
            Operations.complete(copy);
        } catch (Exception e) {
            return false;
        }
        listener.onProgress(RegionMover.Progress.of("entities", "WorldEdit 源区块与实体已复制到剪贴板", 2, 3, 2D / 3D));

        try (EditSession dstSession = worldEdit.newEditSessionBuilder().world(weDst).build()) {
            Operation paste = new ClipboardHolder(clipboard)
                    .createPaste(dstSession)
                    .to(BlockVector3.at(dstX, minY, dstZ))
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(paste);
        } catch (Exception e) {
            return false;
        }
        listener.onProgress(RegionMover.Progress.of("finalize", "WorldEdit 粘贴完成", 3, 3, 1.0D));
        return true;
    }

}
