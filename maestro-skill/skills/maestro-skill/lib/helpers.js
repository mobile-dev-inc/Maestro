const { execSync } = require('child_process');
const os = require('os');
const path = require('path');
const fs = require('fs');

const MAESTRO_BIN = path.join(os.homedir(), '.maestro', 'bin', 'maestro');

function getMaestroCommand() {
    if (fs.existsSync(MAESTRO_BIN)) return MAESTRO_BIN;
    try {
        execSync('maestro --version', { stdio: 'ignore' });
        return 'maestro';
    } catch (e) {
        return null;
    }
}

/**
 * Checks if a specific app is installed on iOS or Android
 */
async function checkAppInstalled(platform, appId) {
    if (platform === 'ios') {
        try {
            const apps = execSync('xcrun simctl listapps booted', { encoding: 'utf8' });
            return apps.includes(appId);
        } catch (e) {
            return false;
        }
    } else if (platform === 'android') {
        try {
            const packages = execSync('adb shell pm list packages', { encoding: 'utf8' });
            return packages.includes(appId.replace(/^[^\.]+\./, '')); // Remove domain prefix
        } catch (e) {
            return false;
        }
    }
    return false;
}

/**
 * Gets installed apps for a platform (limited list)
 */
async function getInstalledApps(platform) {
    if (platform === 'ios') {
        try {
            const apps = execSync('xcrun simctl listapps booted', { encoding: 'utf8' });
            // Extract bundle IDs from output
            const bundleIdMatch = apps.match(/Application bundle IDs:\n((?:.+\n)+)/);
            if (bundleIdMatch) {
                return bundleIdMatch[1].split('\n').filter(id => id.trim()).slice(0, 20); // Limit to first 20
            }
        } catch (e) {
            return [];
        }
    } else if (platform === 'android') {
        try {
            const packages = execSync('adb shell pm list packages', { encoding: 'utf8' });
            return packages.split('\n')
                .filter(line => line.startsWith('package:'))
                .map(line => line.replace('package:', '').trim())
                .slice(0, 20); // Limit to first 20
        } catch (e) {
            return [];
        }
    }
    return [];
}

/**
 * Checks environment health: Maestro install status and connected devices.
 */
async function checkEnvironment() {
    const status = {
        maestro_installed: false,
        maestro_version: null,
        devices: [],
        apps_check: {}
    };

    const cmd = getMaestroCommand();
    
    if (cmd) {
        status.maestro_installed = true;
        try {
            status.maestro_version = execSync(`${cmd} --version`, { encoding: 'utf8' }).trim();
        } catch (e) {}

        try {
            // Check Android (ADB)
            try {
                const adbOut = execSync('adb devices', { encoding: 'utf8' });
                const adbLines = adbOut.split('\n').slice(1);
                const androidDevices = [];
                adbLines.forEach(line => {
                    if (line.includes('device') && !line.includes('offline')) {
                        const deviceId = line.split('\t')[0];
                        androidDevices.push({ platform: 'android', id: deviceId });
                    }
                });
                
                if (androidDevices.length > 0) {
                    status.devices.push(...androidDevices);
                    status.apps_check.android = {
                        device_count: androidDevices.length,
                        sample_apps: await getInstalledApps('android')
                    };
                }
            } catch (e) { /* ADB not found or failed */ }

            // Check iOS (Simctl) - Mac only
            if (process.platform === 'darwin') {
                try {
                    const simOut = execSync('xcrun simctl list devices booted -j', { encoding: 'utf8' });
                    const simJson = JSON.parse(simOut);
                    const iosDevices = [];
                    for (const runtime in simJson.devices) {
                        simJson.devices[runtime].forEach(dev => {
                            if (dev.state === 'Booted') {
                                iosDevices.push({ 
                                    platform: 'ios', 
                                    id: dev.udid, 
                                    name: dev.name,
                                    os: dev.runtime 
                                });
                            }
                        });
                    }
                    
                    if (iosDevices.length > 0) {
                        status.devices.push(...iosDevices);
                        status.apps_check.ios = {
                            device_count: iosDevices.length,
                            sample_apps: await getInstalledApps('ios')
                        };
                    }
                } catch (e) { /* xcrun not found */ }
            }

        } catch (e) {
            console.error("Error detecting devices:", e.message);
        }
    }

    return status;
}

module.exports = {
    checkEnvironment,
    checkAppInstalled,
    getInstalledApps
};