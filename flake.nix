{
  description = "Notable Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    # The sync server's own flake, for buildJobs below.
    web.url = "path:./web";
  };

  outputs = inputs:
    inputs.flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];

      # Built on every push to main by noldor's nix-builds service into the
      # R2 cache, which formenos substitutes the sync server from. The
      # service reads the root flake, so the web flake is re-exported here.
      flake.buildJobs.x86_64-linux = {
        inherit (inputs.web.packages.x86_64-linux) notable-server;
      };

      perSystem = { system, ... }:
        let
          pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          androidSdk = (pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37" ];
            buildToolsVersions = [ "36.0.0" ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
            includeCmake = false;
          }).androidsdk;
          sdkRoot = "${androidSdk}/libexec/android-sdk";
        in {
          devShells.default = pkgs.mkShell {
            # Match the JDK used by the Android CI workflows.
            packages = [ pkgs.jdk21 androidSdk ];
            JAVA_HOME = "${pkgs.jdk21}";
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            # Maven's aapt2 binary cannot run directly on NixOS.
            shellHook = ''
              export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/36.0.0/aapt2"
            '';
          };
        };
    };
}
