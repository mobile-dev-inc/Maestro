#!/usr/bin/env node
/**
 * Maestro Executor for Claude Code
 * * Wraps the Maestro CLI to execute YAML flows.
 * Handles PATH resolution and execution logging.
 */

const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');
const os = require('os');

// Set HOME for Maestro path resolution
const HOMEDIR = os.homedir();
const MAESTRO_BIN = path.join(HOMEDIR, '.maestro', 'bin', 'maestro');

// Add Maestro to PATH for this process
process.env.PATH = `${path.dirname(MAESTRO_BIN)}:${process.env.PATH}`;

/**
 * Check if Maestro is executable
 */
function checkMaestroInstalled() {
  try {
    // Try explicit path first
    if (fs.existsSync(MAESTRO_BIN)) return MAESTRO_BIN;
    // Try PATH
    execSync('maestro --version', { stdio: 'ignore' });
    return 'maestro';
  } catch (e) {
    return false;
  }
}

/**
 * Get flow file to execute
 */
function getFlowToExecute() {
  const args = process.argv.slice(2);

  if (args.length > 0 && fs.existsSync(args[0])) {
    const filePath = path.resolve(args[0]);
    console.log(`📄 Executing flow: ${filePath}`);
    return filePath;
  }
  
  if (args.length > 0) {
    // Treat as inline content, write to temp
    const tempFile = path.join(os.tmpdir(), `maestro-inline-${Date.now()}.yaml`);
    fs.writeFileSync(tempFile, args.join(' '));
    console.log(`⚡ Executing inline flow via ${tempFile}`);
    return tempFile;
  }

  console.error('❌ No flow file provided');
  console.error('Usage: node run.js /path/to/flow.yaml');
  process.exit(1);
}

/**
 * Handle common Maestro errors and provide suggestions
 */
function handleMaestroError(output) {
  if (output.includes('APP_NOT_INSTALLED')) {
    console.log('\n💡 App not installed. To install:');
    console.log('iOS: xcrun simctl install booted /path/to/app.ipa');
    console.log('Android: adb install /path/to/app.apk');
    return true;
  }
  
  if (output.includes('DEVICE_NOT_FOUND')) {
    console.log('\n💡 No device found. To start a device:');
    console.log('iOS: xcrun simctl boot "iPhone 15"');
    console.log('Android: emulator @your_emulator_name');
    return true;
  }
  
  if (output.includes('ELEMENT_NOT_FOUND')) {
    console.log('\n💡 Element not found. Try:');
    console.log('1. Use "maestro hierarchy" to inspect the view');
    console.log('2. Add waitForAnimationToEnd before tapOn');
    console.log('3. Use scrollUntilVisible for off-screen elements');
    return true;
  }
  
  if (output.includes('PERMISSION_DENIED')) {
    console.log('\n💡 Permission denied. Check:');
    console.log('1. App permissions on the device');
    console.log('2. File permissions for screenshot paths');
    return true;
  }
  
  return false;
}

async function main() {
  console.log('🎹 Maestro Skill - Flow Executor\n');

  const maestroCmd = checkMaestroInstalled();
  if (!maestroCmd) {
    console.error('❌ Maestro CLI not found.');
    console.error('Please run: npm run setup');
    process.exit(1);
  }

  const flowFile = getFlowToExecute();

  // Execute Maestro test
  // We use 'test' command. 'cloud' would be for mobile.dev cloud.
  const child = spawn(maestroCmd, ['test', flowFile], {
    stdio: 'inherit',
    env: process.env
  });

  let output = '';
  child.stdout?.on('data', (data) => {
    output += data.toString();
  });
  child.stderr?.on('data', (data) => {
    output += data.toString();
  });

  child.on('close', (code) => {
    if (code === 0) {
        console.log('\n✅ Flow executed successfully');
    } else {
        console.error(`\n❌ Flow failed with exit code ${code}`);
        
        // Try to provide helpful suggestions
        if (!handleMaestroError(output)) {
          console.log('\n🔍 For more help:');
          console.log('1. Check if the app is installed');
          console.log('2. Verify device is connected and unlocked');
          console.log('3. Use "maestro hierarchy" to inspect UI elements');
          console.log('4. Check flow syntax with "maestro test --dry-run <file>"');
        }
    }
    process.exit(code);
  });
}

main().catch(error => {
  console.error('❌ Fatal error:', error.message);
  process.exit(1);
});