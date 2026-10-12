package inhouse.microg.host;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Executes the production extraction code with adversarial ZIP/cache fixtures. */
public final class EmbeddedNativeIntegrityRegression {
    private static final String[] ABI = {"x86_64"};
    private static final Method EXTRACT;
    static {
        try {
            EXTRACT = EmbeddedRuntime.class.getDeclaredMethod("extractLibraries", ZipFile.class,
                    File.class, String[].class, String[][].class);
            EXTRACT.setAccessible(true);
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    private static final class CountedZip extends ZipFile {
        int streams;
        CountedZip(File file) throws IOException { super(file); }
        @Override public InputStream getInputStream(ZipEntry entry) throws IOException {
            streams++;
            return super.getInputStream(entry);
        }
    }

    private static File extract(CountedZip zip, Path output, String[][] pins) throws Exception {
        try { return (File) EXTRACT.invoke(null, zip, output.toFile(), ABI, pins); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw wrapped;
        }
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static void rejected(Path root, String archive, String[][] pins, int expectedStreams)
            throws Exception {
        Path output = Files.createTempDirectory(root, "rejected-");
        try (CountedZip zip = new CountedZip(root.resolve(archive).toFile())) {
            try {
                extract(zip, output, pins);
                throw new AssertionError("Accepted invalid fixture: " + archive);
            } catch (SecurityException expected) {
                check(zip.streams == expectedStreams, "Unexpected inflation while rejecting " + archive);
            }
        }
        try (var paths = Files.walk(output)) {
            check(paths.noneMatch(Files::isRegularFile), "Rejected bytes left a published/partial file");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<String> lines = Files.readAllLines(root.resolve("pins.tsv"));
        String[][] pins = lines.stream().map(line -> line.split("\t", -1)).toArray(String[][]::new);
        Path output = Files.createDirectory(root.resolve("cache"));
        File nativeDir;
        try (CountedZip zip = new CountedZip(root.resolve("valid.zip").toFile())) {
            nativeDir = extract(zip, output, pins);
            check(zip.streams == 2, "Cold cache must inflate each selected library exactly once");
            check(nativeDir.getName().equals("lib-x86_64"), "Wrong ABI selected");
            check(nativeDir.list().length == 2, "Extracted another ABI or unexpected files");
        }
        Path first = nativeDir.toPath().resolve("liba.so");
        byte[] expected = Files.readAllBytes(root.resolve("expected-liba.bin"));
        check(Arrays.equals(Files.readAllBytes(first), expected), "Cold native bytes differ");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(first);
        check(!permissions.contains(PosixFilePermission.OWNER_WRITE)
                && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                && !permissions.contains(PosixFilePermission.OTHERS_WRITE), "Native file is writable");
        FileTime marker = FileTime.fromMillis(123456000L);
        Files.setLastModifiedTime(first, marker);
        try (CountedZip zip = new CountedZip(root.resolve("valid.zip").toFile())) {
            extract(zip, output, pins);
            check(zip.streams == 0, "Warm cache must not inflate native entries");
            check(Files.getLastModifiedTime(first).equals(marker), "Warm cache was rewritten");
        }
        check(first.toFile().setWritable(true, true), "Cannot mutate test fixture");
        Files.write(first, new byte[] {1, 2, 3});
        try (CountedZip zip = new CountedZip(root.resolve("valid.zip").toFile())) {
            extract(zip, output, pins);
            check(zip.streams == 1, "Corrupt cache must inflate only its damaged entry");
            check(Arrays.equals(Files.readAllBytes(first), expected), "Corrupt cache was not repaired");
            check(!Files.getPosixFilePermissions(first).contains(PosixFilePermission.OWNER_WRITE),
                    "Repaired cache is writable");
        }
        rejected(root, "unexpected.zip", pins, 0);
        rejected(root, "missing.zip", pins, 0);
        rejected(root, "duplicate.zip", pins, 0);
        rejected(root, "traversal.zip", pins, 0);
        rejected(root, "tampered.zip", pins, 1);
        String[][] duplicatePins = Arrays.copyOf(pins, pins.length + 1);
        duplicatePins[pins.length] = pins[0];
        rejected(root, "valid.zip", duplicatePins, 0);
        String[][] badDigest = new String[pins.length][];
        for (int index = 0; index < pins.length; index++) {
            badDigest[index] = pins[index].clone();
            if (pins[index][0].endsWith("/liba.so")) badDigest[index][1] = "0".repeat(64);
        }
        rejected(root, "valid.zip", badDigest, 1);
        System.out.println("NATIVE_INTEGRITY_OK cold=2 warm=0 repaired=1 rejected=7");
    }
}
