# Install a release

Every [GitHub release](https://github.com/alros/obsidian-mcp/releases) includes a native macOS binary for Apple
Silicon (`arm64`):

```bash
curl -fsSL https://github.com/alros/obsidian-mcp/releases/latest/download/obsidian-mcp-macos-arm64.tar.gz | tar xz
```

```bash
mkdir -p ~/.local/bin && mv obsidian-mcp-macos-arm64/obsidian-mcp ~/.local/bin/
```

The archive comes with a `.sha256` file to check it against. On Intel Macs, build from source.

The binaries aren't notarized by Apple. A file downloaded with `curl` runs as is, but if you download the archive
in a browser, macOS blocks the binary. To unblock it:

```bash
xattr -d com.apple.quarantine ~/.local/bin/obsidian-mcp
```

# Release

The [Release workflow](.github/workflows/release.yml) runs whenever you push a `v*` tag. It builds the native
binary on GitHub's Apple Silicon macOS runner, runs the tests and the stdio smoke test against the
binary, and publishes a GitHub release with the archives and auto-generated release notes:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

The version comes from the tag. You don't need to edit `pom.xml`, and the server reports that version to MCP
clients. Release binaries are built with `-march=compatibility`, so they run on any Mac of their architecture.
Local builds use `-march=native` instead.

The [CI workflow](.github/workflows/ci.yml) runs the tests and the JVM smoke test on every push to `main` and on
every pull request.
