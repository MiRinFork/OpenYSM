{
  description = "OpenYSM — Minecraft 1.20.1 mod (Fabric + Forge via Architectury)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forEachSystem = fn: nixpkgs.lib.genAttrs systems (system: fn nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forEachSystem (
        pkgs:
        let
          inherit (pkgs) lib stdenv;

          # CI builds with Temurin 21 (see .github/workflows/build-release.yml).
          # The mod targets Java 17 bytecode via Gradle's source/targetCompatibility —
          # JDK 21 is the invoker, not the target.
          jdk = pkgs.temurin-bin-21;

          # Native libraries dlopen'd at runtime by LWJGL (Minecraft client) and
          # the bundled libysm-core.so. Linux only; on Darwin the equivalents come
          # from the system SDK and are not resolved via LD_LIBRARY_PATH.
          linuxRuntimeLibs = with pkgs; [
            stdenv.cc.cc.lib # libstdc++, libgcc_s — needed by libysm-core.so
            zlib
            libGL
            glfw
            openal
            libpulseaudio
            alsa-lib
            flite # Minecraft narrator (text-to-speech)
            libxkbcommon
            wayland
            libx11
            libxext
            libxcursor
            libxi
            libxrandr
            libxxf86vm
          ];
        in
        {
          default = pkgs.mkShell {
            packages = [
              jdk
              pkgs.gradle # convenience; ./gradlew is the canonical entry point
              pkgs.git
              pkgs.cmake # for the (currently gated-off) :common:compileNative task
              pkgs.gnumake
              pkgs.gcc
            ];

            shellHook =
              ''
                export JAVA_HOME=${jdk}
                export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-$HOME/.gradle}"
              ''
              + lib.optionalString stdenv.isLinux ''
                export LD_LIBRARY_PATH="${lib.makeLibraryPath linuxRuntimeLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
              ''
              + ''
                echo "OpenYSM dev shell ready — $(${jdk}/bin/java -version 2>&1 | head -n1)"
              '';
          };
        }
      );

      formatter = forEachSystem (pkgs: pkgs.nixfmt);
    };
}
