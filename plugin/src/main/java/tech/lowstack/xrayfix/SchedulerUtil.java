package tech.lowstack.xrayfix;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public class SchedulerUtil {

    private static final boolean IS_FOLIA = isFolia();

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void runChunkTask(Plugin plugin, Chunk chunk, Runnable task) {
        if (IS_FOLIA) {
            try {
                Object regionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
                Method executeMethod = regionScheduler.getClass().getMethod("execute", Plugin.class, org.bukkit.World.class, int.class, int.class, Runnable.class);
                executeMethod.invoke(regionScheduler, plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), task);
            } catch (Exception e) {
                e.printStackTrace();
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
