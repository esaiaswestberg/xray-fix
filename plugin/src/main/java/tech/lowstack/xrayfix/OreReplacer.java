package tech.lowstack.xrayfix;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;

public class OreReplacer {

    private record OreGroup(
            String name,
            boolean enabled,
            Material stoneOre,
            Material deepslateOre, // null when there is no deepslate variant (e.g. nether ores)
            int veinsPerChunk,
            int veinSize,
            int minY,
            int maxY
    ) {}

    // Blocks that ore veins are allowed to replace.
    private static final Set<Material> OVERWORLD_HOST_ROCKS = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.TUFF
    );
    private static final Set<Material> NETHER_HOST_ROCKS = Set.of(
            Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE
    );

    // Ore materials that belong exclusively to the Nether dimension.
    private static final Set<Material> NETHER_ORE_MATERIALS = Set.of(
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE, Material.ANCIENT_DEBRIS
    );

    private final JavaPlugin plugin;
    private final String secret;
    private final boolean processExistingChunks;
    private final List<OreGroup> oreGroups;
    private final Set<Material> managedOreMaterials;
    private final org.bukkit.NamespacedKey processedKey;

    public OreReplacer(JavaPlugin plugin, String secret) {
        this.plugin = plugin;
        this.secret = secret;
        this.processedKey = new org.bukkit.NamespacedKey(plugin, "processed");
        this.processExistingChunks = plugin.getConfig().getBoolean("process_existing_chunks", false);
        this.oreGroups = loadOreGroups();
        this.managedOreMaterials = buildManagedMaterialSet();
    }

    public int getOreGroupCount() {
        return (int) oreGroups.stream().filter(OreGroup::enabled).count();
    }

    public boolean isProcessExistingChunks() {
        return processExistingChunks;
    }

    public void process(Chunk chunk) {
        if (chunk.getPersistentDataContainer().has(processedKey, PersistentDataType.BYTE)) return;

        World world = chunk.getWorld();
        World.Environment env = world.getEnvironment();

        // The End has no managed ores — just mark and skip.
        if (env == World.Environment.THE_END) {
            markProcessed(chunk);
            return;
        }

        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight(); // exclusive upper bound

        stripOres(chunk, worldMinY, worldMaxY, env);

        Random rng = createChunkRandom(chunk.getX(), chunk.getZ(), env.name());
        for (OreGroup group : oreGroups) {
            if (!group.enabled()) continue;
            if (!isGroupForEnvironment(group, env)) continue;

            int effectiveMinY = Math.max(group.minY(), worldMinY);
            int effectiveMaxY = Math.min(group.maxY(), worldMaxY - 1);
            if (effectiveMinY >= effectiveMaxY) continue;

            for (int i = 0; i < group.veinsPerChunk(); i++) {
                int x = rng.nextInt(16);
                int y = effectiveMinY + rng.nextInt(effectiveMaxY - effectiveMinY + 1);
                int z = rng.nextInt(16);
                placeVein(chunk, group, x, y, z, effectiveMinY, effectiveMaxY, env, rng);
            }
        }

        markProcessed(chunk);
    }

    // Scans the full chunk column and replaces all managed ore blocks with their host rock.
    private void stripOres(Chunk chunk, int minY, int maxY, World.Environment env) {
        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (managedOreMaterials.contains(type)) {
                        chunk.getBlock(x, y, z).setType(hostRockFor(type, env), false);
                    }
                }
            }
        }
    }

    // Returns the appropriate host rock to substitute for a stripped ore block.
    private Material hostRockFor(Material ore, World.Environment env) {
        if (env == World.Environment.NETHER) return Material.NETHERRACK;
        // Deepslate ore names all start with "DEEPSLATE_"
        return ore.name().startsWith("DEEPSLATE_") ? Material.DEEPSLATE : Material.STONE;
    }

    // Places a single ore vein using a 3D random walk anchored at (startX, startY, startZ).
    private void placeVein(Chunk chunk, OreGroup group, int startX, int startY, int startZ,
                           int minY, int maxY, World.Environment env, Random rng) {
        int x = startX, y = startY, z = startZ;
        for (int i = 0; i < group.veinSize(); i++) {
            Block block = chunk.getBlock(x, y, z);
            if (isHostRock(block.getType(), env)) {
                block.setType(selectVariant(group, y), false);
            }
            // Random walk — clamped to stay within this chunk and the ore's Y range.
            x = Math.max(0, Math.min(15, x + rng.nextInt(3) - 1));
            y = Math.max(minY, Math.min(maxY, y + rng.nextInt(3) - 1));
            z = Math.max(0, Math.min(15, z + rng.nextInt(3) - 1));
        }
    }

    // Selects stone or deepslate variant based on Y coordinate.
    private Material selectVariant(OreGroup group, int y) {
        if (group.deepslateOre() == null) return group.stoneOre();
        return y < 0 ? group.deepslateOre() : group.stoneOre();
    }

    private boolean isHostRock(Material material, World.Environment env) {
        return env == World.Environment.NETHER
                ? NETHER_HOST_ROCKS.contains(material)
                : OVERWORLD_HOST_ROCKS.contains(material);
    }

    // True if this ore group belongs to the given environment.
    private boolean isGroupForEnvironment(OreGroup group, World.Environment env) {
        boolean isNetherOre = NETHER_ORE_MATERIALS.contains(group.stoneOre());
        return env == World.Environment.NETHER ? isNetherOre : !isNetherOre;
    }

    // Creates a per-chunk deterministic Random seeded by SHA-256(secret:chunkX:chunkZ:dimension).
    // This seed is not derivable from the world seed.
    private Random createChunkRandom(int chunkX, int chunkZ, String dimension) {
        String input = secret + ":" + chunkX + ":" + chunkZ + ":" + dimension;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xFFL);
            }
            return new Random(seed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java SE
            throw new RuntimeException(e);
        }
    }

    private void markProcessed(Chunk chunk) {
        chunk.getPersistentDataContainer().set(processedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private List<OreGroup> loadOreGroups() {
        Logger log = plugin.getLogger();
        ConfigurationSection oresSection = plugin.getConfig().getConfigurationSection("ores");
        if (oresSection == null) {
            log.warning("No 'ores' section found in config.yml — no ores will be managed.");
            return Collections.emptyList();
        }

        List<OreGroup> groups = new ArrayList<>();
        for (String key : oresSection.getKeys(false)) {
            ConfigurationSection entry = oresSection.getConfigurationSection(key);
            if (entry == null) continue;

            String stoneOreStr = entry.getString("stone_ore", "");
            String deepslateOreStr = entry.getString("deepslate_ore", "");

            Material stoneOre = Material.matchMaterial(stoneOreStr);
            if (stoneOre == null) {
                log.warning("Ore group '" + key + "': unknown material '" + stoneOreStr + "', skipping.");
                continue;
            }

            Material deepslateOre = null;
            if (deepslateOreStr != null && !deepslateOreStr.isBlank()) {
                deepslateOre = Material.matchMaterial(deepslateOreStr);
                if (deepslateOre == null) {
                    log.warning("Ore group '" + key + "': unknown deepslate material '" + deepslateOreStr + "', treating as none.");
                }
            }

            groups.add(new OreGroup(
                    key,
                    entry.getBoolean("enabled", true),
                    stoneOre,
                    deepslateOre,
                    entry.getInt("veins_per_chunk", 1),
                    entry.getInt("vein_size", 4),
                    entry.getInt("min_y", -64),
                    entry.getInt("max_y", 64)
            ));
        }
        return groups;
    }

    private Set<Material> buildManagedMaterialSet() {
        Set<Material> materials = new HashSet<>();
        for (OreGroup group : oreGroups) {
            if (!group.enabled()) continue;
            materials.add(group.stoneOre());
            if (group.deepslateOre() != null) materials.add(group.deepslateOre());
        }
        return Collections.unmodifiableSet(materials);
    }
}
