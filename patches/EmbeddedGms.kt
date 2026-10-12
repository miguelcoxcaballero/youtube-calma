package es.calma.youtube.patches

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File

private const val EMBED_HOST = "app.revanced.android.youtube"
private const val EXTERNAL_GMS = "app.revanced.android.gms"
private const val EMBED_ACCOUNT = "es.inhouse.youtube.google"
private const val EMBED_BRIDGE = "Linhouse/microg/host/YouTubeGmsBridge;"

private fun Method.routeSignature(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Compile stubs cannot describe optimizer changes in the actual host DEX. In
 * particular, this base makes CoreComponentFactory callbacks final even though
 * ordinary AndroidX source allows overriding them. Catch the resulting runtime
 * LinkageError before producing an APK. Platform ancestors absent from the DEX
 * are checked by the android.jar compiler instead.
 */
private fun validateEmbeddedHostInheritance(hostClasses: Iterable<ClassDef>, resolve: (String) -> ClassDef?) {
    fun Method.virtual() = name != "<init>" && name != "<clinit>" && accessFlags and (2 or 8) == 0
    fun Method.prototype() = "$name(${parameterTypes.joinToString("")})$returnType"
    fun packageName(type: String) = type.substringBeforeLast('/', "")

    for (type in hostClasses) {
        val virtualMethods = type.methods.filter { it.virtual() }.associateBy { it.prototype() }
        val visited = linkedSetOf(type.type)
        var ancestorName = type.superclass
        while (ancestorName != null) {
            check(visited.add(ancestorName)) { "Embedded host inheritance cycle: ${visited.joinToString(" -> ")} -> $ancestorName" }
            val ancestor = resolve(ancestorName) ?: break
            check(ancestor.accessFlags and 16 == 0) {
                "Embedded host ${type.type} inherits final class ${ancestor.type} from actual DEX"
            }
            for (inherited in ancestor.methods) {
                if (!inherited.virtual() || inherited.accessFlags and 16 == 0) continue
                // A package-private method outside the subclass package is not
                // inherited. Public/protected methods are inherited across it.
                if (inherited.accessFlags and (1 or 4) == 0 &&
                    packageName(inherited.definingClass) != packageName(type.type)) continue
                val override = virtualMethods[inherited.prototype()] ?: continue
                error("Embedded host method ${override.routeSignature()} overrides final inherited method " +
                    "${inherited.routeSignature()} in actual DEX (flags=0x${inherited.accessFlags.toString(16)})")
            }
            ancestorName = ancestor.superclass
        }
    }
}

/**
 * Experimental, opt-in client routes for the EXACT base.lock.json APK.
 *
 * Normal compilation does not bundle the runtime and execute makes no changes.
 * Enable only alongside the resource/backend integration using
 * -Dcalma.embeddedGms=true. This does not assert authenticated playback works.
 * See experiments/embedded-gms/CLIENT-ROUTING.md for the inspected callsites.
 */
val embeddedGmsClientPatch = bytecodePatch(name = "YouTube Calma experimental embedded GMS client") {
    compatibleWith(EMBED_HOST("20.40.45"))
    dependsOn(behaviorPatch)
    if (System.getProperty("calma.embeddedGms") == "true") extendWith("embedded-host.dex")
    execute {
        if (System.getProperty("calma.embeddedGms") != "true") return@execute

        val embeddedHostClasses = mutableListOf<ClassDef>()
        classDefForEach { if (it.type.startsWith("Linhouse/microg/host/")) embeddedHostClasses.add(it) }
        check(embeddedHostClasses.isNotEmpty()) { "Embedded host runtime DEX was not injected" }
        validateEmbeddedHostInheritance(embeddedHostClasses) { classDefByOrNull(it) }

        fun method(signature: String): MutableMethod {
            val owner = signature.substringBefore("->")
            return mutableClassDefBy(owner).methods.single { it.routeSignature() == signature }
        }
        fun strings(target: Method): List<String> = target.implementation?.instructions
            ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
            ?.toList() ?: emptyList()

        // Capture callsites before modifying them so the report cannot mistake an
        // old, intentionally untouched identity comparison for a routed service.
        val oldRoutes = linkedMapOf<String, List<String>>()
        val binderLiterals = linkedMapOf<String, List<String>>()
        val binderDescriptor = Regex("""(?:com\.google\.|org\.microg\.)[\w.$]*\.I[A-Z][\w$]*""")
        classDefForEach { type ->
            // Count only pre-existing client namespaces, not constants in the
            // separately supplied host integration DEX or Calma updater.
            if (!type.type.startsWith("Linhouse/") && !type.type.startsWith("Les/calma/") &&
                !type.type.startsWith("Les/inhouse/") && !type.type.startsWith("Lcom/inhouse/")) type.methods.forEach { target ->
                val literals = strings(target)
                val descriptors = literals.filter { binderDescriptor.matches(it) }
                if (descriptors.isNotEmpty()) binderLiterals[target.routeSignature()] = descriptors
                val matches = literals.filter {
                    it == "app.revanced" || it.startsWith("app.revanced.android.gms") ||
                    it.startsWith("content://app.revanced.android.") ||
                    it.startsWith("app.revanced.android.c2dm.permission.") ||
                    it.startsWith("app.revanced.android.providers.gsf.permission.")
                }
                if (matches.isNotEmpty()) oldRoutes[target.routeSignature()] = matches
            }
        }
        val vendorAccountCount = oldRoutes.values.sumOf { list -> list.count { it == "app.revanced" } }
        check(vendorAccountCount == 40) {
            "Pinned base changed: expected 40 vendor/account string instructions, found $vendorAccountCount: " +
                oldRoutes.filterValues { "app.revanced" in it }.keys.joinToString()
        }
        val changed = linkedMapOf<Pair<String, String>, Int>()
        val replacedBodies = linkedSetOf<String>()

        fun literal(signature: String, from: String, to: String, expected: Int = 1) {
            val target = method(signature)
            val matches = target.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == from
            }
            check(matches.size == expected) { "$signature expected $expected copies of $from, found ${matches.size}" }
            for ((index, instruction) in matches) {
                check(instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO)
                val register = (instruction as OneRegisterInstruction).registerA
                target.replaceInstruction(index, BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, register, ImmutableStringReference(to)))
            }
            changed[signature to from] = expected
        }

        // Exact account construction, account-type validation, account enumeration,
        // listener registration, add/choose-account and sync-settings callsites.
        // DO NOT rewrite vendorGroup or Lablo.k's accessibility-package prefix.
        val accountSites = listOf(
            "Lalye;->run()V",
            "Lcom/google/android/gms/auth/AccountChangeEventsRequest;-><init>(IILjava/lang/String;Landroid/accounts/Account;)V",
            "Lcom/google/android/gms/people/consentprimitive/ContactsConsentsDetailedStatus;->a()Larbo;",
            "Lxdy;->s(Lwfi;Ljava/lang/String;I)Lcom/google/common/util/concurrent/ListenableFuture;",
            "Lalwf;->f()V",
            "Lamsm;->apply(Ljava/lang/Object;)Ljava/lang/Object;",
            "Lrgu;->b(Lcom/google/android/gms/common/api/Status;Lcom/google/android/gms/people/contactssync/model/BackupAndSyncOptInState;Lcom/google/android/gms/common/api/ApiMetadata;)V",
            "Lrlw;->k(ILjava/lang/String;Ljava/lang/String;IZLjava/lang/String;)Landroid/os/Bundle;",
            "Lwrv;->a(Ljava/lang/String;)Z",
            "Lxdc;->a()Ljava/lang/String;",
            "Lvls;->b(Ljava/lang/Object;)Ljava/lang/Object;",
            "Lvlt;->g(Ljava/lang/String;Ljava/lang/String;Lbldh;)Ljava/lang/Object;",
            "Lwfg;->e(Lxuk;)V",
            "Laiau;->a()V",
            "Lvqx;->c()Ljava/util/Set;",
            "Lwbt;->b(Ljava/lang/Object;)Ljava/lang/Object;",
            "Lwbu;->b(Ljava/lang/String;Lcom/google/android/libraries/onegoogle/consent/model/RequestInfo;Lbldh;)Ljava/lang/Object;",
            "Lwbu;->d(Lcom/google/android/libraries/onegoogle/consent/model/RequestInfo;)Z",
            "Laour;->d(Ljava/lang/String;Laori;Laotj;)Lcom/google/common/util/concurrent/ListenableFuture;",
            "Lqlr;->b()V",
            "Lqmm;->B(Lqnf;Ljava/util/Set;)V",
            "Lypk;->c()V",
            "Ltqa;->a(Landroid/content/Context;Lcom/google/android/libraries/onegoogle/consent/model/Account;Lblez;Lblev;)Ljava/lang/Object;",
            "Lyns;->a(Lajth;)Lajtr;",
            "Lynt;->a(Lajth;)Lajtr;",
            "Lyvz;->h(Landroid/app/Activity;Lachr;)V",
            "Laohq;->apply(Ljava/lang/Object;)Ljava/lang/Object;",
            "Lpxy;-><clinit>()V",
            "Lpxy;->c(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;",
            "Lpxy;->j(Landroid/content/Context;)[Landroid/accounts/Account;",
            "Lqjm;->u()Lqmo;",
            "Lafbt;->call()Ljava/lang/Object;",
            "Lcom/google/android/libraries/onegoogle/accountmanagement/AddAccountActivity;->onCreate(Landroid/os/Bundle;)V",
            "Lpxq;->a(Landroid/content/Context;[Ljava/lang/String;)[Landroid/accounts/Account;",
            "Lpxr;-><init>([Ljava/lang/String;Ljava/lang/String;Lqfy;JJ)V",
            "Lypt;->d(Lcom/google/android/libraries/youtube/account/identity/AccountIdentity;)Lajtr;"
        )
        check(accountSites.size == 36 && accountSites.toSet().size == 36)
        accountSites.forEach { literal(it, "app.revanced", EMBED_ACCOUNT) }

        // Binding and explicit outgoing component/Intent destinations only.
        // GMS identity comparisons, signature classifiers, resource lookups and
        // GetServiceRequest caller metadata are deliberately not globally changed.
        val packageSites = listOf(
            "Lpxy;-><clinit>()V", // Explicit GetToken component.
            "Lqnb;-><init>(Ljava/lang/String;Z[B)V", // Service connection configuration.
            "Laqwp;-><init>(Ljava/lang/String;Z)V", // Alternate connection configuration.
            "Lqif;->a(Landroid/os/Bundle;)Lrju;", // IID registration request.
            "Lqhw;->e(Lqhy;)Z", // Legacy c2dm registration binding.
            "Lrlj;-><init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Z)V",
            "Lblzg;->bB()V", // Account settings intent.
            "Lhsh;->b(Lavfw;Ljava/util/Map;)V", // Account settings intent.
            "Lude;->a(Landroid/content/Context;Ljava/lang/Object;)Landroid/content/Intent;"
        )
        packageSites.forEach { literal(it, EXTERNAL_GMS, EMBED_HOST) }
        // Three bundled generations of IID metadata use the same permission and
        // destination checks. Keep checks; route them to the host-owned permission.
        val iidOwners = listOf("Lqib;", "Lasiy;", "Lashh;")
        for (owner in iidOwners) {
            literal("$owner->b()I", EXTERNAL_GMS, EMBED_HOST, 2)
            literal("$owner->b()I", "app.revanced.android.c2dm.permission.SEND", "$EMBED_HOST.inhouse.gms.c2dm.permission.SEND")
        }
        for (signature in listOf("Lqhm;->b(Lqjd;)V", "Lpwn;->mi()Ljava/lang/Object;", "Lwor;->b()Ljava/lang/Object;")) {
            literal(signature, "app.revanced.android.providers.gsf.permission.READ_GSERVICES",
                "$EMBED_HOST.inhouse.gms.gsf.permission.READ_GSERVICES")
        }
        for (signature in listOf("Lpxy;->j(Landroid/content/Context;)[Landroid/accounts/Account;", "Lvqx;->c()Ljava/util/Set;")) {
            literal(signature, "$EXTERNAL_GMS.auth.accounts", "$EMBED_HOST.inhouse.gms.auth.accounts")
        }
        literal("Lrmc;-><clinit>()V", "content://app.revanced.android.gsf.gservices", "content://$EMBED_HOST.inhouse.gms.gservices")
        literal("Lrmc;-><clinit>()V", "content://app.revanced.android.gsf.gservices/prefix", "content://$EMBED_HOST.inhouse.gms.gservices/prefix")

        // Replace complete bodies, including obsolete exception handlers/debug
        // offsets, rather than leaving unreachable external package lookups.
        fun replaceBody(signature: String, expectedLiteral: String?, literalCount: Int, code: String, isStatic: Boolean = true) {
            val old = method(signature)
            check((old.accessFlags and 8 != 0) == isStatic) { "Static/instance mismatch: $signature" }
            if (expectedLiteral != null) check(strings(old).count { it == expectedLiteral } == literalCount) {
                "Pinned lookup body changed: $signature"
            }
            val replacement = MutableMethod(ImmutableMethod(old.definingClass, old.name, old.parameters,
                old.returnType, old.accessFlags, old.annotations, old.hiddenApiRestrictions,
                ImmutableMethodImplementation(old.implementation!!.registerCount, emptyList(), emptyList(), emptyList())))
            replacement.addInstructions(0, code.trimIndent())
            val owner = mutableClassDefBy(old.definingClass)
            check(owner.methods.remove(old))
            check(owner.methods.add(replacement))
            replacedBodies.add(signature)
        }
        val checkSignature = "Lapp/revanced/extension/shared/GmsCoreSupport;->checkGmsCore(Landroid/app/Activity;)V"
        check(method(checkSignature).implementation!!.instructions.count {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lapp/revanced/extension/shared/GmsCoreSupport\$GmsCore;" && ref.name == "check"
        } == 1) { "Pinned GmsCore startup check changed" }
        replaceBody(checkSignature, null, 0, """
            invoke-static/range {p0 .. p0}, $EMBED_BRIDGE->check(Landroid/app/Activity;)V
            return-void
        """)
        replaceBody("Lqiz;->a(Landroid/content/Context;)I", EXTERNAL_GMS, 1, """
            invoke-static/range {p0 .. p0}, $EMBED_BRIDGE->backendVersion(Landroid/content/Context;)I
            move-result v0
            return v0
        """)
        replaceBody("Lqiz;->c(Landroid/content/Context;)Landroid/content/Context;", EXTERNAL_GMS, 1, """
            invoke-static/range {p0 .. p0}, $EMBED_BRIDGE->backendContext(Landroid/content/Context;)Landroid/content/Context;
            move-result-object v0
            return-object v0
        """)
        replaceBody("Lqmq;->e(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;", EXTERNAL_GMS, 2, """
            invoke-static/range {p0 .. p1}, $EMBED_BRIDGE->backendString(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)

        // Android's installed host version is the YouTube version, not the
        // embedded microG protocol/version capability. Keep those identities apart.
        for (owner in iidOwners) {
            check(mutableClassDefBy(owner).fields.count { it.name == "a" && it.type == "Landroid/content/Context;" } == 1) {
                "Pinned IID context field changed: $owner"
            }
            replaceBody("$owner->a()I", EXTERNAL_GMS, 1, """
                iget-object v0, p0, $owner->a:Landroid/content/Context;
                invoke-static {v0}, $EMBED_BRIDGE->backendVersion(Landroid/content/Context;)I
                move-result v0
                return v0
            """, isStatic = false)
        }

        // Fail if an account-type edit accidentally crosses into vendor identity.
        check(strings(method("Lapp/revanced/extension/shared/GmsCoreSupport;->getGmsCoreVendorGroupId()Ljava/lang/String;")).count { it == "app.revanced" } == 2)
        check(strings(method("Lablo;->k(Landroid/content/Context;)Z")).count { it == "app.revanced" } == 1)
        check(strings(method("Lpxy;->a(Landroid/content/Context;Landroid/os/Bundle;)V")).containsAll(listOf("clientPackageName", "service_connection_start_time_millis")))

        // Fail closed on the required routes in the exact base, not on generic
        // namespace absence: intentionally retained vendor/Binder identities are
        // not installed-package dependencies. Optional services remain reported.
        check(changed.values.sum() == 61 && replacedBodies.size == 7) {
            "Pinned embedded route inventory changed"
        }
        accountSites.forEach { signature ->
            val literals = strings(method(signature))
            check("app.revanced" !in literals && literals.count { it == EMBED_ACCOUNT } == 1) {
                "External account type remains in $signature"
            }
        }
        packageSites.forEach { signature ->
            check(EXTERNAL_GMS !in strings(method(signature))) { "External service destination remains: $signature" }
        }
        iidOwners.forEach { owner ->
            check(strings(method("$owner->b()I")).none { it == EXTERNAL_GMS || it == "app.revanced.android.c2dm.permission.SEND" })
        }
        for (signature in listOf("Lqhm;->b(Lqjd;)V", "Lpwn;->mi()Ljava/lang/Object;", "Lwor;->b()Ljava/lang/Object;")) {
            check("app.revanced.android.providers.gsf.permission.READ_GSERVICES" !in strings(method(signature)))
        }
        for ((signature, expected) in binderLiterals) {
            check(strings(method(signature)).filter { binderDescriptor.matches(it) } == expected) {
                "Binder descriptor changed: $signature"
            }
        }

        val remaining = oldRoutes.flatMap { (signature, values) ->
            if (signature in replacedBodies) emptyList() else values.groupingBy { it }.eachCount().mapNotNull { (literal, count) ->
                val untouched = count - (changed[signature to literal] ?: 0)
                if (untouched > 0) "$signature : $literal ($untouched)" else null
            }
        }
        val report = buildString {
            appendLine("Experimental client routing; authenticated operation not verified by patching.")
            appendLine("Base SHA256: 220c4eb6d315a8887ef404d6530b16196701b84848e568360b0b58bdbe3103f2")
            appendLine("Account callsites: ${accountSites.size}; literal instructions changed: ${changed.values.sum()}; complete body adapters: ${replacedBodies.size}")
            appendLine("Binder descriptors, GMS vendor group and real caller extras are preserved.")
            appendLine("Residual references (some intentionally retained identity/vendor checks; optional services remain unported):")
            remaining.forEach { appendLine(it) }
        }
        val reportFile = File(System.getProperty("calma.embeddedGms.report", "build/embedded-gms/client-routing.txt"))
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report)
        println("Embedded GMS: ${changed.values.sum()} literal edits, ${replacedBodies.size} body adapters; residual report: $reportFile")
    }
}
