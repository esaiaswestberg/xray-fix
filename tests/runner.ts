import { spawn, execSync } from 'child_process';
import { existsSync, rmSync, mkdirSync, copyFileSync, writeFileSync } from 'fs';
import { join } from 'path';
import config from './config.json';

const PAPER_URL = "https://api.papermc.io/v2/projects/paper/versions/1.21.1/builds/119/downloads/paper-1.21.1-119.jar";
const PROTOCOLLIB_URL = "https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar";

const SERVER_DIR = join(import.meta.dir, 'server');
const PLUGINS_DIR = join(SERVER_DIR, 'plugins');
const XRAY_CONFIG_DIR = join(PLUGINS_DIR, 'XrayFix');

async function downloadFile(url: string, path: string) {
    if (existsSync(path)) return;
    console.log(`Downloading ${url} to ${path}`);
    const response = await fetch(url);
    const buffer = await response.arrayBuffer();
    writeFileSync(path, Buffer.from(buffer));
}

async function prepareServer() {
    if (!existsSync(SERVER_DIR)) mkdirSync(SERVER_DIR);
    if (!existsSync(PLUGINS_DIR)) mkdirSync(PLUGINS_DIR);

    await downloadFile(PAPER_URL, join(SERVER_DIR, 'paper.jar'));
    await downloadFile(PROTOCOLLIB_URL, join(PLUGINS_DIR, 'ProtocolLib.jar'));

    writeFileSync(join(SERVER_DIR, 'eula.txt'), 'eula=true');
    writeFileSync(join(SERVER_DIR, 'server.properties'), `level-seed=${config.seed}\nonline-mode=false\nserver-port=${config.port}\n`);
}

function getJavaExecutable(requiredVersion?: number): string {
    const command = requiredVersion
        ? `find /usr/lib/jvm -name "java" -path "*/bin/java" | grep "java-${requiredVersion}" | sort -t- -k2 -V | tail -n1`
        : `find /usr/lib/jvm -name "java" -path "*/bin/java" | sort -t- -k2 -V | tail -n1`;
    try {
        const javaPath = execSync(command).toString().trim();
        if (!javaPath) throw new Error("Java not found");
        return javaPath;
    } catch (e) {
        throw new Error(`Failed to find Java ${requiredVersion || 'latest'}: ${e}`);
    }
}

function getServerJavaVersion(mcVersion: string): number {
    const parts = mcVersion.split('.');
    const major = parseInt(parts[0] || '1', 10);
    const minor = parseInt(parts[1] || '0', 10);
    const patch = parseInt(parts[2] || '0', 10);

    if (major !== 1) return 21;
    if (minor >= 21 || (minor === 20 && patch >= 5)) return 21;
    if (minor >= 18) return 17;
    if (minor === 17) return 16;
    return 8;
}

const BUILD_JAVA = getJavaExecutable();
const SERVER_JAVA = getJavaExecutable(getServerJavaVersion(config.minecraft_version));
const BUILD_JAVA_HOME = join(BUILD_JAVA, '../../');
const SERVER_JAVA_HOME = join(SERVER_JAVA, '../../');

function buildPlugin() {
    console.log(`Building XrayFix plugin with Java: ${BUILD_JAVA}`);
    execSync('mvn clean package', { stdio: 'inherit', cwd: join(import.meta.dir, '../plugin'), env: { ...process.env, JAVA_HOME: BUILD_JAVA_HOME } });
    copyFileSync(join(import.meta.dir, '../plugin/target/xrayfix-1.0.0.jar'), join(PLUGINS_DIR, 'XrayFix.jar'));
}

async function runServerAndBot(runName: string): Promise<void> {
    return new Promise((resolve, reject) => {
        console.log(`Starting server for ${runName} with Java: ${SERVER_JAVA}...`);
        const serverProcess = spawn(SERVER_JAVA, ['-jar', 'paper.jar', '--nogui'], { cwd: SERVER_DIR, env: { ...process.env, JAVA_HOME: SERVER_JAVA_HOME } });
        
        serverProcess.stdout.on('data', (data) => {
            const line = data.toString();
            if (line.includes('Done') && line.includes('For help, type "help"')) {
                console.log("Server is ready, starting bot...");
                
                const botProcess = spawn('bun', ['run', 'bot.ts', `${runName}.json`], { cwd: import.meta.dir });
                botProcess.stdout.on('data', d => console.log(`[BOT] ${d}`));
                botProcess.stderr.on('data', d => console.error(`[BOT ERR] ${d}`));
                
                botProcess.on('exit', (code) => {
                    console.log(`Bot finished with code ${code}. Stopping server...`);
                    serverProcess.stdin.write('stop\n');
                });
            }
        });

        serverProcess.on('exit', (code) => {
            console.log(`Server stopped for ${runName}.`);
            resolve();
        });
    });
}

