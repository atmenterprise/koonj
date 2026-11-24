# Koonj MCP integration

Koonj exposes a project-scoped MCP server over HTTP with Server-Sent Events. The connection uses the MCP `2024-11-05` SSE transport. An SSE stream announces a session-specific POST endpoint. The client sends JSON-RPC requests there and reads replies from the original stream. [MCP transport specification](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)

## Generate a Personal Access Token

1. Select your project in Koonj.
2. Open **Integration → MCP & AI Assistant → Generate New Token**.
3. Name the token and optionally choose an expiration. Copy the secret or use the populated client configuration. The API reveals the secret once at creation and stores a SHA-256 digest.
4. Select **Test connection** to initialize a session and list the tools available to the new token.

Tokens use your Koonj roles at issuance. Changes to Koonj’s role permissions and project membership apply to existing tokens. Revoke existing tokens when changing a user’s identity-provider role assignment, then issue replacements with the new roles. Revocation stops new requests immediately and the heartbeat closes existing streams within 20 seconds under normal operation. Already queued jobs continue under Koonj’s job controls.

Use a private client configuration or secret store and keep PATs out of version control. Set `Authorization: Bearer <PAT>` on both HTTP endpoints. The `X-Koonj-Project` header is optional because the project is part of the token. If you send it, use the token’s project ID.

## Claude Desktop

Install Node.js 20 or later and use `mcp/mcp-bridge.mjs` from the extracted deployment bundle or [mcp-bridge.mjs](../scripts/mcp-bridge.mjs) from the repository. Put this entry under `mcpServers` in `claude_desktop_config.json`. Use absolute paths to your Node executable and the script. On Windows, double backslashes in JSON paths, or use forward slashes.

```json
{
  "mcpServers": {
    "koonj": {
      "command": "/absolute/path/to/node",
      "args": [
        "/absolute/path/to/mcp/mcp-bridge.mjs",
        "--url",
        "https://koonj.example.com/api/v1/mcp/sse",
        "--project",
        "00000000-0000-0000-0000-000000000001"
      ],
      "env": {
        "KOONJ_TOKEN": "YOUR_KOONJ_TOKEN"
      }
    }
  }
}
```

