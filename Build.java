import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * No-Gradle APK builder.   Usage:   java Build.java [path-to-android-sdk]
 * Needs in the SDK: build-tools (aapt2, d8, zipalign, apksigner) and platforms/android-XX/android.jar.
 * Output: Launcher.apk (signed with a local debug.keystore that is created on first run).
 */
public class Build {
    static final boolean WIN = System.getProperty("os.name").toLowerCase().contains("win");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        String sdkStr = args.length > 0 ? args[0] : System.getenv("ANDROID_HOME");
        if (sdkStr == null) sdkStr = System.getenv("ANDROID_SDK_ROOT");
        if (sdkStr == null) die("Укажите путь к Android SDK: java Build.java C:\\android-sdk  (или переменная ANDROID_HOME)");
        Path sdk = Paths.get(sdkStr);

        Path bt = newest(sdk.resolve("build-tools"), null);
        Path pf = newest(sdk.resolve("platforms"), "android.jar");
        Path androidJar = pf.resolve("android.jar");
        System.out.println("build-tools: " + bt.getFileName() + ", platform: " + pf.getFileName());

        String exe = WIN ? ".exe" : "";
        String bat = WIN ? ".bat" : "";
        String aapt2 = bt.resolve("aapt2" + exe).toString();
        String zipalign = bt.resolve("zipalign" + exe).toString();
        String d8 = bt.resolve("d8" + bat).toString();
        String apksigner = bt.resolve("apksigner" + bat).toString();
        String javaHome = System.getProperty("java.home");
        String javac = Paths.get(javaHome, "bin", "javac" + exe).toString();
        String keytool = Paths.get(javaHome, "bin", "keytool" + exe).toString();
        if (!Files.exists(Paths.get(javac))) javac = "javac"; // JRE only? try PATH

        Path out = root.resolve("build");
        deleteTree(out);
        Files.createDirectories(out.resolve("classes"));
        Files.createDirectories(out.resolve("dex"));

        // 1. resources
        run(aapt2, "compile", "--dir", root.resolve("res").toString(), "-o", out.resolve("res.zip").toString());
        run(aapt2, "link", "-o", out.resolve("base.apk").toString(),
                "-I", androidJar.toString(),
                "--manifest", root.resolve("AndroidManifest.xml").toString(),
                "--min-sdk-version", "21", "--target-sdk-version", "30",
                "--version-code", "1", "--version-name", "1.0",
                out.resolve("res.zip").toString());

        // 2. java -> class
        List<String> javaFiles;
        try (Stream<Path> s = Files.walk(root.resolve("src"))) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).collect(Collectors.toList());
        }
        List<String> cmd = new ArrayList<>(Arrays.asList(javac, "-Xlint:-options", "-source", "8", "-target", "8",
                "-bootclasspath", androidJar.toString(), "-classpath", androidJar.toString(),
                "-d", out.resolve("classes").toString()));
        cmd.addAll(javaFiles);
        run(cmd.toArray(new String[0]));

        // 3. class -> dex
        List<String> classFiles;
        try (Stream<Path> s = Files.walk(out.resolve("classes"))) {
            classFiles = s.filter(p -> p.toString().endsWith(".class")).map(Path::toString).collect(Collectors.toList());
        }
        cmd = new ArrayList<>(Arrays.asList(d8, "--release", "--min-api", "21", "--lib", androidJar.toString(),
                "--output", out.resolve("dex").toString()));
        cmd.addAll(classFiles);
        run(cmd.toArray(new String[0]));

        // 4. base.apk + classes.dex -> unsigned.apk  (resources.arsc must stay STORED for targetSdk 30)
        Path unsigned = out.resolve("unsigned.apk");
        try (ZipFile zf = new ZipFile(out.resolve("base.apk").toFile());
             ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(unsigned.toFile()))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                byte[] data = zf.getInputStream(e).readAllBytes();
                putEntry(zo, e.getName(), data, e.getName().equals("resources.arsc") || e.getMethod() == ZipEntry.STORED);
            }
            byte[] dex = Files.readAllBytes(out.resolve("dex").resolve("classes.dex"));
            putEntry(zo, "classes.dex", dex, false);
        }

        // 5. align + sign
        Path aligned = out.resolve("aligned.apk");
        run(zipalign, "-f", "-p", "4", unsigned.toString(), aligned.toString());

        Path ks = root.resolve("debug.keystore");
        if (!Files.exists(ks)) {
            run(keytool, "-genkeypair", "-keystore", ks.toString(), "-storepass", "android", "-keypass", "android",
                    "-alias", "key", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Launcher");
        }
        Path apk = root.resolve("Launcher.apk");
        Files.deleteIfExists(apk);
        run(apksigner, "sign", "--ks", ks.toString(), "--ks-pass", "pass:android", "--key-pass", "pass:android",
                "--out", apk.toString(), aligned.toString());
        System.out.println("\nГотово: " + apk);
    }

    static void putEntry(ZipOutputStream zo, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            ne.setCrc(crc.getValue());
        }
        zo.putNextEntry(ne);
        zo.write(data);
        zo.closeEntry();
    }

    static Path newest(Path dir, String mustContain) throws IOException {
        if (!Files.isDirectory(dir)) die("Нет папки: " + dir);
        List<Path> l;
        try (Stream<Path> s = Files.list(dir)) {
            l = s.filter(Files::isDirectory)
                    .filter(p -> mustContain == null || Files.exists(p.resolve(mustContain)))
                    .collect(Collectors.toList());
        }
        if (l.isEmpty()) die("В " + dir + " ничего не найдено (поставьте через sdkmanager)");
        l.sort(Comparator.comparing((Path p) -> key(p.getFileName().toString())));
        return l.get(l.size() - 1);
    }

    /** "34.0.0" / "android-34" -> sortable string. */
    static String key(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.replaceAll("[^0-9.]", " ").trim().split("[ .]+")) {
            if (part.isEmpty()) continue;
            sb.append(String.format("%08d", Long.parseLong(part)));
        }
        return sb.toString();
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) die("Команда завершилась с кодом " + code + ": " + cmd[0]);
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }

    static void die(String msg) {
        System.err.println("ОШИБКА: " + msg);
        System.exit(1);
    }
}
