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
      ../schema/runtime
      ../server/package.json
      ../server/src
      ../repl/package.json
      ../repl/src
      ../app/package.json
      ../app/index.html
      ../app/vite.config.ts
      ../app/tsconfig.json
      ../app/public
      ../app/src
    ];
  };

  npmDepsHash = "sha256-aNT6Vl7+1MqZEh4QDjdQFx9GPc6iAakUMPmfzo2J/Rc=";

  nodejs = nodejs_24;
  nativeBuildInputs = [ makeWrapper ];

  # The server needs no build (Node 24 strips TypeScript types at load time); the PWA does.
  buildPhase = ''
    runHook preBuild
    npm run build --workspace @notable/app
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall

    # The PWA ships as static files; its dependencies are all devDependencies and go here.
    mkdir -p $out/share/notable
    cp -r app/dist $out/share/notable/app

    # Reinstall only the server's production dependencies. Pruning keeps the app workspace's
    # packages (Vite, Solid, MuPDF), which the server never loads.
    rm -rf node_modules
    npm ci --omit=dev --workspace @notable/server --ignore-scripts --offline --no-audit --no-fund
    # Nothing runs the .bin shims.
    find node_modules -name .bin -type d -prune -exec rm -rf {} +

    lib=$out/lib/notable
    mkdir -p $lib $out/bin
    # Keep node_modules/@notable/* as relative symlinks into schema/ and server/: Node refuses to
    # strip types from files whose real path lies under node_modules.
    cp -a package.json node_modules schema server $lib/
    # Shebangs patched by npmConfigHook point at the full nodejs; none may survive the reinstall.
    { grep -rlZ ${nodejs_24} $lib || true; } | xargs -0 -r sed -i "s#${nodejs_24}#${nodejs-slim_24}#g"

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
    description = "Notable sync server: Automerge sync, file store, accounts and the PWA";
    license = lib.licenses.gpl3Only;
    mainProgram = "notable-server";
    platforms = lib.platforms.linux;
  };
}
