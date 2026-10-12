import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kotlin.jvm.functions.Function1;

/**
 * Regression for the startup LinkageError caused by the optimized host's final
 * CoreComponentFactory callbacks. Run through scripts/test-embedded-inheritance.py,
 * which verifies the actual APK/tool hashes and supplies the compiled patch bundle.
 * The intentionally invalid subclasses are DEX model fixtures, never installed.
 */
public final class EmbeddedInheritanceRegression {
    private static final String FACTORY = "Linhouse/microg/host/YouTubeComponentFactory;";
    private static final String CORE = "Landroidx/core/app/CoreComponentFactory;";
    private static final Set<String> FINAL_CALLBACKS = Set.of(
            "instantiateActivity", "instantiateApplication", "instantiateProvider",
            "instantiateReceiver", "instantiateService");

    private static ClassDef type(String name, String parent, List<Method> methods) {
        return new ImmutableClassDef(
                name, 1, parent, List.of(), null, Set.of(), List.of(), methods);
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : dex.getDexEntryNames()) {
            for (ClassDef definition : dex.getEntry(entry).getDexFile().getClasses()) {
                types.put(definition.getType(), definition);
            }
        }
        ClassDef core = Objects.requireNonNull(types.get(CORE), "Pinned host factory missing");
        List<Method> overrides = new ArrayList<>();
        for (Method method : core.getMethods()) {
            if (!FINAL_CALLBACKS.contains(method.getName())) continue;
            if (method.getAccessFlags() != 0x11) {
                throw new AssertionError("Pinned callback is not public final: " + method);
            }
            overrides.add(new ImmutableMethod(
                    FACTORY, method.getName(), method.getParameters(), method.getReturnType(),
                    1, Set.of(), Set.of(), null));
        }
        if (overrides.size() != FINAL_CALLBACKS.size()) {
            throw new AssertionError("Expected all five final callbacks from the actual pinned APK");
        }

        java.lang.reflect.Method validate;
        try {
            validate = Class.forName("es.calma.youtube.patches.EmbeddedGmsKt")
                    .getDeclaredMethod("validateEmbeddedHostInheritance", Iterable.class, Function1.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Patch bundle predates the inheritance preflight; rebuild it", exception);
        }
        validate.setAccessible(true);
        Function1<String, ClassDef> resolve = types::get;

        // Every real callback must reject the old direct-subclass implementation.
        for (Method override : overrides) {
            expectRejected(validate, type(FACTORY, CORE, List.of(override)), resolve,
                    "overrides final inherited method", override.getName());
        }
        // A superclass layer must not hide a final method further up the hierarchy.
        ClassDef middle = type("Linhouse/microg/host/Middle;", CORE, List.of());
        types.put(middle.getType(), middle);
        expectRejected(validate, type(FACTORY, middle.getType(), overrides), resolve,
                "overrides final inherited method", "instantiate");

        // The fixed implementation extends the framework factory and delegates
        // AndroidX compatibility handling instead of overriding its final methods.
        validate.invoke(null, List.of(type(FACTORY, "Landroid/app/AppComponentFactory;", overrides)), resolve);
        validate.invoke(null, List.of(type(FACTORY, CORE, List.of())), resolve);
        System.out.println("PASS: five actual final callbacks reject direct overrides; "
                + "recursive override rejects; composition and non-overriding subclass pass");
    }

    private static void expectRejected(
            java.lang.reflect.Method validate, ClassDef definition,
            Function1<String, ClassDef> resolve, String expected, String callback) throws Exception {
        try {
            validate.invoke(null, List.of(definition), resolve);
            throw new AssertionError("Invalid subclass accepted: " + definition.getType());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (!(cause instanceof IllegalStateException)
                    || !cause.getMessage().contains(expected)
                    || !cause.getMessage().contains(callback)
                    || !cause.getMessage().contains("flags=0x11")) {
                throw exception;
            }
        }
    }
}