Find the file at `~/Library/Application Support/Claude/claude_desktop_config.json` on macOS or `%APPDATA%\Claude\claude_desktop_config.json` on Windows. Fully quit and reopen Claude Desktop after editing it. The bridge also accepts `--token` for scripted launches. Use `KOONJ_TOKEN` to keep the secret out of process arguments. [MCP guide for Claude Desktop](https://py.sdk.modelcontextprotocol.io/get-started/real-host/)

## Cursor

Save the following in your private `~/.cursor/mcp.json` or a private project-level `.cursor/mcp.json`. If your Cursor build requires a stdio server, use the Claude Desktop entry in the same file. [Cursor MCP configuration](https://cursor.com/docs/mcp)

```json
{
  "mcpServers": {
    "koonj": {
      "url": "https://koonj.example.com/api/v1/mcp/sse",
      "headers": {
        "Authorization": "Bearer ${env:KOONJ_TOKEN}",
        "X-Koonj-Project": "00000000-0000-0000-0000-000000000001"
      }
    }
  }
}
```

## Windsurf

Add the following to `~/.codeium/windsurf/mcp_config.json` and refresh the MCP servers in Windsurf. The Claude Desktop entry also works as a stdio configuration.

```json
{
  "mcpServers": {
    "koonj": {
      "serverUrl": "https://koonj.example.com/api/v1/mcp/sse",
      "headers": {
        "Authorization": "Bearer YOUR_KOONJ_TOKEN",
        "X-Koonj-Project": "00000000-0000-0000-0000-000000000001"
      }
    }
  }
}
```

## Custom Python agents and LangChain

Install the MCP Python SDK with `python -m pip install mcp`. Set `KOONJ_MCP_URL` to the SSE URL, `KOONJ_PROJECT` to your project UUID, and `KOONJ_TOKEN` to your PAT. [MCP Python SSE client](https://py.sdk.modelcontextprotocol.io/client/transports/)

```python
import asyncio
import os

from mcp import ClientSession
from mcp.client.sse import sse_client

async def main():
    headers = {
        "Authorization": f"Bearer {os.environ['KOONJ_TOKEN']}",
        "X-Koonj-Project": os.environ["KOONJ_PROJECT"],
    }
    async with sse_client(os.environ["KOONJ_MCP_URL"], headers=headers) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            print([tool.name for tool in (await session.list_tools()).tools])
            result = await session.call_tool("search_catalog_files", {"limit": 10})
            print(result.content[0].text)
            if job_id := os.environ.get("KOONJ_JOB_ID"):
                status = await session.call_tool("get_job_status", {"jobId": job_id})
                print(status.content[0].text)

asyncio.run(main())
```

For an agent using `langchain-mcp-adapters`, configure `MultiServerMCPClient` with an explicit SSE transport. [LangChain MCP adapters](https://github.com/langchain-ai/langchain-mcp-adapters)

```python
import asyncio
import os

from langchain_mcp_adapters.client import MultiServerMCPClient

async def load_tools():
    client = MultiServerMCPClient({
        "koonj": {
            "transport": "sse",
            "url": os.environ["KOONJ_MCP_URL"],
            "headers": {
                "Authorization": f"Bearer {os.environ['KOONJ_TOKEN']}",
                "X-Koonj-Project": os.environ["KOONJ_PROJECT"],
            },
        }
    })
    return await client.get_tools()

async def main():
    print([tool.name for tool in await load_tools()])

asyncio.run(main())
```

## Check the connection with curl

Set `KOONJ_BASE_URL` to the Koonj origin and set `KOONJ_TOKEN` and `KOONJ_PROJECT` as above. Leave the first terminal running.

```sh
curl -sS -N "$KOONJ_BASE_URL/api/v1/mcp/sse" \
  -H 'Accept: text/event-stream' \
  -H "Authorization: Bearer $KOONJ_TOKEN" \
  -H "X-Koonj-Project: $KOONJ_PROJECT"
```

Copy the relative URL from the stream’s `endpoint` event into `KOONJ_MESSAGE_PATH` in a second terminal. Wait for the initialization response before sending the initialized notification. POSTs return HTTP 202 and the first terminal displays JSON-RPC replies.

```sh
mcp_post() {
  curl -fsS "$KOONJ_BASE_URL$KOONJ_MESSAGE_PATH" \
    -H "Authorization: Bearer $KOONJ_TOKEN" \
    -H "X-Koonj-Project: $KOONJ_PROJECT" \
    -H 'Content-Type: application/json' -d "$1"
}

mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
mcp_post '{"jsonrpc":"2.0","method":"notifications/initialized"}'
mcp_post '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
mcp_post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_catalog_files","arguments":{"limit":10}}}'
```

To inspect a job, set `KOONJ_JOB_ID` to a job ID you own in this project.

```sh
mcp_post "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_job_status\",\"arguments\":{\"jobId\":\"$KOONJ_JOB_ID\"}}}"
```

## Tool permissions

| Tool | Required permission |
| --- | --- |
| `list_storage_locations`, `search_catalog_files`, `get_file_details`, `get_file_lineage` | `CATALOG_VIEW` |
| `get_storage_status` | `STORAGE_VIEW` |
| `queue_archive` | `TRANSFER_RUN`, plus `TRANSFER_PURGE` for `PURGE` |
| `queue_recall`, `sync_recall_file` | `TRANSFER_RUN` |
| `get_job_status` | `ACTIVITY_VIEW` and job ownership |
| `control_job` | `ACTIVITY_CONTROL` and job ownership. Resume and retry also need permissions to run the original operation. |
| `trigger_catalog_scan` | `CATALOG_OPERATE` |
| `get_system_health` | `OBSERVABILITY_VIEW` |

Search uses the project’s last-modified thresholds for `HOT`, `WARM`, and `COLD`. The response includes `archiveState` separately. Pages start at zero and contain up to 50 entries. Pass the file ID to `get_file_details` for the full path and metadata. Monitor queued jobs using the returned `jobId`. Permission denials appear as MCP tool results with `isError: true` and leave the session open.

## Deployment

See the [deployment guide](DEPLOYMENT-GUIDE.md#mcp-server) for the database migration, Nginx buffering configuration, and multi-instance routing. For browser SSE clients that cannot set headers on the GET, `/api/v1/mcp/sse?token=<PAT>` accepts a query token. Send the PAT in the Authorization header on POST. Keep query strings out of proxy and application logs and prefer headers wherever possible.
