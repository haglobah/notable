{
  dockerTools,
  notable-server,
}:
# OCI image for self-hosting without NixOS. State lives in the /var/lib/notable volume.
#   docker run -v notable:/var/lib/notable -p 3030:3030 \
#     -e NOTABLE_PUBLIC_ORIGIN=https://notable.example.org -e NOTABLE_TRUST_PROXY=1 notable-server
#   docker exec -i <container> notable-admin user add alice
dockerTools.buildLayeredImage {
  name = "notable-server";
  tag = notable-server.version;
  contents = [ notable-server ];
  fakeRootCommands = ''
    mkdir -p ./var/lib/notable ./tmp
    chown 1000:1000 ./var/lib/notable
    chmod 1777 ./tmp
  '';
  enableFakechroot = false;
  config = {
    Cmd = [ "/bin/notable-server" ];
    User = "1000:1000";
    Env = [
      "NOTABLE_STATE_DIR=/var/lib/notable"
      "NOTABLE_LISTEN=0.0.0.0:3030"
    ];
    ExposedPorts."3030/tcp" = { };
    Volumes."/var/lib/notable" = { };
  };
}
