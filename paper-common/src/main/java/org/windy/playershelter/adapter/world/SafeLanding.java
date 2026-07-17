package org.windy.playershelter.adapter.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * 安全落点（决策 #48；同 GuildShelter 安全落点思想 [[guildshelter-safe-landing]]）：
 * 落点若是水/岩浆/虚空/空气悬空，就在脚下铺一格站脚台（默认玻璃，config 可改），防落水/坠虚空/坠落摔死。
 */
public final class SafeLanding {

    private final Material platformMaterial;

    public SafeLanding(Material platformMaterial) {
        this.platformMaterial = platformMaterial == null ? Material.GLASS : platformMaterial;
    }

    /**
     * 【重载方法 1】求世界地表/出生点的安全落点。
     * 适用于：没有指定 Y 轴高度的传送（如随机传送、纯 XZ 坐标传送）。
     *
     * @param world 已加载的世界
     * @param x,z   目标水平坐标
     */
    public Location resolve(World world, int x, int z) {
        int top = world.getHighestBlockYAt(x, z);

        // 【修复 1】getHighestBlockYAt 返回的是最高非空方块的 Y。
        // 玩家应该站在这个方块的“上面一格”，所以脚部 Y 轴必须是 top + 1。
        int y = top + 1;

        // 虚空世界处理
        if (y < world.getMinHeight() + 1) {
            y = 64; // 虚空：默认在 y=64 摆台 (脚部在 64，踩的玻璃在 63)
        }

        return createSafeLandingAndPlatform(world, x, y, z);
    }

    /**
     * Paper 传送前准备落点：先异步加载目标 chunk，再回主线程写安全平台。
     */
    public CompletableFuture<Location> prepareAsync(Plugin plugin, World world, int x, int z) {
        CompletableFuture<Location> out = new CompletableFuture<>();
        world.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, error) -> {
            if (error != null) {
                out.completeExceptionally(error);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    out.complete(resolve(world, x, z));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            });
        });
        return out;
    }

    public CompletableFuture<Location> prepareAsync(Plugin plugin, Location targetLoc) {
        World world = targetLoc.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(targetLoc);
        }
        CompletableFuture<Location> out = new CompletableFuture<>();
        int x = targetLoc.getBlockX();
        int z = targetLoc.getBlockZ();
        world.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, error) -> {
            if (error != null) {
                out.completeExceptionally(error);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    out.complete(resolve(targetLoc));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            });
        });
        return out;
    }

    /**
     * 【重载方法 2 - 解决高空传送点问题】根据玩家原本指定的完整坐标（包含Y轴）计算安全落点。
     * 适用于：家园(Home)、地标(Warp)等原本就拥有 Y 轴高度的传送点。
     *
     * @param targetLoc 原始的目标传送位置
     */
    public Location resolve(Location targetLoc) {
        World world = targetLoc.getWorld();
        if (world == null) return targetLoc;

        int x = targetLoc.getBlockX();
        int y = targetLoc.getBlockY();
        int z = targetLoc.getBlockZ();

        // 【修复 2】如果原本就是天上的传送点，我们基于当前的 Y 轴向下探测，而不要用 getHighestBlockYAt 强行拉回地面。
        return createSafeLandingAndPlatform(world, x, y, z);
    }

    /**
     * 核心内部方法：在指定的 (x, y, z) 作为脚部进行安全检查，必要时铺台，并清理窒息方块。
     */
    private Location createSafeLandingAndPlatform(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block below = world.getBlockAt(x, y - 1, z);

        // 检查脚踩的方块是否是不安全的（空气、液体、熔岩、水）
        boolean unsafeBelow = below.getType().isAir()
                || below.isLiquid()
                || below.getType() == Material.LAVA
                || below.getType() == Material.WATER;

        if (unsafeBelow) {
            // 在脚下生成一格方块，防止玩家掉下去
            below.setType(platformMaterial);
        }

        // 【修复 3】清出头顶两格空间（脚部和头部）。
        // 只有当它们不是空气，或者是液体时，才强制清除。
        // 这样如果你在天上设置了传送点，两格高空间内本就是空气，就不会触发任何 setType，绝对不会吞方块。
        if (feet.isLiquid() || (!feet.getType().isAir() && feet.getType() != platformMaterial)) {
            feet.setType(Material.AIR);
        }

        Block head = world.getBlockAt(x, y + 1, z);
        if (head.isLiquid() || !head.getType().isAir()) {
            head.setType(Material.AIR);
        }

        // 返回精确的方块中心点
        return new Location(world, x + 0.5, y, z + 0.5);
    }
}
