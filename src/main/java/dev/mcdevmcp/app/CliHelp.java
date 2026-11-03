package dev.mcdevmcp.app;

import java.util.Arrays;
import java.util.Set;

final class CliHelp {
    private static final Set<String> COMMANDS = Set.of("serve", "init", "callgraph", "rebuild", "status", "clean");
    private static final String ROOT = """
                                       Usage: mcdev-mcp [options] [command]
                                       
                                       MCP server for Minecraft mod development
                                       
                                       Options:
                                         -V, --version        output the version number
                                         -h, --help           display help for command
                                       
                                       Commands:
                                         serve                Start the MCP server over stdio (used by MCP clients, not
                                                              humans)
                                         init [options]       Download, decompile, index Minecraft sources, and
                                                              generate callgraph
                                         callgraph [options]  Generate callgraph database for finding method references
                                         rebuild [options]    Rebuild the symbol index from cached sources
                                         status [options]     Show current status of all cached Minecraft versions
                                         clean [options]      Remove cached data and index
                                         help [command]       display help for command
                                       """;
    private static final String SERVE = """
                                        Usage: mcdev-mcp serve [options]
                                        
                                        Start the MCP server over stdio (used by MCP clients, not humans)
                                        
                                        Options:
                                          -h, --help  display help for command
                                        """;
    private static final String INIT = """
                                       Usage: mcdev-mcp init [options]
                                       
                                       Download, decompile, index Minecraft sources, and generate callgraph
                                       
                                       Options:
                                         -v, --version <version>  Minecraft version (e.g., 1.21.11, 26.1)
                                         --skip-callgraph         Skip callgraph generation (default: false)
                                         -h, --help               display help for command
                                       """;
    private static final String CALLGRAPH = """
                                            Usage: mcdev-mcp callgraph [options]
                                            
                                            Generate callgraph database for finding method references
                                            
                                            Options:
                                              -v, --version <version>  Minecraft version (e.g., 1.21.11, 26.1)
                                              -h, --help               display help for command
                                            """;
    private static final String REBUILD = """
                                          Usage: mcdev-mcp rebuild [options]
                                          
                                          Rebuild the symbol index from cached sources
                                          
                                          Options:
                                            -v, --version <version>  Minecraft version (e.g., 1.21.11, 26.1)
                                            --with-callgraph         Also rebuild callgraph (default: false)
                                            -h, --help               display help for command
                                          """;
    private static final String STATUS = """
                                         Usage: mcdev-mcp status [options]
                                         
                                         Show current status of all cached Minecraft versions
                                         
                                         Options:
                                           -v, --version <version>  Show status for specific version
                                           -h, --help               display help for command
                                         """;
    private static final String CLEAN = """
                                        Usage: mcdev-mcp clean [options]
                                        
                                        Remove cached data and index
                                        
                                        Options:
                                          --callgraph              Only clean callgraph data
                                          --cache                  Clean cache directory (decompiled sources)
                                          --index                  Clean index directory (symbol index)
                                          --all                    Clean everything (cache, index, temporary analysis state)
                                          -v, --version <version>  Clean data for specific version only
                                          -h, --help               display help for command
                                        """;

    private CliHelp() {
    }

    static Preflight preflight(String[] arguments) {
        if (arguments.length == 0) {
            return new Preflight(1, "", ROOT);
        }
        String command = arguments[0];
        if (isRootHelp(arguments)) {
            return new Preflight(0, ROOT, "");
        }
        if (command.equals("help")) {
            if (arguments.length == 1) {
                return new Preflight(0, ROOT, "");
            }
            String help = commandHelp(arguments[1]);
            if (help != null) {
                return new Preflight(0, help, "");
            }
            return new Preflight(1, "", ROOT);
        }
        if (!command.startsWith("-") && !COMMANDS.contains(command)) {
            return new Preflight(1, "", "error: unknown command '" + command + "'\n");
        }
        if (COMMANDS.contains(command) && Arrays.stream(arguments).anyMatch(CliHelp::isHelpOption)) {
            return new Preflight(0, commandHelp(command), "");
        }
        if (requiresVersion(command) && !hasVersionOption(arguments)) {
            return new Preflight(1, "", "error: required option '-v, --version <version>' not specified\n");
        }
        return null;
    }

    private static boolean isRootHelp(String[] arguments) {
        return isHelpOption(arguments[0]);
    }

    private static boolean isHelpOption(String argument) {
        return argument.equals("-h") || argument.equals("--help");
    }

    private static boolean requiresVersion(String command) {
        return command.equals("init") || command.equals("callgraph") || command.equals("rebuild");
    }

    private static boolean hasVersionOption(String[] arguments) {
        return Arrays.stream(arguments).anyMatch(argument -> argument.equals("-v") || argument.equals("--version") || argument.startsWith("--version="));
    }

    private static String commandHelp(String command) {
        return switch (command) {
            case "serve" -> SERVE;
            case "init" -> INIT;
            case "callgraph" -> CALLGRAPH;
            case "rebuild" -> REBUILD;
            case "status" -> STATUS;
            case "clean" -> CLEAN;
            default -> null;
        };
    }

    value record Preflight(int exitCode, String stdout, String stderr) {
    }
}