function clearWorld() {
    ['world', 'world_nether', 'world_the_end'].forEach(world => {
        const p = join(SERVER_DIR, world);
        if (existsSync(p)) rmSync(p, { recursive: true, force: true });
    });
}

function writeXrayConfig(obfuscationEnabled: boolean) {
    if (!existsSync(XRAY_CONFIG_DIR)) mkdirSync(XRAY_CONFIG_DIR, { recursive: true });
    
    // Core logic for the test
    const configContent = `
secret: "${config.plugin_secret}"
process_existing_chunks: false
obfuscation:
  enabled: ${obfuscationEnabled}
  max_y: 64
  fake_materials:
    - DIAMOND_ORE
    - DEEPSLATE_DIAMOND_ORE
world_generation:
  overworld:
    vanilla_generation: true
    ores:
      - stone_ore: COAL_ORE
        deepslate_ore: DEEPSLATE_COAL_ORE
      - stone_ore: IRON_ORE
        deepslate_ore: DEEPSLATE_IRON_ORE
      - stone_ore: DIAMOND_ORE
        deepslate_ore: DEEPSLATE_DIAMOND_ORE
`;
    writeFileSync(join(XRAY_CONFIG_DIR, 'config.yml'), configContent);
}

function removePlugin() {
    if (existsSync(join(PLUGINS_DIR, 'XrayFix.jar'))) rmSync(join(PLUGINS_DIR, 'XrayFix.jar'));
    if (existsSync(XRAY_CONFIG_DIR)) rmSync(XRAY_CONFIG_DIR, { recursive: true, force: true });
}

async function runTest1() {
    console.log("=== RUN 1: VANILLA ===");
    clearWorld();
    removePlugin();
    await runServerAndBot('run1');
}

async function runTest2() {
    console.log("=== RUN 2: CUSTOM GEN ONLY ===");
    clearWorld();
    buildPlugin();
    writeXrayConfig(false);
    await runServerAndBot('run2');
}

async function runTest3() {
    console.log("=== RUN 3: OBFUSCATION ===");
    // Do not clear world!
    writeXrayConfig(true);
    await runServerAndBot('run3');
}

async function main() {
    await prepareServer();
    
    await runTest1();
    await runTest2();
    await runTest3();
    
    console.log("=== COMPARING RESULTS ===");
    const run1 = await import('./run1.json');
    const run2 = await import('./run2.json');
    const run3 = await import('./run3.json');
    
    let overlap = 0;
    const run2Set = new Set(run2.default.map((o: any) => `${o.x},${o.y},${o.z}`));
    for (const ore of run1.default as any[]) {
        if (run2Set.has(`${ore.x},${ore.y},${ore.z}`)) {
            overlap++;
        }
    }
    const overlapPercentage = (overlap / run1.default.length) * 100;
    console.log(`Run 1 total ores: ${run1.default.length}`);
    console.log(`Run 2 total ores: ${run2.default.length}`);
    console.log(`Overlap: ${overlap} ores (${overlapPercentage.toFixed(2)}%)`);
    
    if (overlapPercentage > 10) {
        console.error("FAILED: Ore placement overlap is too high! Seed-based x-ray protection failed.");
        process.exitCode = 1;
    } else {
        console.log("PASSED: Ore placement differs significantly (Seed-based x-ray protected).");
    }
    
    console.log(`Run 3 total ores: ${run3.default.length}`);
    if (run3.default.length >= run2.default.length * 2) {
        console.log("PASSED: Obfuscation successfully increased apparent ore count.");
    } else {
        console.error("FAILED: Obfuscation did not double the ore count!");
        process.exitCode = 1;
    }
}

main().catch(console.error);
