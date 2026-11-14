# Zally Server

This is Zally's heart - Zally Server. It implements all rule checks and offers an
API to request an API linting. It also provides permalinks and statistics functionalities.

## Build and Run

1. Clone Zally repository

```bash
git clone git@github.com:zalando/zally.git zally
```

1. Switch to `server` folder:

```bash
cd zally/server
```

1. Build the server:

```bash
./gradlew clean build
```

1. Fix linter violations

```bash
./gradlew spotlessApply
```

1. Run Zally server using:

```bash
./gradlew bootRun
```

The bootRun task is configured to run with 'dev' profile by default.
## Fetching API definitions from URLs

When a request contains `apiDefinitionUrl`, the server fetches the definition itself. To prevent server-side
request forgery, only `http`/`https` URLs whose host resolves to a public address are fetched (this is checked
again for every redirect hop). It can be tuned with these properties (or the matching environment variables):

| Property | Default | Description |
| --- | --- | --- |
| `zally.api-definition-url.allowed-hosts` | _(empty, any public host)_ | Comma separated hosts to allow; `*.example.com` matches subdomains |
| `zally.api-definition-url.allow-private-network` | `false` | Allow loopback, link-local and private addresses. Only enable when untrusted callers cannot reach the server |
| `zally.api-definition-url.connect-timeout` | `5s` | Connect timeout |
| `zally.api-definition-url.read-timeout` | `10s` | Socket read timeout |
| `zally.api-definition-url.max-redirects` | `5` | Maximum number of redirects to follow (`0` disables redirects) |
| `zally.api-definition-url.max-response-size` | `10MB` | Maximum size of a fetched API definition |
