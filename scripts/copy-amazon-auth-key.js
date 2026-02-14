#!/usr/bin/env node

/**
 * Cordova hook: copy AppstoreAuthenticationKey.pem to the Android assets folder.
 *
 * The Amazon Appstore SDK 3.x requires this file for DRM verification.
 * If the file exists in the Cordova project root or the current working
 * directory, it is copied into platforms/android/app/src/main/assets/.
 */

var fs = require('fs');
var path = require('path');

module.exports = function (context) {
    var projectRoot = context.opts.projectRoot;
    var destDir = path.join(projectRoot, 'platforms', 'android', 'app', 'src', 'main', 'assets');
    var destFile = path.join(destDir, 'AppstoreAuthenticationKey.pem');
    var fileName = 'AppstoreAuthenticationKey.pem';

    // Already copied — skip.
    if (fs.existsSync(destFile)) {
        console.log('[AmazonIAP] AppstoreAuthenticationKey.pem already in Android assets.');
        return;
    }

    // Search for the PEM file in several locations:
    // 1. Cordova project root
    // 2. Current working directory (e.g. Bitrise repo root)
    // 3. One level above the Cordova project root
    var searchPaths = [
        path.join(projectRoot, fileName),
        path.join(process.cwd(), fileName),
        path.join(projectRoot, '..', fileName),
    ];

    var srcFile = null;
    for (var i = 0; i < searchPaths.length; i++) {
        var candidate = searchPaths[i];
        if (fs.existsSync(candidate)) {
            srcFile = candidate;
            break;
        }
    }

    if (!srcFile) {
        console.log('[AmazonIAP] AppstoreAuthenticationKey.pem not found. Searched:');
        for (var j = 0; j < searchPaths.length; j++) {
            console.log('  - ' + searchPaths[j]);
        }
        return;
    }

    // Check if Android platform is present
    if (!fs.existsSync(path.join(projectRoot, 'platforms', 'android'))) {
        return;
    }

    if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
    }

    fs.copyFileSync(srcFile, destFile);
    console.log('[AmazonIAP] Copied AppstoreAuthenticationKey.pem from ' + srcFile + ' to Android assets.');
};
