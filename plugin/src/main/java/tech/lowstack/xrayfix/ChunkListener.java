package tech.lowstack.xrayfix;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;

public class ChunkListener implements Listener {

    private final OreReplacer oreReplacer;

    public ChunkListener(OreReplacer oreReplacer) {
        this.oreReplacer = oreReplacer;
    }

    // Primary handler: fires when a freshly generated chunk finishes population.
    // LOWEST priority so our ore strip-and-replace happens before other plugins see the chunk.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        oreReplacer.process(event.getChunk());
    }

    // Secondary handler: catches chunks that loaded from disk without our PDC flag,
    // e.g. worlds that existed before XrayFix was installed (if process_existing_chunks is true).
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!oreReplacer.isProcessExistingChunks()) return;
        if (event.isNewChunk()) return; // already handled by ChunkPopulateEvent
        oreReplacer.process(event.getChunk());
    }
}
