{ mkShell
, callPackage
, writeScript
, writeText
, xcodeWrapper
, pkgs
, stdenv}:
let
  buildNimStatus = platform: arch: callPackage ./nim-status.nix {
    inherit platform arch;
  };

  buildStatusGo = platform: arch: callPackage ./status-go.nix {
    inherit platform arch;
  };

  buildAndroid = buildMap: name: stdenv.mkDerivation {
    name = "${name}-android";
    buildInputs = [ pkgs.coreutils ];
    builder = writeScript "${name}-android-builder.sh"
    ''
      source $stdenv/setup
      mkdir $out

      ln -s ${buildMap.x86} $out/x86
      ln -s ${buildMap.arm} $out/armeabi-v7a
      ln -s ${buildMap.arm64} $out/arm64-v8a
    '';
  };

  # Create a single multi-arch fat binary using lipo
  # Create a single header for multiple target archs
  # by utilizing C preprocessor conditionals
  buildIos = buildMap: name:
    let
      headerIos = writeText "${name}.h" ''
        #if TARGET_CPU_X86_64
        ${builtins.readFile "${buildMap.x86}/${name}.h"}
        #elif TARGET_CPU_ARM
        ${builtins.readFile "${buildMap.arm}/${name}.h"}
        #else
        ${builtins.readFile "${buildMap.arm64}/${name}.h"}
        #endif
      '';
    in stdenv.mkDerivation {
      name = "${name}-ios";
      buildInputs = [ pkgs.coreutils xcodeWrapper ];
      builder = writeScript "${name}-ios-builder.sh"
      ''
        source $stdenv/setup
        mkdir $out

        # lipo merges arch-specific binaries into one fat iOS binary
        lipo -create ${buildMap.x86}/lib${name}.a \
             ${buildMap.arm}/lib${name}.a \
             ${buildMap.arm64}/lib${name}.a \
             -output $out/lib${name}.a

        cp ${headerIos} $out/${name}.h
        ${if name=="nim_status" then "cp ${buildMap.arm64}/nimbase.h $out" else ""}
      '';
  };
in rec {
  nim-status = {
    android = {
      x86 = buildNimStatus "android" "386";
      arm = buildNimStatus "androideabi" "arm";
      arm64 = buildNimStatus "android" "arm64";
    };
    ios = {
      x86 = buildNimStatus "ios" "386";
      arm = buildNimStatus "ios" "arm";
      arm64 = buildNimStatus "ios" "arm64";
    };
  };

  status-go = {
    android = {
      x86 = buildStatusGo "android" "386";
      arm = buildStatusGo "androideabi" "arm";
      arm64 = buildStatusGo "android" "arm64";
    };
    ios = {
      x86 = buildStatusGo "ios" "386";
      arm = buildStatusGo "ios" "arm";
      arm64 = buildStatusGo "ios" "arm64";
    };
  };


  nim-status-android = buildAndroid nim-status.android "nim_status";
  nim-status-ios = buildIos nim-status.ios "nim_status";

  status-go-android = buildAndroid status-go.android "status";
  status-go-ios = buildIos status-go.ios "status";

  android = stdenv.mkDerivation {
      buildInputs = [ pkgs.coreutils ];
      name = "nim-status-go-android";
      builder = writeScript "nim-status-go-android-builder.sh"
      ''
        source $stdenv/setup

        mkdir $out
        for arch in "x86" "armeabi-v7a" "arm64-v8a"; do
          mkdir $out/$arch

          for filename in ${nim-status-android}/$arch/*; do
            ln -sf "$filename" $out/$arch/$(basename $filename)
          done

          for filename in ${status-go-android}/$arch/*; do
            ln -sf "$filename" $out/$arch/$(basename $filename)
          done
        done
      '';
  };

  ios = stdenv.mkDerivation {
      buildInputs = [ pkgs.coreutils ];
      name = "nim-status-go-ios";
      builder = writeScript "nim-status-go-ios-builder.sh"
      ''
        source $stdenv/setup
        mkdir $out
        for filename in ${nim-status-ios}/*; do
          ln -sf "$filename" $out/$(basename $filename)
        done

        for filename in ${status-go-ios}/*; do
          ln -sf "$filename" $out/$(basename $filename)
        done
      '';
  };
  shell = mkShell {
    inputsFrom = [ status-go-android status-go-ios ];
  };
}
