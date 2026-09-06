"use strict";

const {spawn, spawnSync} = require("node:child_process");
const path = require("node:path");

const jar = path.join(__dirname, "mcdev-mcp.jar");
const javaCommand = "java";

function javaArguments(commandArguments) {
    return commandArguments;
}

const unresolvedUserConfig = new Set([
    "${user_config.script_logs}",
    "${user_config.run_command}",
    "${user_config.debug_log}",
    "${user_config.index_threads}",
    "${user_config.debugbridge_port}"
]);

/**
 * @param {NodeJS.ProcessEnv} environment
 * @returns {NodeJS.ProcessEnv}
 */
function childEnvironment(environment) {
    return /** @type {NodeJS.ProcessEnv} */ (Object.fromEntries(Object.entries(environment).filter(([, value]) => typeof value === "string" && !unresolvedUserConfig.has(value))));
}

/**
 * @param {NodeJS.Signals} signal
 * @param {import("node:child_process").ChildProcess} child
 */
function forwardSignal(signal, child) {
    if (!child.killed) {
        child.kill(signal);
    }
}

const version = spawnSync(javaCommand, javaArguments(["-version"]), {encoding: "utf8"});
const versionText = `${version.stdout}\n${version.stderr}`;
const match = versionText.match(/^\s*(?:openjdk|java)\s+version\s+["']?(\d+)(?=[.\-+_'"\s]|$)/im);
const feature = match === null ? NaN : Number.parseInt(match[1], 10);

if (version.error !== undefined) {
    process.stderr.write(`Unable to start Java: ${version.error.message}\n`);
    process.exitCode = 1;
} else if (version.status !== 0) {
    const outcome = version.signal === null ? `status ${version.status}` : `signal ${version.signal}`;
    process.stderr.write(`Unable to determine Java version: java -version exited with ${outcome}.\n`);
    process.exitCode = 1;
} else if (!Number.isInteger(feature) || feature < 26) {
    process.stderr.write(`Java 26 or newer is required; detected ${Number.isInteger(feature) ? `Java ${feature}` : "an unknown Java version"}.\n`);
    process.exitCode = 1;
} else {
    const child = spawn(javaCommand, javaArguments(["-jar", jar, "serve"]), {
        env: childEnvironment(process.env),
        stdio: "inherit"
    });

    process.on("SIGINT", () => forwardSignal("SIGINT", child));
    process.on("SIGTERM", () => forwardSignal("SIGTERM", child));

    child.once("error", (error) => {
        process.stderr.write(`Unable to start mcdev-mcp: ${error.message}\n`);
        process.exitCode = 1;
    });
    child.once("exit", (code, signal) => {
        if (signal !== null) {
            process.kill(process.pid, signal);
            return;
        }
        process.exitCode = code === null ? 1 : code;
    });
}
