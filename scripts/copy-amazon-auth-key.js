#!/usr/bin/env node

/**
 * Cordova hook: copy AppstoreAuthenticationKey.pem to the Android assets folder.
 *
 * The Amazon Appstore SDK 3.x requires this file for DRM verification.
 * If the file exists in the Cordova project root, it is copied into
 * platforms/android/app/src/main/assets/ during the build.
 */

var fs = require('fs');
var path = require('path');

module.exports = function (context) {
    var projectRoot = context.opts.projectRoot;
    var srcFile = path.join(projectRoot, 'AppstoreAuthenticationKey.pem');
    var destDir = path.join(projectRoot, 'platforms', 'android', 'app', 'src', 'main', 'assets');
    var destFile = path.join(destDir, 'AppstoreAuthenticationKey.pem');

    console.log('Looking for AppstoreAuthenticationKey.pem in ' + projectRoot + ' and copying it to ' + destDir + ' if it exists.');

    if (!fs.existsSync(srcFile)) {
        // File not present in project root — nothing to do.
        console.log('AppstoreAuthenticationKey.pem not found in project root. Skipping copy.');
        return;
    }

    if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
    }

    fs.copyFileSync(srcFile, destFile);
    console.log('Copied AppstoreAuthenticationKey.pem to Android assets.');
};
