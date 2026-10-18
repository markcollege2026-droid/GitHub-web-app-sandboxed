package app.arenawebview.locked;

import java.io.File;

/**
 * Locates project files from a JVM unit test.
 *
 * <p>Gradle runs unit tests with the module directory as the working directory ({@code app/}), but the
 * root project directory is used in some setups, so both are tried.</p>
 */
public final class TestPaths {

    private TestPaths() {
        // no instances
    }

    /** Directory that contains {@code AndroidManifest.xml} and {@code res/}. */
    public static File moduleDir() {
        String[] candidates = {"", "app/", "../app/", "../"};
        for (String candidate : candidates) {
            File dir = new File(candidate.isEmpty() ? "." : candidate);
            if (new File(dir, "src/main/AndroidManifest.xml").isFile()) {
                return dir.getAbsoluteFile();
            }
        }
        throw new IllegalStateException(
                "Could not locate the app module directory from " + new File(".").getAbsolutePath());
    }

    /** Directory that contains the main Java sources. */
    public static File mainSourcesDir() {
        File dir = new File(moduleDir(), "src/main/java");
        if (!dir.isDirectory()) {
            throw new IllegalStateException("Missing " + dir);
        }
        return dir;
    }

    /** A file inside {@code src/main/res}. */
    public static File resource(String relativePath) {
        File file = new File(moduleDir(), "src/main/res/" + relativePath);
        if (!file.isFile()) {
            throw new IllegalStateException("Missing " + file);
        }
        return file;
    }

    /** The app manifest. */
    public static File manifest() {
        File file = new File(moduleDir(), "src/main/AndroidManifest.xml");
        if (!file.isFile()) {
            throw new IllegalStateException("Missing " + file);
        }
        return file;
    }

    /** Read a whole file as text. */
    public static String read(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.Reader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(file), "UTF-8")) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }

    /** Read a source file from the main source set. */
    public static String readSource(String relativePath) throws Exception {
        return read(new File(mainSourcesDir(), relativePath));
    }
}
