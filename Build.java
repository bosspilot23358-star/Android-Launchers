// PlayHack5 - build the APK WITHOUT Android Studio, Gradle or GitHub Actions.
//
// The only thing you need is a JDK 17 (11+ works). Run from the project folder:
//
//     java Build.java            build the APK (downloads SDK + libVLC on the first run)
//     java Build.java setup      only download/prepare the Android SDK pieces
//     java Build.java install    install PlayHack5.apk through adb (USB or "install 192.168.1.50")
//     java Build.java clean      delete the build/ folder
//
// Options:  --sdk=DIR  (use/create the SDK here)   --abi=arm64-v8a,armeabi-v7a  (or --abi=all)
// Env:      PH_MAVEN (comma separated Maven mirrors), PH_CMDLINE_TOOLS_URL, PH_SDK_PROXY=host:port
//
// Everything is written in plain Java 11 so it runs with "java Build.java" and needs no libraries.

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Build {

    // ------------------------------------------------------------------ settings

    static final String APP_ID = "com.playhack5";
    static final String APK_NAME = "PlayHack5.apk";
    static final String VERSION_NAME = "1.1";
    static final int VERSION_CODE = 1;
    static final int MIN_SDK = 21;
    static final int TARGET_SDK = 34;
    static final String SDK_PLATFORM = "34";        // what "setup" installs
    static final String SDK_BUILD_TOOLS = "34.0.0"; // what "setup" installs
    static final String VLC_VERSION = "3.6.2";
    static final String CMDLINE_REV = "11076708";   // commandlinetools-<os>-REV_latest.zip
    static final String[] DEFAULT_ABIS = {"arm64-v8a", "armeabi-v7a"};
    static final String[] MAVEN_MIRRORS = {
            "https://repo1.maven.org/maven2",
            "https://maven.aliyun.com/repository/central",
            "https://repo.huaweicloud.com/repository/maven"
    };

    static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    static Path root;
    static String optSdk;
    static String[] abis = DEFAULT_ABIS;

    static class Fail extends RuntimeException {
        Fail(String m) {
            super(m);
        }
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Fail f) {
            System.err.println();
            System.err.println("ERROR: " + f.getMessage());
            code = 1;
        } catch (Throwable t) {
            t.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--sdk=")) {
                optSdk = a.substring(6);
            } else if (a.startsWith("--abi=")) {
                abis = a.substring(6).split(",");
            } else {
                pos.add(a);
            }
        }
        String cmd = pos.isEmpty() ? "build" : pos.get(0);

        root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("app/src/main/AndroidManifest.xml"))) {
            throw new Fail("Run this from the PlayHack5 project folder: app/src/main/AndroidManifest.xml "
                    + "was not found in " + root);
        }
        if (Runtime.version().feature() < 11) {
            throw new Fail("Java " + Runtime.version().feature() + " is too old. Install JDK 17 (for example Temurin from adoptium.net).");
        }
        if (ToolProvider.getSystemJavaCompiler() == null) {
            throw new Fail("This is a JRE, not a JDK (no compiler). Install a full JDK 17.");
        }
        for (char ch : root.toString().toCharArray()) {
            if (ch > 127) {
                System.out.println("WARNING: the project path contains non-ASCII characters. If aapt2 fails, move the folder to a plain path like C:\\PlayHack5");
                break;
            }
        }

        switch (cmd) {
            case "build":
                build();
                return 0;
            case "setup":
                setup();
                return 0;
            case "install":
                install(pos.size() > 1 ? pos.get(1) : null);
                return 0;
            case "clean":
                deleteTree(root.resolve("build"));
                System.out.println("build/ deleted");
                return 0;
            case "help":
            case "--help":
            case "-h":
                usage();
                return 0;
            default:
                usage();
                return 2;
        }
    }

    static void usage() {
        System.out.println("java Build.java [build|setup|install [host[:port]]|clean|help] [--sdk=DIR] [--abi=arm64-v8a,armeabi-v7a|all]");
    }

    // ------------------------------------------------------------------ build

    static void build() throws Exception {
        long t0 = System.currentTimeMillis();
        step(1, "Android SDK");
        Sdk sdk = ensureSdk();
        System.out.println("  SDK:         " + sdk.home);
        System.out.println("  platform:    android-" + sdk.api);
        System.out.println("  build-tools: " + sdk.buildToolsName);
        if (sdk.api < 34) {
            System.out.println("  note: compileSdk 34 is recommended, building against android-" + sdk.api);
        }
        fixExecutable(sdk.aapt2);
        fixExecutable(sdk.zipalign);

        step(2, "libVLC " + VLC_VERSION);
        Path aar = ensureVlc();
        Path b = root.resolve("build");
        Files.createDirectories(b);
        Path aarDir = b.resolve("aar");
        extractAar(aar, aarDir);
        Path vlcClasses = aarDir.resolve("classes.jar");
        if (!Files.isRegularFile(vlcClasses)) {
            throw new Fail(aar + " does not contain classes.jar - is it really a libvlc AAR?");
        }
        List<Path> vlcJars = new ArrayList<>();
        vlcJars.add(vlcClasses);
        Path aarLibs = aarDir.resolve("libs");
        if (Files.isDirectory(aarLibs)) {
            try (Stream<Path> s = Files.list(aarLibs)) {
                vlcJars.addAll(s.filter(p -> p.toString().endsWith(".jar")).sorted().collect(Collectors.toList()));
            }
        }

        Path resOut = b.resolve("res");
        Path gen = b.resolve("gen");
        Path classes = b.resolve("classes");
        Path dex = b.resolve("dex");
        for (Path p : new Path[]{resOut, gen, classes, dex}) {
            deleteTree(p);
            Files.createDirectories(p);
        }

        step(3, "Resources and manifest (aapt2)");
        String aarManifest = "";
        Path aarManifestFile = aarDir.resolve("AndroidManifest.xml");
        if (Files.isRegularFile(aarManifestFile)) {
            aarManifest = new String(Files.readAllBytes(aarManifestFile), StandardCharsets.UTF_8);
        }
        Path manifest = writeManifest(b, aarManifest);

        Path appRes = root.resolve("app/src/main/res");
        Path appResZip = resOut.resolve("app.zip");
        exec(Arrays.asList(sdk.aapt2.toString(), "compile", "--dir", appRes.toString(), "-o", appResZip.toString()), "aapt2 compile");

        Path aarResDir = aarDir.resolve("res");
        Path aarResZip = null;
        if (hasFiles(aarResDir)) {
            aarResZip = resOut.resolve("vlc.zip");
            exec(Arrays.asList(sdk.aapt2.toString(), "compile", "--dir", aarResDir.toString(), "-o", aarResZip.toString()), "aapt2 compile (libvlc)");
        }

        Path baseApk = b.resolve("base.apk");
        List<String> link = new ArrayList<>(Arrays.asList(
                sdk.aapt2.toString(), "link",
                "-o", baseApk.toString(),
                "-I", sdk.androidJar.toString(),
                "--manifest", manifest.toString(),
                "--min-sdk-version", String.valueOf(MIN_SDK),
                "--target-sdk-version", String.valueOf(TARGET_SDK),
                "--version-code", String.valueOf(VERSION_CODE),
                "--version-name", VERSION_NAME,
                "--java", gen.toString(),
                "--auto-add-overlay"));
        if (aarResZip != null) {
            Matcher pm = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(aarManifest);
            if (pm.find()) {
                link.add("--extra-packages");
                link.add(pm.group(1));
            }
            link.add(aarResZip.toString());
        }
        link.add(appResZip.toString());
        exec(link, "aapt2 link");

        step(4, "Java (javac)");
        List<String> javac = new ArrayList<>();
        javac.add("-encoding");
        javac.add("UTF-8");
        javac.add("--release");
        javac.add(String.valueOf(javaRelease(sdk.androidJar)));
        javac.add("-Xlint:-options");
        javac.add("-proc:none");
        javac.add("-classpath");
        List<String> cp = new ArrayList<>();
        cp.add(sdk.androidJar.toString());
        for (Path j : vlcJars) {
            cp.add(j.toString());
        }
        javac.add(String.join(File.pathSeparator, cp));
        javac.add("-d");
        javac.add(classes.toString());
        int n = 0;
        for (Path src : new Path[]{root.resolve("app/src/main/java"), gen}) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
                    javac.add(f.toString());
                    n++;
                }
            }
        }
        if (n == 0) {
            throw new Fail("No .java files found in app/src/main/java");
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        int rc = jc.run(null, null, null, javac.toArray(new String[0]));
        if (rc != 0) {
            throw new Fail("javac found errors in the source code (see above)");
        }
        System.out.println("  compiled " + n + " files");

        step(5, "DEX (d8)");
        Path appJar = b.resolve("app-classes.jar");
        zipDir(classes, appJar);
        List<String> d8 = new ArrayList<>(Arrays.asList(
                javaExe(), "-cp", sdk.d8Jar.toString(), "com.android.tools.r8.D8",
                "--release", "--min-api", String.valueOf(MIN_SDK),
                "--lib", sdk.androidJar.toString(),
                "--output", dex.toString(), appJar.toString()));
        for (Path j : vlcJars) {
            d8.add(j.toString());
        }
        exec(d8, "d8");

        step(6, "Packing the APK");
        Path unaligned = b.resolve("unaligned.apk");
        int libs = assembleApk(baseApk, dex, aarDir.resolve("jni"), unaligned);
        if (libs == 0) {
            System.out.println("  WARNING: no native libraries (.so) for " + String.join(",", abis)
                    + " were found in the libVLC AAR - video will not play.");
        } else {
            System.out.println("  native libraries: " + libs);
        }

        step(7, "zipalign + signature");
        Path aligned = b.resolve("aligned.apk");
        Files.deleteIfExists(aligned);
        exec(Arrays.asList(sdk.zipalign.toString(), "-f", "-p", "4", unaligned.toString(), aligned.toString()), "zipalign");
        Path ks = ensureKeystore();
        Path out = root.resolve(APK_NAME);
        Files.deleteIfExists(out);
        exec(Arrays.asList(javaExe(), "-jar", sdk.apksignerJar.toString(), "sign",
                "--ks", ks.toString(), "--ks-pass", "pass:android", "--key-pass", "pass:android",
                "--ks-key-alias", "androiddebugkey", "--min-sdk-version", String.valueOf(MIN_SDK),
                "--out", out.toString(), aligned.toString()), "apksigner");
        exec(Arrays.asList(javaExe(), "-jar", sdk.apksignerJar.toString(), "verify", out.toString()), "apksigner verify");

        long secs = (System.currentTimeMillis() - t0) / 1000;
        System.out.println();
        System.out.println("DONE in " + secs + " s:  " + out + "  (" + (Files.size(out) / 1024 / 1024) + " MB)");
        System.out.println("Install:  java Build.java install        (USB)");
        System.out.println("          java Build.java install 192.168.1.50   (over the network)");
    }

    static void step(int n, String what) {
        System.out.println();
        System.out.println("[" + n + "/7] " + what);
    }

    /** Copy of the manifest with the package attribute and the permissions requested by libVLC. */
    static Path writeManifest(Path b, String aarManifest) throws IOException {
        String m = new String(Files.readAllBytes(root.resolve("app/src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        if (!Pattern.compile("<manifest[^>]*\\spackage\\s*=").matcher(m).find()) {
            m = m.replaceFirst("<manifest\\b", Matcher.quoteReplacement("<manifest package=\"" + APP_ID + "\""));
        }
        Matcher pm = Pattern.compile("<uses-permission[^>]*android:name=\"([^\"]+)\"").matcher(aarManifest);
        StringBuilder extra = new StringBuilder();
        while (pm.find()) {
            String perm = pm.group(1);
            if (!m.contains("\"" + perm + "\"")) {
                extra.append("    <uses-permission android:name=\"").append(perm).append("\" />\n");
                System.out.println("  + permission from libVLC: " + perm);
            }
        }
        if (extra.length() > 0) {
            int i = m.indexOf("<application");
            if (i < 0) {
                throw new Fail("<application> not found in AndroidManifest.xml");
            }
            m = m.substring(0, i) + extra + "    " + m.substring(i);
        }
        Path out = b.resolve("AndroidManifest.xml");
        Files.write(out, m.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** Java language level for javac: 8, unless android.jar itself was compiled for something newer. */
    static int javaRelease(Path androidJar) {
        try (ZipFile z = new ZipFile(androidJar.toFile())) {
            ZipEntry e = z.getEntry("android/app/Activity.class");
            if (e != null) {
                try (InputStream in = z.getInputStream(e)) {
                    byte[] h = new byte[8];
                    int got = 0;
                    while (got < 8) {
                        int r = in.read(h, got, 8 - got);
                        if (r < 0) {
                            break;
                        }
                        got += r;
                    }
                    if (got == 8) {
                        int major = ((h[6] & 0xff) << 8) | (h[7] & 0xff);
                        int rel = major - 44;
                        if (rel > 8 && rel <= 17) {
                            return rel;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through to the default
        }
        return 8;
    }

    /** Merges the aapt2 output with classes.dex and the native libraries. Returns the number of .so files. */
    static int assembleApk(Path base, Path dexDir, Path jni, Path out) throws IOException {
        int libs = 0;
        try (ZipFile in = new ZipFile(base.toFile());
             ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            Enumeration<? extends ZipEntry> en = in.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                byte[] data = readAll(in.getInputStream(e));
                boolean stored = e.getMethod() == ZipEntry.STORED || e.getName().equals("resources.arsc");
                putEntry(z, e.getName(), data, stored);
            }
            List<Path> dexes;
            try (Stream<Path> s = Files.list(dexDir)) {
                dexes = s.filter(p -> p.getFileName().toString().matches("classes\\d*\\.dex")).sorted().collect(Collectors.toList());
            }
            if (dexes.isEmpty()) {
                throw new Fail("d8 produced no classes.dex");
            }
            for (Path d : dexes) {
                putEntry(z, d.getFileName().toString(), Files.readAllBytes(d), false);
            }
            if (Files.isDirectory(jni)) {
                List<Path> abiDirs;
                try (Stream<Path> s = Files.list(jni)) {
                    abiDirs = s.filter(Files::isDirectory).sorted().collect(Collectors.toList());
                }
                boolean all = Arrays.asList(abis).contains("all");
                for (Path abiDir : abiDirs) {
                    String abi = abiDir.getFileName().toString();
                    if (!all && !Arrays.asList(abis).contains(abi)) {
                        continue;
                    }
                    try (Stream<Path> s = Files.list(abiDir)) {
                        for (Path so : s.filter(p -> p.toString().endsWith(".so")).sorted().collect(Collectors.toList())) {
                            putEntry(z, "lib/" + abi + "/" + so.getFileName(), Files.readAllBytes(so), false);
                            libs++;
                        }
                    }
                }
            }
        }
        return libs;
    }

    static void putEntry(ZipOutputStream z, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            ne.setCrc(crc.getValue());
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
        }
        z.putNextEntry(ne);
        z.write(data);
        z.closeEntry();
    }

    static Path ensureKeystore() throws Exception {
        // The same file Android Studio / Gradle use for "debug" builds, so APKs from both builds are interchangeable.
        Path ks = Paths.get(System.getProperty("user.home"), ".android", "debug.keystore");
        if (!Files.isRegularFile(ks)) {
            Files.createDirectories(ks.getParent());
            System.out.println("  creating debug keystore " + ks);
            Path keytool = Paths.get(System.getProperty("java.home"), "bin", exe("keytool"));
            exec(Arrays.asList(keytool.toString(), "-genkeypair", "-keystore", ks.toString(),
                    "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-dname", "CN=Android Debug,O=Android,C=US"), "keytool");
        }
        return ks;
    }

    // ------------------------------------------------------------------ libVLC

    static Path ensureVlc() throws Exception {
        Path local = findLocal("libvlc*.aar");
        if (local != null) {
            System.out.println("  using " + local);
            return local;
        }
        String name = "libvlc-all-" + VLC_VERSION + ".aar";
        List<String> bases = new ArrayList<>();
        String env = System.getenv("PH_MAVEN");
        if (env != null && !env.trim().isEmpty()) {
            for (String s : env.split(",")) {
                if (!s.trim().isEmpty()) {
                    bases.add(s.trim());
                }
            }
        }
        bases.addAll(Arrays.asList(MAVEN_MIRRORS));
        List<String> urls = new ArrayList<>();
        for (String base : bases) {
            urls.add(base.replaceAll("/+$", "") + "/org/videolan/android/libvlc-all/" + VLC_VERSION + "/" + name);
        }
        Path target = root.resolve("libs").resolve(name);
        System.out.println("  downloading " + name);
        fetchAny(urls, target,
                "Could not download libVLC. Turn on your VPN, or download the file in a browser:\n  "
                        + urls.get(0) + "\nand put it into the libs/ folder, then run the build again.");
        try (ZipFile z = new ZipFile(target.toFile())) {
            if (z.getEntry("classes.jar") == null) {
                throw new IOException("no classes.jar");
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw new Fail("The downloaded libVLC file is damaged (" + e.getMessage() + "). Run the build again.");
        }
        return target;
    }

    static void extractAar(Path aar, Path dir) throws IOException {
        Path marker = dir.resolve(".ok");
        String stamp = Files.size(aar) + "/" + Files.getLastModifiedTime(aar).toMillis();
        if (Files.isRegularFile(marker) && stamp.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8))) {
            return;
        }
        System.out.println("  unpacking " + aar.getFileName());
        deleteTree(dir);
        unzip(aar, dir, 0);
        Files.write(marker, stamp.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ Android SDK

    static class Sdk {
        Path home;
        Path androidJar;
        int api;
        String buildToolsName;
        Path aapt2;
        Path zipalign;
        Path d8Jar;
        Path apksignerJar;
    }

    static Path sdkTarget() {
        if (optSdk != null) {
            return Paths.get(optSdk).toAbsolutePath().normalize();
        }
        String env = System.getenv("PH_SDK");
        if (env != null && !env.isEmpty()) {
            return Paths.get(env).toAbsolutePath().normalize();
        }
        return root.resolve("sdk");
    }

    static Sdk findSdk() {
        List<Path> cands = new ArrayList<>();
        cands.add(sdkTarget());
        for (String v : new String[]{"ANDROID_SDK_ROOT", "ANDROID_HOME"}) {
            String e = System.getenv(v);
            if (e != null && !e.isEmpty()) {
                cands.add(Paths.get(e));
            }
        }
        String home = System.getProperty("user.home");
        String lad = System.getenv("LOCALAPPDATA");
        if (lad != null) {
            cands.add(Paths.get(lad, "Android", "Sdk"));
        }
        cands.add(Paths.get(home, "Android", "Sdk"));
        cands.add(Paths.get(home, "Library", "Android", "sdk"));
        for (Path c : cands) {
            Sdk s = probe(c);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    static Sdk probe(Path home) {
        if (home == null || !Files.isDirectory(home)) {
            return null;
        }
        Sdk s = new Sdk();
        s.home = home;
        Path btRoot = home.resolve("build-tools");
        if (Files.isDirectory(btRoot)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(btRoot)) {
                for (Path d : ds) {
                    String n = d.getFileName().toString();
                    if (!n.matches("\\d+(\\.\\d+)*")) {
                        continue;
                    }
                    if (!Files.isRegularFile(d.resolve(exe("aapt2"))) || !Files.isRegularFile(d.resolve(exe("zipalign")))
                            || !Files.isRegularFile(d.resolve("lib/d8.jar")) || !Files.isRegularFile(d.resolve("lib/apksigner.jar"))) {
                        continue;
                    }
                    if (s.buildToolsName == null || compareVer(n, s.buildToolsName) > 0) {
                        s.buildToolsName = n;
                        s.aapt2 = d.resolve(exe("aapt2"));
                        s.zipalign = d.resolve(exe("zipalign"));
                        s.d8Jar = d.resolve("lib/d8.jar");
                        s.apksignerJar = d.resolve("lib/apksigner.jar");
                    }
                }
            } catch (IOException e) {
                return null;
            }
        }
        if (s.buildToolsName == null) {
            return null;
        }
        Path pRoot = home.resolve("platforms");
        if (Files.isDirectory(pRoot)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(pRoot)) {
                for (Path d : ds) {
                    Matcher m = Pattern.compile("android-(\\d+)").matcher(d.getFileName().toString());
                    if (m.matches() && Files.isRegularFile(d.resolve("android.jar"))) {
                        int api = Integer.parseInt(m.group(1));
                        if (api >= 30 && api > s.api) {
                            s.api = api;
                            s.androidJar = d.resolve("android.jar");
                        }
                    }
                }
            } catch (IOException e) {
                return null;
            }
        }
        return s.androidJar == null ? null : s;
    }

    static int compareVer(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    static Sdk ensureSdk() throws Exception {
        Sdk s = findSdk();
        if (s != null) {
            return s;
        }
        System.out.println("  No usable Android SDK found - installing the minimum one into " + sdkTarget());
        setup();
        s = probe(sdkTarget());
        if (s == null) {
            throw new Fail("The SDK in " + sdkTarget() + " is still incomplete after setup (need platforms/android-30+ and build-tools).");
        }
        return s;
    }

    /** Downloads cmdline-tools (if needed) and installs platform, build-tools and platform-tools with sdkmanager. */
    static void setup() throws Exception {
        Path sdk = sdkTarget();
        Files.createDirectories(sdk);
        Path tools = sdk.resolve("cmdline-tools").resolve("latest");
        if (!Files.isDirectory(tools.resolve("lib"))) {
            Path zip = findLocal("commandlinetools-*.zip");
            if (zip == null) {
                String os = WIN ? "win" : MAC ? "mac" : "linux";
                String url = System.getenv("PH_CMDLINE_TOOLS_URL");
                if (url == null || url.isEmpty()) {
                    url = "https://dl.google.com/android/repository/commandlinetools-" + os + "-" + CMDLINE_REV + "_latest.zip";
                }
                zip = root.resolve("libs").resolve("commandlinetools-" + os + "-" + CMDLINE_REV + "_latest.zip");
                System.out.println("  downloading Android command-line tools");
                fetchAny(Collections.singletonList(url), zip,
                        "Could not download the Android command-line tools. Turn on your VPN, or download\n  "
                                + url + "\nin a browser, put the zip into the libs/ folder and run again.");
            } else {
                System.out.println("  using " + zip);
            }
            deleteTree(tools);
            unzip(zip, tools, 1);
            if (!WIN) {
                Path bin = tools.resolve("bin");
                if (Files.isDirectory(bin)) {
                    try (Stream<Path> s = Files.list(bin)) {
                        for (Path p : s.collect(Collectors.toList())) {
                            p.toFile().setExecutable(true);
                        }
                    }
                }
            }
        }

        List<String> pkgs = new ArrayList<>();
        if (!Files.isRegularFile(sdk.resolve("platforms").resolve("android-" + SDK_PLATFORM).resolve("android.jar"))) {
            pkgs.add("platforms;android-" + SDK_PLATFORM);
        }
        if (!Files.isRegularFile(sdk.resolve("build-tools").resolve(SDK_BUILD_TOOLS).resolve(exe("aapt2")))) {
            pkgs.add("build-tools;" + SDK_BUILD_TOOLS);
        }
        if (!Files.isRegularFile(sdk.resolve("platform-tools").resolve(exe("adb")))) {
            pkgs.add("platform-tools");
        }
        if (pkgs.isEmpty()) {
            System.out.println("  SDK packages are already installed");
            return;
        }

        String mainClass = "com.android.sdklib.tool.sdkmanager.SdkManagerCli";
        Path script = tools.resolve("bin").resolve(WIN ? "sdkmanager.bat" : "sdkmanager");
        if (Files.isRegularFile(script)) {
            Matcher m = Pattern.compile("(com\\.android\\.[A-Za-z0-9_.$]*SdkManagerCli)")
                    .matcher(new String(Files.readAllBytes(script), StandardCharsets.ISO_8859_1));
            if (m.find()) {
                mainClass = m.group(1);
            }
        }
        List<String> cmd = new ArrayList<>(Arrays.asList(
                javaExe(),
                "-Dcom.android.sdklib.toolsdir=" + tools,
                "-classpath", tools.resolve("lib").toString() + File.separator + "*",
                mainClass,
                "--sdk_root=" + sdk));
        String proxy = System.getenv("PH_SDK_PROXY");
        if (proxy != null && proxy.contains(":")) {
            cmd.add("--proxy=http");
            cmd.add("--proxy_host=" + proxy.substring(0, proxy.lastIndexOf(':')));
            cmd.add("--proxy_port=" + proxy.substring(proxy.lastIndexOf(':') + 1));
        }
        cmd.addAll(pkgs);
        System.out.println("  sdkmanager: " + String.join(" ", pkgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            // answers "y" to every licence question
            for (int i = 0; i < 100; i++) {
                os.write("y\n".getBytes(StandardCharsets.US_ASCII));
            }
            os.flush();
        } catch (IOException ignored) {
            // sdkmanager did not need the answers
        }
        int rc = p.waitFor();
        if (rc != 0) {
            throw new Fail("sdkmanager failed (exit code " + rc + "). Most likely dl.google.com is unreachable: turn on the VPN"
                    + " (or set PH_SDK_PROXY=127.0.0.1:PORT) and run again.");
        }
    }

    // ------------------------------------------------------------------ install over adb

    static void install(String host) throws Exception {
        Path apk = root.resolve(APK_NAME);
        if (!Files.isRegularFile(apk)) {
            throw new Fail(APK_NAME + " not found. Run:  java Build.java");
        }
        Sdk s = findSdk();
        String adb = "adb";
        Path sdk = s != null ? s.home : sdkTarget();
        Path local = sdk.resolve("platform-tools").resolve(exe("adb"));
        if (Files.isRegularFile(local)) {
            adb = local.toString();
            fixExecutable(local);
        }
        if (host != null) {
            exec(Arrays.asList(adb, "connect", host.contains(":") ? host : host + ":5555"), "adb connect");
        }
        exec(Arrays.asList(adb, "install", "-r", apk.toString()), "adb install");
        try {
            // Gives PlayHack5 access to all files (videos, music, photos) without going through the settings screens.
            new ProcessBuilder(adb, "shell", "appops", "set", APP_ID, "MANAGE_EXTERNAL_STORAGE", "allow").inheritIO().start().waitFor();
        } catch (IOException ignored) {
            // not important
        }
        System.out.println("Installed. Launch PlayHack5 from the app list.");
    }

    // ------------------------------------------------------------------ helpers

    static String exe(String name) {
        return WIN ? name + ".exe" : name;
    }

    static String javaExe() {
        return Paths.get(System.getProperty("java.home"), "bin", exe("java")).toString();
    }

    static void fixExecutable(Path p) {
        if (!WIN && Files.isRegularFile(p) && !Files.isExecutable(p)) {
            p.toFile().setExecutable(true);
        }
    }

    static boolean hasFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(Files::isRegularFile);
        }
    }

    static Path findLocal(String glob) throws IOException {
        Path libs = root.resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libs, glob)) {
            List<Path> found = new ArrayList<>();
            for (Path p : ds) {
                if (Files.isRegularFile(p) && Files.size(p) > 100_000) {
                    found.add(p);
                }
            }
            Collections.sort(found);
            return found.isEmpty() ? null : found.get(found.size() - 1);
        }
    }

    static void exec(List<String> cmd, String what) throws Exception {
        int rc = new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (rc != 0) {
            throw new Fail(what + " failed (exit code " + rc + "). The message above says why.");
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) != -1) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        List<Path> all;
        try (Stream<Path> s = Files.walk(p)) {
            all = s.sorted(Collections.reverseOrder()).collect(Collectors.toList());
        }
        for (Path x : all) {
            Files.deleteIfExists(x);
        }
    }

    static void zipDir(Path dir, Path zip) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (Path f : files) {
                z.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace(File.separatorChar, '/')));
                Files.copy(f, z);
                z.closeEntry();
            }
        }
    }

    /** Unpacks a zip; strip = number of leading path components to drop. */
    static void unzip(Path zip, Path dest, int strip) throws IOException {
        Files.createDirectories(dest);
        Path base = dest.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String[] parts = e.getName().replace('\\', '/').split("/");
                if (parts.length <= strip) {
                    continue;
                }
                String rel = String.join("/", Arrays.copyOfRange(parts, strip, parts.length));
                if (rel.isEmpty()) {
                    continue;
                }
                Path out = base.resolve(rel).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("unsafe path in zip: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Tries each URL in turn (mirrors). */
    static void fetchAny(List<String> urls, Path target, String failHelp) {
        for (String u : urls) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    System.out.println("  " + u);
                    download(u, target);
                    return;
                } catch (IOException e) {
                    System.out.println("  failed: " + e);
                }
            }
        }
        throw new Fail(failHelp);
    }

    static void download(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        String cur = url;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(cur).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (PlayHack5 build script)");
            int rc = c.getResponseCode();
            if (rc >= 300 && rc < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) {
                    throw new IOException("redirect without Location from " + cur);
                }
                cur = URI.create(cur).resolve(loc).toString();
                continue;
            }
            if (rc != 200) {
                c.disconnect();
                throw new IOException("HTTP " + rc);
            }
            long len = c.getContentLengthLong();
            long done = 0;
            long last = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 700) {
                        last = now;
                        System.out.print("\r    " + (done >> 20) + (len > 0 ? " / " + (len >> 20) : "") + " MB   ");
                    }
                }
            }
            System.out.println("\r    " + (done >> 20) + " MB - done          ");
            if (len > 0 && Files.size(part) != len) {
                throw new IOException("download was cut off");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        throw new IOException("too many redirects");
    }
}
