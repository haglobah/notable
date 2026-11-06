self:
{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.notable-server;
  inherit (lib) mkOption types;

  stateUnderVarLib = lib.hasPrefix "/var/lib/" cfg.stateDir;

  environment = {
    NOTABLE_STATE_DIR = cfg.stateDir;
    NOTABLE_LISTEN = "${cfg.listenAddress}:${toString cfg.port}";
    NOTABLE_OPEN_SIGNUP = if cfg.openSignup then "1" else "0";
    NOTABLE_TRUST_PROXY = if cfg.trustProxy then "1" else "0";
    NOTABLE_MAX_BLOB_MIB = toString cfg.maxBlobSizeMiB;
    NOTABLE_STATIC_DIR = "${cfg.package}/share/notable/app";
  }
  // lib.optionalAttrs (cfg.publicOrigin != null) { NOTABLE_PUBLIC_ORIGIN = cfg.publicOrigin; }
  // lib.optionalAttrs (cfg.humaneToolsUrl != null) {
    NOTABLE_HT_URL = cfg.humaneToolsUrl;
    NOTABLE_HT_LINKS = lib.concatStringsSep "," (lib.mapAttrsToList (name: email: "${name}=${email}") cfg.humaneToolsLinks);
  }
  // lib.optionalAttrs (cfg.accountsUrl != null) { NOTABLE_ACCOUNTS_URL = cfg.accountsUrl; }
  // lib.optionalAttrs (cfg.connectClients != { }) {
    NOTABLE_CONNECT_CLIENTS = lib.concatStringsSep "," (
      lib.concatLists (lib.mapAttrsToList (name: urls: map (url: "${name}=${url}") urls) cfg.connectClients)
    );
  }
  // cfg.environment;

  # Runs the admin CLI as the service user against the service's state, so files it creates
  # keep the right owner.
  notable-admin = pkgs.writeShellScriptBin "notable-admin" ''
    ${lib.concatStringsSep "\n" (lib.mapAttrsToList (k: v: "export ${k}=${lib.escapeShellArg v}") environment)}
    if [ "$(id -u)" = 0 ]; then
      exec ${pkgs.util-linux}/bin/runuser -u ${cfg.user} -- ${cfg.package}/bin/notable-admin "$@"
    fi
    if [ "$(id -un)" != ${lib.escapeShellArg cfg.user} ]; then
      echo "notable-admin: run as root or as ${cfg.user}" >&2
      exit 1
    fi
    exec ${cfg.package}/bin/notable-admin "$@"
  '';
