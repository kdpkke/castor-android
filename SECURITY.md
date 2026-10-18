# Security

This is the independently maintained Castor Android project. Report Android issues in [kdpkke/castor-android](https://github.com/kdpkke/castor-android/issues). For a sensitive disclosure, contact this repository’s maintainer privately through their GitHub profile; do not publish credentials or private stream URLs. Shared-core issues can also be reported to upstream Castor. The core security guidance below remains applicable.

Castor collects no telemetry.

## Keeping secrets out of git

Use git-ignored `config.local.yaml` (overlays `config.yaml`):

```yaml
tmdb:
  api_key: "<key>"
```

Environment variables such as `CASTOR_TMDB__API_KEY` override both files. Revoke accidentally committed keys. The TMDB key is used only by the interactive browser and sent only to [TMDB](https://www.themoviedb.org/settings/api).

## API, media, and scraping services

- **Authenticate RPCs:** set independent `servers.api.token`, `servers.media.token`, and `servers.scraping.token`; use `Authorization: Bearer <token>`. Health checks are exempt. Default ports `:8411`, `:8410`, and `:8412` bind all interfaces and warn if exposed without tokens.
- **Restrict network access:** unauthenticated callers can start casts or make scraping visit URLs, including its local network. Media accepts streams, not webpage inputs.
- **Device playback is open:** `/media` cannot require authentication. Anyone who can reach media and knows a cast URL can fetch it; `servers.media.advertise` defines the device-reachable address.
- **Protect credentials in transit:** RPCs use plain HTTP by default and carry tokens, stream URLs, and replay headers. Castor serves plain HTTP only: beyond trusted networks, terminate TLS in a reverse proxy and point clients at its `https://` URL. IP-bound streams may require scraping and media to share an outbound IP.
- **Keep replay headers private:** they reach media but are stripped from public cast listings.

## Headless Chrome

Only scraping runs Chrome and reads `browser`/`capture` settings. It uses an isolated profile and fresh randomized fingerprint, not your browser's cookies or passwords. It visits supplied pages and their loaded resources, following only HTTP(S) frames.

## Image provenance

Release images include an SPDX SBOM and SLSA provenance from the [delivery workflow](.github/workflows/_delivery.yml):

```sh
docker buildx imagetools inspect ghcr.io/stupside/castor:latest --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/stupside/castor:latest --format '{{ json .Provenance }}'
```

## Reporting a vulnerability

For the upstream core, use [stupside/castor](https://github.com/stupside/castor/issues) or the upstream maintainer’s private contact. For Android-specific vulnerabilities, use this project’s private contact described above.
