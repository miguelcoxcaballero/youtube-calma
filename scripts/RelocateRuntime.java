package es.calma.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper;
import org.jetbrains.org.objectweb.asm.commons.Remapper;

/** Isolates this extension's dependencies without modifying their source or algorithms. */
public final class RelocateRuntime {
    private static final String[][] PREFIXES = {
        {"kotlin/", "es/calma/vendor/kotlin/"},
        {"androidx/", "es/calma/vendor/androidx/"},
        {"android/support/", "es/calma/vendor/support/"},
        {"org/jetbrains/annotations/", "es/calma/vendor/annotations/"},
        {"org/intellij/lang/annotations/", "es/calma/vendor/intellij/annotations/"}
    };
    private static String rename(String name) {
        for (String[] prefix : PREFIXES) if (name.startsWith(prefix[0])) return prefix[1] + name.substring(prefix[0].length());
        return name;
    }
    private static byte[] read(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int size;
            while ((size = input.read(buffer)) >= 0) output.write(buffer, 0, size);
            return output.toByteArray();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("RelocateRuntime <output.jar> <input.jar>...");
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            try (JarFile archive = new JarFile(args[i])) {
                for (JarEntry entry : java.util.Collections.list(archive.entries())) {
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) continue;
                    if (inputs.put(name.substring(0, name.length() - 6), read(archive.getInputStream(entry))) != null)
                        throw new IllegalStateException("Duplicate extension input class: " + name);
                }
            }
        }
        final Map<String, String> binaryNames = new LinkedHashMap<>();
        for (String name : inputs.keySet()) binaryNames.put(name.replace('/', '.'), rename(name).replace('/', '.'));
        // ReflectionFactory may be absent from the small stdlib; never let it bind Instagram's Kotlin.
        binaryNames.put("kotlin.reflect.jvm.internal.ReflectionFactory", "es.calma.vendor.kotlin.reflect.jvm.internal.ReflectionFactory");
        binaryNames.put("kotlin.internal.jdk8.JDK8PlatformImplementations", "es.calma.vendor.kotlin.internal.jdk8.JDK8PlatformImplementations");
        binaryNames.put("kotlin.internal.jdk7.JDK7PlatformImplementations", "es.calma.vendor.kotlin.internal.jdk7.JDK7PlatformImplementations");
        Remapper remapper = new Remapper() {
            @Override public String map(String internalName) { return rename(internalName); }
            @Override public Object mapValue(Object value) {
                // Only literal binary class names are changed. Manifest metadata keys and messages stay intact.
                if (value instanceof String && binaryNames.containsKey(value)) return binaryNames.get(value);
                return super.mapValue(value);
            }
        };
        Map<String, byte[]> output = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : inputs.entrySet()) {
            ClassReader reader = new ClassReader(entry.getValue());
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            String mapped = rename(entry.getKey()) + ".class";
            if (output.put(mapped, writer.toByteArray()) != null) throw new IllegalStateException("Relocation collision: " + mapped);
        }
        File destination = new File(args[0]);
        destination.getParentFile().mkdirs();
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(destination.toPath()))) {
            for (Map.Entry<String, byte[]> entry : output.entrySet()) {
                JarEntry zipEntry = new JarEntry(entry.getKey());
                zipEntry.setTime(1767225600000L);
                archive.putNextEntry(zipEntry);
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        System.out.println("Isolated " + output.size() + " extension classes in " + destination);
    }
}
