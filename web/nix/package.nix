{
  lib,
  buildNpmPackage,
  nodejs_24,
  nodejs-slim_24,
  makeWrapper,
}:
let
  fs = lib.fileset;
  root = ../.;
in
buildNpmPackage {
  pname = "notable-server";
  version = "0.1.0";

  # Only what runs: no tests, fixtures or local node_modules.
  src = fs.toSource {
    inherit root;
    fileset = fs.unions [
      ../package.json
      ../package-lock.json
      ../schema/package.json
      ../schema/src
      ../server/package.json
      ../server/src
    ];
  };

  npmDepsHash = "sha256-IiMd7/JX8mtch3WQNGGXkEZ7SoUOaCpg7yHbYK6P/os=";

  nodejs = nodejs_24;
  nativeBuildInputs = [ makeWrapper ];

  # Node 24 strips TypeScript types at load time, so there is nothing to build.
  dontNpmBuild = true;

  installPhase = ''
    runHook preInstall

    npm prune --omit=dev --no-audit --no-fund
    # Nothing runs the .bin shims.
    find node_modules -name .bin -type d -prune -exec rm -rf {} +

    lib=$out/lib/notable
    mkdir -p $lib $out/bin
    # Keep node_modules/@notable/* as relative symlinks into schema/ and server/: Node refuses to
    # strip types from files whose real path lies under node_modules.
    cp -a package.json node_modules schema server $lib/
    grep -rlZ ${nodejs_24} $lib | xargs -0 -r sed -i "s#${nodejs_24}#${nodejs-slim_24}#g"

    makeWrapper ${nodejs-slim_24}/bin/node $out/bin/notable-server \
      --add-flags "--disable-warning=ExperimentalWarning $lib/server/src/main.ts"
    makeWrapper ${nodejs-slim_24}/bin/node $out/bin/notable-admin \
      --add-flags "--disable-warning=ExperimentalWarning $lib/server/src/cli.ts"

    runHook postInstall
  '';

  # npmConfigHook already patched shebangs to the full nodejs; installPhase points them at the slim
  # one, so npm stays out of the runtime closure.
  dontPatchShebangs = true;
  disallowedReferences = [ nodejs_24 ];

  meta = {
    description = "Notable sync server: Automerge sync, file store and accounts";
    license = lib.licenses.gpl3Only;
    mainProgram = "notable-server";
    platforms = lib.platforms.linux;
  };
}
