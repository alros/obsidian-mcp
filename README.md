# obsidian-mcp

A small, fast [MCP](https://modelcontextprotocol.io) server that gives Claude, GitHub Copilot and any other MCP
client **read-only** access to your Obsidian vaults, with per-vault tag rules that decide what's visible.

It's built with Java 25 and Quarkus, compiled to a native binary, and speaks MCP over stdio.

## Tools

| Tool | What it does |
|---|---|
| `list_vaults` | Configured vaults and the number of visible notes in each |
| `list_notes(vault?, tag?, offset?, limit?)` | `id`, `title` and `tags` of the visible notes. A tag filter also matches nested tags |
| `get_note(id)` | Note content as markdown, plus `links` (external URLs) and `linkedNotes` (visible notes it links to) |
| `search_notes(query, vault?, limit?)` | Case-insensitive full-text search. All terms must match, and `"quoted phrases"` work. Returns ranked results with snippets |

A note `id` is `<vault>/<path inside the vault>`, for example `work/Projects/Alpha.md`.

### What the model sees

The body is the note without its frontmatter, sanitized as follows:

- **Images** (`![[x.png]]`, `![alt](x.png)`, `<img>`, remote images too) → `[image: alt-or-filename]`
- **Other attachments** (`[[spec.pdf]]`, `![[audio.m4a]]`) → `[attachment: name]`
- **External links** (`http`, `https`, `ftp`) stay in the text and are also listed in `links`, so the model can decide whether to follow them
- **Links to visible notes** stay as written and are listed in `linkedNotes` with their ids
- **Links and embeds pointing to hidden or missing notes** → plain text (`[[Secret|alias]]` → `alias`, `![[Secret]]` → `Secret`). A hidden note looks exactly like a missing one
- **Other URI schemes** (`obsidian://…`, `file://…`) → link text only
- **Obsidian comments** `%%…%%` are removed (Obsidian doesn't show them either)
- **Code blocks and inline code** are left untouched
- `#tags` in the body stay as plain text and **don't** affect visibility. Only frontmatter tags do

## Configuration

Copy [`config.example.yaml`](config.example.yaml) to `~/.config/obsidian-mcp/config.yaml` and edit it:

```yaml
vaults:
  - name: work
    path: ~/Obsidian/Work
    allowed-tags: [project, howto]   # empty = everything
    ignored-tags: [private]          # always wins over allowed-tags
    show-untagged: false             # default
```

The server looks for its config in this order and uses the first file it finds:

1. `$OBSIDIAN_MCP_CONFIG`
2. `./.config/config.yaml`
3. `~/.config/obsidian-mcp/config.yaml`

Visibility rules:

- **Ignored tags win.** A note with both an allowed and an ignored tag is hidden.
- **Empty allow list:** every tagged note is visible unless it has an ignored tag.
- **Untagged notes** are visible only with `show-untagged: true`.
- **Matching** is case-insensitive and hierarchical: `project` matches `project/alpha` but not `projects`.
- **Skipped folders:** `.obsidian`, `.trash`, `.git` and other hidden folders are never scanned.

The index refreshes incrementally, at most once per second, and only when a tool is called. Edits you make in
Obsidian show up straight away without restarting the server, and nothing runs in the background while it's idle.

At startup the server logs the absolute path of the config file it uses and how it found it, plus each vault
with its rules, to stderr:

```
INFO  Using configuration file /Users/you/.config/obsidian-mcp/config.yaml (from user home)
INFO  Vault 'work' at /Users/you/Obsidian/Work (allowed-tags=[project], ignored-tags=[private], show-untagged=false)
```

Config errors (a missing vault folder, a misspelled key, a duplicate name) are logged to stderr and returned as
the error of every tool call, so they show up in your client.

## Build

You need GraalVM for JDK 25 (`brew install --cask graalvm-jdk`) and Maven.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

```bash
mvn verify -Dnative
```

The binary is written to `target/obsidian-mcp-1.0.0-SNAPSHOT-runner`. Install it somewhere stable:

```bash
cp target/obsidian-mcp-1.0.0-SNAPSHOT-runner ~/.local/bin/obsidian-mcp
```

To run the end-to-end stdio check against the bundled fixture vaults:

```bash
scripts/smoke-test.sh
```

Measured on an Apple Silicon Mac with the native binary (46 MB file):

| | |
|---|---|
| Launch to first MCP response | ~25 ms |
| Peak memory (small vault) | ~34 MB |
| Indexing 5,000 notes (39 MB) | ~200 ms |
| `get_note` | ~0.1 ms |
| `search_notes` over ~3,000 visible notes | ~30 ms (~300 ms if run in the first second, before the background warm-up finishes) |

To build a JVM version instead (no GraalVM needed, slower startup), run `mvn package` and use
`java -jar target/quarkus-app/quarkus-run.jar`.

## Run from the terminal

MCP clients normally start the server themselves, so you only need to run it by hand to try it out or to debug
it. The server reads JSON-RPC messages from stdin and writes responses to stdout. Logs go to stderr, so they never
mix with the protocol.

### 1. Point it at a config

Use your real config (`~/.config/obsidian-mcp/config.yaml`), or the bundled test vaults:

```bash
mkdir -p .config && sed "s|~/Obsidian/Work|$PWD/src/test/resources/vaults/work|; s|~/Obsidian/Personal|$PWD/src/test/resources/vaults/personal|" config.example.yaml > .config/config.yaml
```

That command writes `./.config/config.yaml`, which is picked up when you start the server from the project
folder. To use any other file, set `OBSIDIAN_MCP_CONFIG=/path/to/config.yaml`.

### 2. Start the server

Native binary (after `mvn verify -Dnative`):

```bash
target/obsidian-mcp-1.0.0-SNAPSHOT-runner
```

Or the JVM build (after `mvn package`):

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

The server prints `Loaded …` and `Indexed N visible notes …` to stderr, then waits silently for input. Stop it
with `Ctrl-D` or `Ctrl-C`.

### 3. Talk to it

Paste these lines one at a time. Each request is a single line of JSON, and every request with an `id` gets a
one-line JSON response:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"terminal","version":"1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_notes","arguments":{}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_note","arguments":{"id":"work/Projects/Alpha.md"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"search_notes","arguments":{"query":"quarkus"}}}
```

For a one-shot call with readable output, pipe the requests in and format the result with `jq`:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"cli","version":"1"}}}' '{"jsonrpc":"2.0","method":"notifications/initialized"}' '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_notes","arguments":{}}}' | target/obsidian-mcp-1.0.0-SNAPSHOT-runner 2>/dev/null | jq -r 'select(.id==2) | .result.content[0].text | fromjson'
```

### Or use the MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) gives you a web UI where you pick a tool,
fill in its arguments and see the response.

It needs a recent Node.js: version 2.x requires Node 22.23.4+, 24.22.1+ or 26.12.1+. On an older Node, such as
16, npm installs only part of the package and fails with `sh: inspector: command not found`. With nvm:

```bash
nvm install 24 && nvm use 24
```

Then start it. Use `@latest`, so npx doesn't reuse a broken copy cached by an older Node:

```bash
npx @modelcontextprotocol/inspector@latest target/obsidian-mcp-1.0.0-SNAPSHOT-runner
```

To use a config other than `./.config/config.yaml` or `~/.config/obsidian-mcp/config.yaml`, pass it with `-e`:

```bash
npx @modelcontextprotocol/inspector@latest -e OBSIDIAN_MCP_CONFIG=$PWD/.config/myconfig.yaml target/obsidian-mcp-1.0.0-SNAPSHOT-runner
```

To call a single tool without opening the web UI, use `--cli`:

```bash
npx @modelcontextprotocol/inspector@latest --cli target/obsidian-mcp-1.0.0-SNAPSHOT-runner --method tools/call --tool-name search_notes --tool-arg query=quarkus
```

> **About `mvn quarkus:dev`:** dev mode uses the terminal for its own interactive console, which clashes with the
> stdio transport. To iterate on the code, run `mvn test`, or run `mvn package` and then `scripts/smoke-test.sh
> java -jar target/quarkus-app/quarkus-run.jar`.

## Client setup

In every example below, replace `/Users/you` with your home directory.

### Claude Code

```bash
claude mcp add obsidian --scope user -- /Users/you/.local/bin/obsidian-mcp
```

### Claude Desktop

Add this to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "obsidian": {
      "command": "/Users/you/.local/bin/obsidian-mcp"
    }
  }
}
```

### GitHub Copilot (VS Code, agent mode)

Add this to `.vscode/mcp.json` in a workspace, or run **MCP: Open User Configuration** to make it global:

```json
{
  "servers": {
    "obsidian": {
      "type": "stdio",
      "command": "/Users/you/.local/bin/obsidian-mcp"
    }
  }
}
```

Each client can use its own config file by setting `OBSIDIAN_MCP_CONFIG`. For example, in Claude Desktop add
`"env": { "OBSIDIAN_MCP_CONFIG": "/Users/you/.config/obsidian-mcp/work-only.yaml" }`.

## Layout

```
src/main/java/eu/rossotto/obsidian/
├── config/    YAML config model and loader
├── vault/     index (Vault), frontmatter, tag rules, markdown sanitizer (NoteRenderer)
├── service/   NoteRepository (lazy refresh, visibility boundary), SearchEngine
└── mcp/       ObsidianTools, the MCP tool definitions
```
