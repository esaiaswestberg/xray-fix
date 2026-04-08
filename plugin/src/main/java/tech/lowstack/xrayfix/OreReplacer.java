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

    private record OreGroup(String name, Material stoneOre, Material deepslateOre /* null for nether ores */) {}

    private record WorldDimConfig(boolean vanillaGeneration, List<OreGroup> ores) {}

    // Vanilla ore placement parameters for each primary ore material.
    // Vein sizes and counts approximate Minecraft 1.21 defaults.
    // Multiple entries = multiple distribution passes (e.g. iron has a lower and upper band).
    private record VanillaOrePlacement(int veinSize, int veinsPerChunk, int minY, int maxY) {}

    private static final Map<Material, List<VanillaOrePlacement>> VANILLA_PLACEMENTS;
    static {
        Map<Material, List<VanillaOrePlacement>> m = new HashMap<>();
        m.put(Material.COAL_ORE,         List.of(new VanillaOrePlacement(17, 20,  0,  192)));
        m.put(Material.IRON_ORE,         List.of(
                new VanillaOrePlacement(9, 10, -24,  56),
                new VanillaOrePlacement(9, 10,  80, 256)));
        m.put(Material.GOLD_ORE,         List.of(new VanillaOrePlacement(9,  4,  -64,  32)));
        m.put(Material.COPPER_ORE,       List.of(new VanillaOrePlacement(20, 6,  -16,  96)));
        m.put(Material.LAPIS_ORE,        List.of(new VanillaOrePlacement(7,  3,  -64,  64)));
        m.put(Material.REDSTONE_ORE,     List.of(new VanillaOrePlacement(8,  8,  -64, -32)));
        m.put(Material.DIAMOND_ORE,      List.of(new VanillaOrePlacement(8,  1,  -64, -16)));
        m.put(Material.EMERALD_ORE,      List.of(new VanillaOrePlacement(3,  2,  -16, 256)));
        m.put(Material.NETHER_QUARTZ_ORE,List.of(new VanillaOrePlacement(14, 16,  10, 117)));
        m.put(Material.NETHER_GOLD_ORE,  List.of(new VanillaOrePlacement(10, 10,  10, 117)));
        m.put(Material.ANCIENT_DEBRIS,   List.of(new VanillaOrePlacement(3,  1,    8,  24)));
        VANILLA_PLACEMENTS = Collections.unmodifiableMap(m);
    }

    private final JavaPlugin plugin;
    private final String secret;
    private final boolean processExistingChunks;
    private final Map<World.Environment, WorldDimConfig> worldConfigs;
    private final Set<Material> managedOreMaterials;
    private final org.bukkit.NamespacedKey processedKey;

    public OreReplacer(JavaPlugin plugin, String secret) {
        this.plugin = plugin;
        this.secret = secret;
        this.processedKey = new org.bukkit.NamespacedKey(plugin, "processed");
        this.processExistingChunks = plugin.getConfig().getBoolean("process_existing_chunks", false);
        this.worldConfigs = loadWorldConfigs();
        this.managedOreMaterials = buildManagedMaterialSet();
    }

    public int getOreGroupCount() {
        return worldConfigs.values().stream()
                .mapToInt(c -> c.ores().size())
                .sum();
    }

    public boolean isProcessExistingChunks() {
        return processExistingChunks;
    }

    public void process(Chunk chunk) {
        if (chunk.getPersistentDataContainer().has(processedKey, PersistentDataType.BYTE)) return;

        World world = chunk.getWorld();
        World.Environment env = world.getEnvironment();
        WorldDimConfig dimConfig = worldConfigs.get(env);

        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight(); // exclusive upper bound

        stripOres(chunk, worldMinY, worldMaxY, env);

        if (dimConfig != null && dimConfig.vanillaGeneration() && !dimConfig.ores().isEmpty()) {
            placeVanillaOres(chunk, env, dimConfig, worldMinY, worldMaxY);
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

    // Re-places ores using a deterministic random-walk vein algorithm seeded from the server
    // secret rather than the world seed. Operates entirely within the loaded chunk using
    // Bukkit API to avoid triggering cross-chunk loading (which would cause recursive events).
    private void placeVanillaOres(Chunk chunk, World.Environment env, WorldDimConfig dimConfig,
                                  int worldMinY, int worldMaxY) {
        long baseSeed = computeChunkSeed(chunk.getX(), chunk.getZ(), env.name());
        Random rng = new Random(baseSeed);

        Set<Material> hostRocks = hostRocksFor(env);

        for (OreGroup group : dimConfig.ores()) {
            List<VanillaOrePlacement> placements = VANILLA_PLACEMENTS.get(group.stoneOre());
            if (placements == null) {
                plugin.getLogger().warning("No vanilla placement data for '" + group.stoneOre()
                        + "'; ore will be stripped but not re-placed.");
                continue;
            }

            for (VanillaOrePlacement vp : placements) {
                int effectiveMinY = Math.max(vp.minY(), worldMinY);
                int effectiveMaxY = Math.min(vp.maxY(), worldMaxY - 1);
                if (effectiveMinY >= effectiveMaxY) continue;

                for (int i = 0; i < vp.veinsPerChunk(); i++) {
                    int localX = rng.nextInt(16);
                    int y = effectiveMinY + rng.nextInt(effectiveMaxY - effectiveMinY + 1);
                    int localZ = rng.nextInt(16);
                    long veinSeed = rng.nextLong();
                    placeVein(chunk, localX, y, localZ, group, env, hostRocks, vp.veinSize(), new Random(veinSeed));
                }
            }
        }
    }

    // Places a single ore vein using a random-walk cluster. Stays strictly within the
    // chunk's local x/z coordinates (0–15) to avoid loading neighboring chunks.
    private void placeVein(Chunk chunk, int startX, int startY, int startZ,
                           OreGroup group, World.Environment env, Set<Material> hostRocks,
                           int veinSize, Random rng) {
        int cx = startX, cy = startY, cz = startZ;
        int placed = 0;
        int maxAttempts = veinSize * 4;

        for (int attempt = 0; attempt < maxAttempts && placed < veinSize; attempt++) {
            int nx = cx + rng.nextInt(3) - 1;
            int ny = cy + rng.nextInt(3) - 1;
            int nz = cz + rng.nextInt(3) - 1;

            // Clamp to chunk column to avoid cross-chunk access
            nx = Math.max(0, Math.min(15, nx));
            nz = Math.max(0, Math.min(15, nz));

            int worldMinY = chunk.getWorld().getMinHeight();
            int worldMaxY = chunk.getWorld().getMaxHeight();
            if (ny < worldMinY || ny >= worldMaxY) continue;

            Block block = chunk.getBlock(nx, ny, nz);
            Material current = block.getType();

            if (hostRocks.contains(current)) {
                // Choose stone vs deepslate ore based on current host block
                Material ore = (current == Material.DEEPSLATE && group.deepslateOre() != null)
                        ? group.deepslateOre()
                        : group.stoneOre();
                block.setType(ore, false);
                cx = nx;
                cy = ny;
                cz = nz;
                placed++;
            }
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    // Returns the appropriate host rock to substitute for a stripped ore block.
    private Material hostRockFor(Material ore, World.Environment env) {
        if (env == World.Environment.NETHER) return Material.NETHERRACK;
        return ore.name().startsWith("DEEPSLATE_") ? Material.DEEPSLATE : Material.STONE;
    }

    // Returns the set of blocks that ore veins are allowed to replace in a given dimension.
    private Set<Material> hostRocksFor(World.Environment env) {
        if (env == World.Environment.NETHER) {
            return Set.of(Material.NETHERRACK);
        }
        return Set.of(Material.STONE, Material.GRANITE, Material.DIORITE,
                Material.ANDESITE, Material.TUFF, Material.DEEPSLATE);
    }

    // Creates a per-chunk deterministic seed via SHA-256(secret:chunkX:chunkZ:dimension).
    // This seed is not derivable from the world seed.
    private long computeChunkSeed(int chunkX, int chunkZ, String dimension) {
        String input = secret + ":" + chunkX + ":" + chunkZ + ":" + dimension;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xFFL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java SE
            throw new RuntimeException(e);
        }
    }

    private void markProcessed(Chunk chunk) {
        chunk.getPersistentDataContainer().set(processedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private Map<World.Environment, WorldDimConfig> loadWorldConfigs() {
        Logger log = plugin.getLogger();
        ConfigurationSection worldGenSection = plugin.getConfig().getConfigurationSection("world_generation");
        if (worldGenSection == null) {
            log.warning("No 'world_generation' section found in config.yml - no ores will be managed.");
            return Collections.emptyMap();
        }

        Map<String, World.Environment> sectionToEnv = Map.of(
                "overworld",  World.Environment.NORMAL,
                "the_nether", World.Environment.NETHER,
                "the_end",    World.Environment.THE_END
        );

        Map<World.Environment, WorldDimConfig> result = new EnumMap<>(World.Environment.class);
        for (Map.Entry<String, World.Environment> entry : sectionToEnv.entrySet()) {
            String sectionKey = entry.getKey();
            World.Environment env = entry.getValue();

            ConfigurationSection section = worldGenSection.getConfigurationSection(sectionKey);
            if (section == null) continue;

            boolean vanillaGen = section.getBoolean("vanilla_generation", false);
            List<OreGroup> ores = new ArrayList<>();

            List<?> oreList = section.getList("ores", Collections.emptyList());
            for (Object obj : oreList) {
                if (!(obj instanceof Map<?, ?> oreEntry)) continue;

                String stoneOreStr = String.valueOf(oreEntry.get("stone_ore"));
                String deepslateOreStr = String.valueOf(oreEntry.get("deepslate_ore"));

                Material stoneOre = Material.matchMaterial(stoneOreStr);
                if (stoneOre == null) {
                    log.warning("world_generation." + sectionKey + ": unknown material '"
                            + stoneOreStr + "', skipping.");
                    continue;
                }

                Material deepslateOre = null;
                if (!deepslateOreStr.isBlank() && !deepslateOreStr.equals("null")) {
                    deepslateOre = Material.matchMaterial(deepslateOreStr);
                    if (deepslateOre == null) {
                        log.warning("world_generation." + sectionKey + ": unknown deepslate material '"
                                + deepslateOreStr + "', treating as none.");
                    }
                }

                ores.add(new OreGroup(stoneOreStr, stoneOre, deepslateOre));
            }

            result.put(env, new WorldDimConfig(vanillaGen, Collections.unmodifiableList(ores)));
        }
        return Collections.unmodifiableMap(result);
    }

    private Set<Material> buildManagedMaterialSet() {
        Set<Material> materials = new HashSet<>();
        for (WorldDimConfig config : worldConfigs.values()) {
            for (OreGroup group : config.ores()) {
                materials.add(group.stoneOre());
                if (group.deepslateOre() != null) materials.add(group.deepslateOre());
            }
        }
        return Collections.unmodifiableSet(materials);
    }
}
