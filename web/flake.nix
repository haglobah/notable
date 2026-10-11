{
  description = "Notable sync server: Automerge sync, file store and accounts";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAll (pkgs: rec {
        notable-server = pkgs.callPackage ./nix/package.nix { };
        notable-server-image = pkgs.callPackage ./nix/image.nix { inherit notable-server; };
        default = notable-server;
      });

      nixosModules.default = import ./nix/module.nix self;

      checks = forAll (pkgs: {
        vm = pkgs.testers.runNixOSTest (import ./nix/vm-test.nix self);
      });
    };
}
