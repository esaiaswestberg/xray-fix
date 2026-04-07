package tech.lowstack.xrayfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.*;
import java.lang.reflect.Method;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ChunkObfuscator implements Listener {

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final int maxY;
    private final int netherMaxY;
    private final List<BlockData> fakeMaterials;
    private final List<BlockData> fakeMaterialsNether;
    private final Set<Material> transparentBlocks;

    public ChunkObfuscator(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("obfuscation.enabled", true);
        this.maxY = plugin.getConfig().getInt("obfuscation.max_y", 64);
        this.netherMaxY = plugin.getConfig().getInt("obfuscation.max_y_nether", 128);
        this.fakeMaterials = loadFakeMaterials("obfuscation.fake_materials", Material.STONE);
        this.fakeMaterialsNether = loadFakeMaterials("obfuscation.nether_fake_materials", Material.NETHERRACK);
        this.transparentBlocks = buildTransparentBlockSet();

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.MAP_CHUNK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!enabled) return;
                Player player = event.getPlayer();
                int chunkX = event.getPacket().getIntegers().read(0);
                int chunkZ = event.getPacket().getIntegers().read(1);
                World world = player.getWorld();

                if (world.getEnvironment() == World.Environment.THE_END) return;

                SchedulerUtil.runChunkTask(plugin, world.getChunkAt(chunkX, chunkZ), () -> {
                    if (player.isOnline()) {
                        processChunkSend(player, world.getChunkAt(chunkX, chunkZ));
                    }
                });
            }
        });
    }

    private List<BlockData> loadFakeMaterials(String configPath, Material fallback) {
        List<String> matNames = plugin.getConfig().getStringList(configPath);
        List<BlockData> list = new ArrayList<>();
        for (String name : matNames) {
            Material mat = Material.matchMaterial(name);
            if (mat != null && mat.isBlock()) {
                list.add(mat.createBlockData());
            }
        }
        if (list.isEmpty()) {
            list.add(fallback.createBlockData());
        }
        return list;
    }

    private Set<Material> buildTransparentBlockSet() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (Material mat : Material.values()) {
            if (mat.isBlock()) {
                if (!mat.isOccluding() || !mat.isSolid()) {
                    set.add(mat);
                }
            }
        }
        set.add(Material.AIR);
        set.add(Material.CAVE_AIR);
        set.add(Material.VOID_AIR);
        set.add(Material.WATER);
        set.add(Material.LAVA);
        return set;
    }

    private void processChunkSend(Player player, Chunk chunk) {
        World world = chunk.getWorld();

        if (world.getEnvironment() == World.Environment.THE_END) return;

        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();
        int limitY = Math.min(
                world.getEnvironment() == World.Environment.NETHER ? netherMaxY : maxY,
                worldMaxY - 1
        );

        List<BlockData> pool = world.getEnvironment() == World.Environment.NETHER ? fakeMaterialsNether : fakeMaterials;

        Map<Integer, Map<Location, BlockData>> sectionFakeBlocks = new HashMap<>();

        for (int y = worldMinY; y <= limitY; y++) {
            int sectionY = y >> 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material mat = snapshot.getBlockType(x, y, z);
                    if (transparentBlocks.contains(mat)) continue;

                    if (isExposed(chunk, snapshot, worldMinY, worldMaxY, x, y, z)) continue;

                    BlockData fakeData = getRandomFakeMaterial(pool);
                    if (fakeData.getMaterial() == mat) continue;

                    Location loc = new Location(world, (chunk.getX() << 4) + x, y, (chunk.getZ() << 4) + z);
                    sectionFakeBlocks.computeIfAbsent(sectionY, k -> new HashMap<>()).put(loc, fakeData);
                }
            }
        }

        for (Map<Location, BlockData> fakeBlocks : sectionFakeBlocks.values()) {
            Map<Location, BlockData> batch = new HashMap<>();
            for (Map.Entry<Location, BlockData> entry : fakeBlocks.entrySet()) {
                batch.put(entry.getKey(), entry.getValue());
                if (batch.size() >= 512) {
                    sendFakeBlocks(player, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                sendFakeBlocks(player, batch);
            }
        }
    }

    private boolean isExposed(Chunk chunk, ChunkSnapshot snapshot, int worldMinY, int worldMaxY, int x, int y, int z) {
        return isTransparent(chunk, snapshot, worldMinY, worldMaxY, x + 1, y, z) ||
               isTransparent(chunk, snapshot, worldMinY, worldMaxY, x - 1, y, z) ||
               isTransparent(chunk, snapshot, worldMinY, worldMaxY, x, y + 1, z) ||
               isTransparent(chunk, snapshot, worldMinY, worldMaxY, x, y - 1, z) ||
               isTransparent(chunk, snapshot, worldMinY, worldMaxY, x, y, z + 1) ||
               isTransparent(chunk, snapshot, worldMinY, worldMaxY, x, y, z - 1);
    }

    private boolean isTransparent(Chunk chunk, ChunkSnapshot snapshot, int worldMinY, int worldMaxY, int x, int y, int z) {
        if (y < worldMinY || y >= worldMaxY) {
            return false;
        }

        if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
            return transparentBlocks.contains(snapshot.getBlockType(x, y, z));
        }

        int cx = chunk.getX() + (x >> 4);
        int cz = chunk.getZ() + (z >> 4);
        World world = chunk.getWorld();

        if (!world.isChunkLoaded(cx, cz)) {
            return false;
        }

        int rx = x & 15;
        int rz = z & 15;
        return transparentBlocks.contains(world.getBlockAt((cx << 4) | rx, y, (cz << 4) | rz).getType());
    }

    private BlockData getRandomFakeMaterial(List<BlockData> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) { revealSurrounding(event.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) { revealSurrounding(event.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { revealMultiple(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { revealMultiple(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        revealSurrounding(event.getToBlock().getLocation());
    }

    private void revealMultiple(Collection<Block> blocks) {
        if (!enabled || blocks.isEmpty()) return;
        
        World world = blocks.iterator().next().getWorld();
        Map<Location, BlockData> updates = new HashMap<>();
        Set<Location> eventLocs = new HashSet<>();
        
        for (Block b : blocks) {
            eventLocs.add(b.getLocation());
        }
        
        for (Block b : blocks) {
            int cx = b.getX();
            int cy = b.getY();
            int cz = b.getZ();
            
            addIfValid(updates, world, eventLocs, cx + 1, cy, cz);
            addIfValid(updates, world, eventLocs, cx - 1, cy, cz);
            addIfValid(updates, world, eventLocs, cx, cy + 1, cz);
            addIfValid(updates, world, eventLocs, cx, cy - 1, cz);
            addIfValid(updates, world, eventLocs, cx, cy, cz + 1);
            addIfValid(updates, world, eventLocs, cx, cy, cz - 1);
        }
        
        sendUpdates(world, blocks.iterator().next().getLocation(), updates);
    }

    private void revealSurrounding(Location center) {
        if (!enabled) return;
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        Map<Location, BlockData> updates = new HashMap<>();
        Set<Location> eventLocs = Collections.singleton(center);

        addIfValid(updates, world, eventLocs, cx + 1, cy, cz);
        addIfValid(updates, world, eventLocs, cx - 1, cy, cz);
        addIfValid(updates, world, eventLocs, cx, cy + 1, cz);
        addIfValid(updates, world, eventLocs, cx, cy - 1, cz);
        addIfValid(updates, world, eventLocs, cx, cy, cz + 1);
        addIfValid(updates, world, eventLocs, cx, cy, cz - 1);

        sendUpdates(world, center, updates);
    }

    private void addIfValid(Map<Location, BlockData> updates, World world, Set<Location> eventLocs, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;

        Location loc = new Location(world, x, y, z);
        if (eventLocs.contains(loc)) return;

        Block block = world.getBlockAt(x, y, z);
        if (transparentBlocks.contains(block.getType())) return;

        updates.put(loc, block.getBlockData());
    }

    private void sendUpdates(World world, Location center, Map<Location, BlockData> updates) {
        if (updates.isEmpty()) return;

        int viewDist = plugin.getServer().getViewDistance();
        double viewDistSquared = Math.pow(viewDist * 16.0, 2);

        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= viewDistSquared) {
                sendFakeBlocks(player, updates);
            }
        }
    }

    private static Method sendMultiBlockChangeMethod;

    static {
        try {
            sendMultiBlockChangeMethod = Player.class.getMethod("sendMultiBlockChange", Map.class);
        } catch (NoSuchMethodException e) {
            sendMultiBlockChangeMethod = null;
        }
    }

    private void sendFakeBlocks(Player player, Map<Location, BlockData> batch) {
        if (sendMultiBlockChangeMethod != null) {
            try {
                sendMultiBlockChangeMethod.invoke(player, batch);
                return;
            } catch (Exception ignored) {
            }
        }
        for (Map.Entry<Location, BlockData> entry : batch.entrySet()) {
            player.sendBlockChange(entry.getKey(), entry.getValue());
        }
    }
}
