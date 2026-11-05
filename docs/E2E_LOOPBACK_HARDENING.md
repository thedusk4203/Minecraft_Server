# E2E Security Hardening — Loopback-only Synthetic Minecraft Listeners

Status: **IMPLEMENTATION PROPOSED IN PR; NOT A PRODUCTION AUTHENTICATION GATE**

Tracking: [#49](https://github.com/thedusk4203/Minecraft_Server/issues/49); additional staging requirements [#47](https://github.com/thedusk4203/Minecraft_Server/issues/47); parent Java/Bedrock identity blocker [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42).

## Verified upstream behavior

The pinned E2E translation bridge runs **ViaProxy 3.4.14**, SHA-256 `2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5`. Its actual upstream source for tag `v3.4.14` defines `@Option("bind-address")` and defaults to `0.0.0.0:25568` in `ViaProxyConfig`; the main server binds `CONFIG.getBindAddress()`. See:

- [ViaProxy v3.4.14 ViaProxyConfig.java](https://github.com/ViaVersion/ViaProxy/blob/v3.4.14/src/main/java/net/raphimc/viaproxy/protocoltranslator/viaproxy/ViaProxyConfig.java)
- [ViaProxy v3.4.14 ViaProxy.java](https://github.com/ViaVersion/ViaProxy/blob/v3.4.14/src/main/java/net/raphimc/viaproxy/ViaProxy.java)

Neither the E2E Paper backend nor ViaProxy needs to accept a remote public connection. All test clients and the ViaProxy bridge operate on the same Linux GitHub Actions host via `127.0.0.1`.

## Changed runtime configuration — E2E only

1. `compose.e2e.yaml` changes **only** the test Paper port from `25567:25565/tcp` (all interfaces by default) to `127.0.0.1:25567:25565/tcp`. PostgreSQL remains unexposed.
2. `tools/e2e/start-viaproxy.sh` adds the upstream-supported `--bind-address 127.0.0.1:25568` CLI argument, retaining `--target-address 127.0.0.1:25567` and pinned `--target-version 26.3`.
3. `tools/security/verify_e2e_loopback.py` provides standard-library-only static and live listener checks. No credentials or Compose JSON are printed.
4. `.github/workflows/ci.yml` includes the static validator + **18 deterministic unit/negative mutation tests** before starting E2E containers; immediately after ViaProxy startup it verifies the actual Linux TCP listener is **exactly one** loopback-only TCP socket on port 25568: either IPv4 `127.0.0.1` or the equivalent Java/Netty IPv4-mapped IPv6 address `::ffff:127.0.0.1`. IPv6 wildcard `::`, IPv4 wildcard `0.0.0.0`, unrelated IPv6 endpoints and duplicate listeners are rejected. Existing gameplay E2Es remain unchanged.

The static model rejects wildcard Paper host interfaces, additional/incorrect port mappings, PostgreSQL publication, unknown Compose services, host-network/privileged services, wrong Paper offline mode, wrong ViaProxy target or wildcard bind. Runtime reads `/proc/net/tcp` and `/proc/net/tcp6` without dumping addresses or process environment; missing or wildcard ViaProxy listeners fail closed.

### Local reproduction

With Docker Compose, Python 3.11+, Java 25 and the project's normal temporary E2E environment:

```bash
python3 tools/security/verify_e2e_loopback.py --self-test
python3 tools/security/verify_e2e_loopback.py
docker compose -f compose.e2e.yaml up -d --wait
bash tools/e2e/start-viaproxy.sh
python3 tools/security/verify_e2e_loopback.py --runtime
```

The launch script uses `RUNNER_TEMP` for the synthetic translation bridge and `tools/e2e/stone-warden*.cjs`, `stone-vault*.cjs` continue targeting loopback.


### Runtime finding and fix

The first head CI run #229 (commit `6cd4b797e15ee30c31ad0d9f5780bc4ce7fbe237`) proved that ViaProxy starts successfully but the original runtime guard erroneously rejected an IPv4-mapped IPv6 listener. Pinned ViaProxy logs reported `127.0.0.1:25568`, while Linux listed the Java/Netty listener in `/proc/net/tcp6`, not `/proc/net/tcp`. The revised guard recognizes the exact little-endian `/proc/net/tcp6` encoding of `::ffff:127.0.0.1`; it **does not** accept `::` or other IPv6 interfaces. A matching new positive regression test and further negative cases ensure we do not loosen the security boundary to accommodate the runtime behavior. The corrected head requires a fresh complete CI result before merge.

## Non-goals and remaining risks

**Passing this guard does NOT verify a real account or prove production network isolation.** It only limits the **synthetic offline-mode E2E harness** on its Linux host.

- `compose.yaml` for normal development retains a separate production-like Velocity online-mode and modern forwarding topology, which has its own static security guard added in PR #48.
- Real Java and real Bedrock/Floodgate/XUID login verification is an **open staging gate** in #47.
- A separate deployment firewall review is still needed; loopback binding on a GitHub Actions test VM is not evidence about cloud firewall rules.
- No backend Floodgate, key sharing, ecommerce identity linking, PostgreSQL migration, paid entitlement or active shop is added.
- A local operator must **never** deploy the E2E Compose service or its offline-mode access as an Internet-facing server.
- The Java/Bedrock tests use offline Mineflayer players and cannot certify paid-account identity.

## Acceptance

- Exact PR head: full CI with static and live loopback guards plus Stone Warden solo/party, Stone Vault complete/abandoned/restart/preactivation/forced failure and frozen reward replay **SUCCESS**.
- Merged main: same CI **SUCCESS**.
- Only then close #49; keep #47/#42/#41 OPEN for authenticated staging, security and business approval.