in
{
  options.services.notable-server = {
    enable = lib.mkEnableOption "the Notable sync server";

    package = mkOption {
      type = types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.notable-server;
      defaultText = lib.literalExpression "notable.packages.\${system}.notable-server";
    };

    listenAddress = mkOption {
      type = types.str;
      default = "127.0.0.1";
      description = "Address to listen on. Put a TLS reverse proxy in front.";
    };

    port = mkOption {
      type = types.port;
      default = 3030;
    };

    stateDir = mkOption {
      type = types.path;
      default = "/var/lib/notable";
      description = "Database, Automerge docs and uploaded files. Back up the whole directory.";
    };

    publicOrigin = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "https://notable.example.org";
      description = ''
        Origin the PWA is served from. Cookie-authenticated requests must come from it, and cookies
        get `Secure` when it is https. Leave null only for local development.
      '';
    };

    openSignup = mkOption {
      type = types.bool;
      default = false;
      description = "Allow anyone to create an account. Otherwise accounts come from `notable-admin`.";
    };

    humaneToolsUrl = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "https://todos.humane.tools";
      description = ''
        humane tools' auth server. A login with an email instead of a username is checked there,
        and signs in as the account linked in `humaneToolsLinks`.
      '';
    };

    humaneToolsLinks = mkOption {
      type = types.attrsOf types.str;
      default = { };
      example = {
        alice = "alice@example.org";
      };
      description = ''
        Notable account name to the humane tools email that may sign in as it. The whole set,
        applied at every start: accounts left out lose their link. The accounts must exist.
      '';
    };

    accountsUrl = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "https://accounts.humane.tools";
      description = ''
        The humane.tools accounts provider (OIDC, ht's ADR-005). Set, the login page offers "Sign
        in with humane tools", for the accounts humaneToolsLinks links (by the provider's user
        id, the same as humaneToolsUrl's). Needs publicOrigin, the `notable` client registered at
        the provider with publicOrigin + "/api/oidc/callback", and its secret as
        NOTABLE_OIDC_CLIENT_SECRET in environmentFile.
      '';
    };

    connectClients = mkOption {
      type = types.attrsOf (types.listOf types.str);
      default = { };
      example = {
        flow = [ "https://flow.humane.tools/api/notable/callback" ];
      };
      description = ''
        Apps that may connect to an account through the provider (GET /api/connect, code flow
        with PKCE), by name, with the exact redirect URLs each may receive a code at. Needs
        accountsUrl.
      '';
    };

    environmentFile = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "/run/secrets/notable.env";
      description = "A file of secret environment variables (NOTABLE_OIDC_CLIENT_SECRET=…), outside the Nix store.";
    };

    trustProxy = mkOption {
      type = types.bool;
      default = false;
      description = ''
        Take the client IP from X-Forwarded-For. Enable behind a reverse proxy, or every login
        shares the proxy's IP for rate limiting.
      '';
    };

    maxBlobSizeMiB = mkOption {
      type = types.ints.positive;
      default = 512;
      description = "Largest file upload, in MiB.";
    };

    environment = mkOption {
      type = types.attrsOf types.str;
      default = { };
      example = {
        NOTABLE_SESSION_IDLE_DAYS = "90";
      };
      description = "Extra environment variables for the server and `notable-admin`.";
    };

    user = mkOption {
      type = types.str;
      default = "notable";
    };

    group = mkOption {
      type = types.str;
      default = "notable";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.accountsUrl == null || (cfg.publicOrigin != null && cfg.environmentFile != null);
        message = "services.notable-server.accountsUrl needs publicOrigin and an environmentFile holding NOTABLE_OIDC_CLIENT_SECRET";
      }
      {
        assertion = cfg.connectClients == { } || cfg.accountsUrl != null;
        message = "services.notable-server.connectClients needs accountsUrl";
      }
    ];

    users.users.${cfg.user} = {
      isSystemUser = true;
      group = cfg.group;
      home = cfg.stateDir;
    };
    users.groups.${cfg.group} = { };

    environment.systemPackages = [ notable-admin ];

    systemd.tmpfiles.settings.notable-server = lib.mkIf (!stateUnderVarLib) {
      ${cfg.stateDir}.d = {
        user = cfg.user;
        group = cfg.group;
        mode = "0750";
      };
    };

    systemd.services.notable-server = {
      description = "Notable sync server";
      wantedBy = [ "multi-user.target" ];
      after = [ "network.target" ];
      inherit environment;

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        EnvironmentFile = lib.mkIf (cfg.environmentFile != null) cfg.environmentFile;
        User = cfg.user;
        Group = cfg.group;
        Restart = "on-failure";
        RestartSec = 5;

        StateDirectory = lib.mkIf stateUnderVarLib (lib.removePrefix "/var/lib/" cfg.stateDir);
        StateDirectoryMode = "0750";
        ReadWritePaths = lib.mkIf (!stateUnderVarLib) [ cfg.stateDir ];
        UMask = "0027";

        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectKernelLogs = true;
        ProtectControlGroups = true;
        ProtectClock = true;
        ProtectHostname = true;
        ProtectProc = "invisible";
        ProcSubset = "pid";
        RestrictAddressFamilies = [
          "AF_INET"
          "AF_INET6"
          "AF_UNIX"
        ];
        RestrictNamespaces = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
        LockPersonality = true;
        # V8 needs writable+executable memory for its JIT, so no MemoryDenyWriteExecute.
        SystemCallArchitectures = "native";
        SystemCallFilter = [
          "@system-service"
          "~@privileged"
        ];
        CapabilityBoundingSet = if cfg.port < 1024 then [ "CAP_NET_BIND_SERVICE" ] else "";
        AmbientCapabilities = lib.mkIf (cfg.port < 1024) [ "CAP_NET_BIND_SERVICE" ];
      };
    };
  };
}
