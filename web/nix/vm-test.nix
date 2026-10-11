self:
{
  name = "notable-server";

  nodes.machine =
    { pkgs, ... }:
    {
      imports = [ self.nixosModules.default ];
      services.notable-server.enable = true;
      environment.systemPackages = [ pkgs.curl pkgs.jq ];
    };

  testScript = ''
    import json

    api = "http://127.0.0.1:3030"
    password = "correct horse battery staple"

    def login():
        out = machine.succeed(
            f"curl -sf -X POST -H 'content-type: application/json' "
            f"-d '{{\"username\":\"alice\",\"password\":\"{password}\"}}' {api}/api/login"
        )
        return json.loads(out)["token"]

    def me(token):
        return json.loads(machine.succeed(f"curl -sf -H 'Authorization: Bearer {token}' {api}/api/me"))

    def upgrade_status(extra = ""):
        # curl keeps a successful upgrade open until --max-time, so ignore its exit code.
        return machine.succeed(
            "curl -s -o /dev/null -w '%{http_code}' --max-time 3 "
            "-H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' "
            f"-H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' {extra} {api}/sync || true"
        ).strip()

    machine.wait_for_unit("notable-server.service")
    machine.wait_for_open_port(3030)
    machine.succeed(f"curl -sf {api}/api/health")

    with subtest("service runs unprivileged"):
        machine.succeed("test \"$(systemctl show -P User notable-server)\" = notable")
        machine.succeed("test \"$(stat -c %U /var/lib/notable)\" = notable")

    with subtest("admin CLI creates an account as the service user"):
        machine.succeed(f"printf '%s\\n' '{password}' | notable-admin user add alice")
        machine.succeed("notable-admin user list | grep -q alice")
        machine.succeed("test \"$(stat -c %U /var/lib/notable/notable.db)\" = notable")

    with subtest("login and library doc"):
        token = login()
        library = me(token)["libraryDocUrl"]
        assert library.startswith("automerge:"), library

    with subtest("wrong password is rejected"):
        machine.succeed(
            "test \"$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' "
            f"-d '{{\"username\":\"alice\",\"password\":\"nope\"}}' {api}/api/login)\" = 401"
        )

    with subtest("blob round trip"):
        machine.succeed("head -c 100000 /dev/urandom > /tmp/blob")
        sha = machine.succeed("sha256sum /tmp/blob | cut -d' ' -f1").strip()
        machine.succeed(
            f"test \"$(curl -s -o /dev/null -w '%{{http_code}}' -X PUT --data-binary @/tmp/blob "
            f"-H 'Authorization: Bearer {token}' {api}/api/blobs/{sha})\" = 201"
        )
        machine.succeed(f"curl -sf -H 'Authorization: Bearer {token}' {api}/api/blobs/{sha} | cmp - /tmp/blob")
        machine.succeed(f"test \"$(curl -s -o /dev/null -w '%{{http_code}}' {api}/api/blobs/{sha})\" = 401")

    with subtest("sync upgrade needs a session"):
        status = upgrade_status()
        assert status == "401", f"unauthenticated upgrade got {status}"
        status = upgrade_status(f"-H 'Authorization: Bearer {token}'")
        assert status == "101", f"authenticated upgrade got {status}"

    with subtest("the PWA is served from the same origin"):
        page = machine.succeed(f"curl -sf {api}/")
        assert "<title>Notable</title>" in page, "index.html not served"
        headers = machine.succeed(f"curl -sfI {api}/n/some-notebook")
        assert "content-security-policy:" in headers.lower(), "client route lacks the app CSP"
        assert "cache-control: no-cache" in headers.lower(), "app shell must be revalidated"
        machine.succeed(f"curl -sf {api}/sw.js | grep -q 'const PRECACHE = \\[\"/\"'")
        machine.succeed(f"curl -sf {api}/manifest.webmanifest | grep -q share_target")
        asset = machine.succeed(f"curl -sf {api}/ | grep -o '/assets/index-[^\"]*\\.js' | head -1").strip()
        assert asset, "no hashed entry script in index.html"
        headers = machine.succeed(f"curl -sfI {api}{asset}").lower()
        assert "immutable" in headers, f"{asset} should be immutable"

    with subtest("state survives a restart"):
        machine.succeed("systemctl restart notable-server.service")
        machine.wait_for_unit("notable-server.service")
        machine.wait_for_open_port(3030)
        assert me(token)["libraryDocUrl"] == library, "session or library lost"
        token2 = login()
        assert me(token2)["libraryDocUrl"] == library
        machine.succeed(f"curl -sf -H 'Authorization: Bearer {token2}' {api}/api/blobs/{sha} | cmp - /tmp/blob")
        machine.succeed("test -n \"$(ls -A /var/lib/notable/docs)\"")

    print(machine.succeed("systemd-analyze security notable-server.service | tail -1"))
  '';
}
