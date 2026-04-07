package tech.lowstack.xrayfix;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class XrayFix extends JavaPlugin {

    private OreReplacer oreReplacer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureSecret();

        String secret = getConfig().getString("secret");
        oreReplacer = new OreReplacer(this, secret);

        getServer().getPluginManager().registerEvents(new ChunkListener(oreReplacer), this);

        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            ChunkObfuscator obfuscator = new ChunkObfuscator(this);
            getServer().getPluginManager().registerEvents(obfuscator, this);
            getLogger().info("ProtocolLib found — packet obfuscation active.");
        } else {
            getLogger().warning("ProtocolLib not found — packet obfuscation disabled.");
        }

        getLogger().info("XrayFix enabled. Ore randomization active.");
        getLogger().info("Loaded " + oreReplacer.getOreGroupCount() + " ore group(s).");
    }

    @Override
    public void onDisable() {
        getLogger().info("XrayFix disabled.");
    }

    private void ensureSecret() {
        FileConfiguration config = getConfig();
        String secret = config.getString("secret", "");
        if (secret == null || secret.isBlank()) {
            secret = UUID.randomUUID().toString();
            config.set("secret", secret);
            saveConfig();
            getLogger().info("Generated new ore randomization secret. Keep config.yml private.");
        }
    }
}
