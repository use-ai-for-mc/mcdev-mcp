# The platforms a release is packaged for. Each entry describes the application image as jpackage
# lays it out on that platform and the name an MCPB manifest gives its operating system.
@{
    'linux-x64'     = @{
        System      = 'linux'
        Manifest    = 'manifest-linux.json'
        Root        = 'mcdev-mcp/'
        Launcher    = 'mcdev-mcp/bin/mcdev-mcp'
        Application = 'mcdev-mcp/lib/app/'
    }
    'macos-aarch64' = @{
        System      = 'darwin'
        Manifest    = 'manifest-macos.json'
        Root        = 'mcdev-mcp.app/'
        Launcher    = 'mcdev-mcp.app/Contents/MacOS/mcdev-mcp'
        Application = 'mcdev-mcp.app/Contents/app/'
    }
    'windows-x64'   = @{
        System      = 'win32'
        Manifest    = 'manifest-windows.json'
        Root        = 'mcdev-mcp/'
        Launcher    = 'mcdev-mcp/mcdev-mcp.exe'
        Application = 'mcdev-mcp/app/'
    }
}
