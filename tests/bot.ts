import mineflayer from 'mineflayer';
import { writeFileSync } from 'fs';
import { Vec3 } from 'vec3';
import config from './config.json';

const args = process.argv.slice(2);
const outputFile = args[0];

if (!outputFile) {
  console.error("Usage: bun run bot.ts <outputFile>");
  process.exit(1);
}

const bot = mineflayer.createBot({
  host: 'localhost',
  port: config.port,
  username: 'TestBot',
  version: config.minecraft_version
});

const oresToTrack = new Set([
  'coal_ore', 'deepslate_coal_ore',
  'iron_ore', 'deepslate_iron_ore',
  'copper_ore', 'deepslate_copper_ore',
  'gold_ore', 'deepslate_gold_ore',
  'diamond_ore', 'deepslate_diamond_ore',
  'lapis_ore', 'deepslate_lapis_ore',
  'redstone_ore', 'deepslate_redstone_ore',
  'emerald_ore', 'deepslate_emerald_ore'
]);

bot.once('spawn', async () => {
  console.log('Bot spawned. Waiting for chunks to load...');
  // Wait a few seconds to ensure surrounding chunks are sent
  await new Promise(resolve => setTimeout(resolve, 3000));

  const oreLocations: { x: number, y: number, z: number, type: string }[] = [];

  // Define the scan area: The spawn chunk (or a 16x16 area around the bot)
  const botPos = bot.entity.position.floored();
  const chunkX = Math.floor(botPos.x / 16) * 16;
  const chunkZ = Math.floor(botPos.z / 16) * 16;

  console.log(`Scanning chunk at X:${chunkX} Z:${chunkZ} from Y=-64 to Y=64`);

  for (let x = chunkX; x < chunkX + 16; x++) {
    for (let z = chunkZ; z < chunkZ + 16; z++) {
      for (let y = -64; y <= 64; y++) {
        const block = bot.blockAt(new Vec3(x, y, z));
        if (block && oresToTrack.has(block.name)) {
          oreLocations.push({
            x, y, z,
            type: block.name
          });
        }
      }
    }
  }

  console.log(`Found ${oreLocations.length} ores.`);
  writeFileSync(outputFile, JSON.stringify(oreLocations, null, 2));
  
  bot.quit();
});

bot.on('error', (err) => {
  console.error('Bot Error:', err);
  process.exit(1);
});

bot.on('kicked', (reason) => {
  console.error('Bot Kicked:', reason);
  process.exit(1);
});
