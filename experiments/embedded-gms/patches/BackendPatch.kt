package inhouse.microg.patches

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File

private const val ENV = "Linhouse/microg/bridge/BackendEnvironment;"
private const val CONTEXT = "Landroid/content/Context;"
private const val HOST = "app.revanced.android.youtube"
private const val ORIGINAL = "app.revanced.android.gms"
private const val ACCOUNT = "es.inhouse.youtube.google"
private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
private fun mapped(value: String): String {
    if (value == "app.revanced") return ACCOUNT
    if (value == ORIGINAL) return HOST
    // Existing ReVanced caller metadata is kept intact; UID/signature validation is untouched.
    if (value == "$ORIGINAL.SPOOFED_PACKAGE_NAME" || value == "$ORIGINAL.SPOOFED_PACKAGE_SIGNATURE") return value
    endpointMappings.entries.sortedByDescending { it.key.length }.forEach { (before, after) ->
        if (value == before) return after
        if (value.startsWith("content://$before/") || value == "content://$before")
            return "content://$after" + value.removePrefix("content://$before")
    }
    if (value.startsWith("$ORIGINAL.permission.")) return HOST + ".inhouse.gms.permission." + value.removePrefix("$ORIGINAL.permission.")
    if (value.startsWith("app.revanced.android.c2dm.permission.")) return HOST + ".inhouse.gms.c2dm.permission." + value.removePrefix("app.revanced.android.c2dm.permission.")
    if (value.startsWith("app.revanced.android.providers.gsf.permission.")) return HOST + ".inhouse.gms.gsf.permission." + value.removePrefix("app.revanced.android.providers.gsf.permission.")
    return value
}
private fun methodId(m: Method) = m.definingClass + "->" + m.name + "(" + m.parameterTypes.joinToString("") + ")" + m.returnType
private fun sensitiveClass(type: String) = type.startsWith("Lorg/microg/gms/auth/") ||
    type.startsWith("Lcom/google/android/gms/auth/") || type.startsWith("Lorg/microg/gms/checkin/") ||
    type.startsWith("Lorg/microg/gms/gcm/") || type == "Lorg/microg/gms/common/HttpFormClient;"

val embeddedBackend = bytecodePatch(name = "Inhouse same-APK microG backend") {
    compatibleWith(ORIGINAL("0.3.13.2.250932"))
    execute {
        check(packageMetadata.versionCode == "250932004") { "Unrecognized backend version" }
        val stringChanges = mutableListOf<String>()
        val fieldChanges = mutableListOf<String>()
        val hooks = mutableListOf<String>()
        val redactions = mutableListOf<String>()
        var foregroundHooks = 0
        var alarmHooks = 0
        var notificationHooks = 0
        val definitions = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
        classDefForEach { definitions.add(it) }
        for (definition in definitions) {
            val needsStrings = definition.fields.any { val v = it.initialValue as? StringEncodedValue; v != null && mapped(v.value) != v.value } ||
                definition.methods.any { m -> m.implementation?.instructions?.any { i -> val v = (i as? ReferenceInstruction)?.reference as? StringReference; v != null && mapped(v.string) != v.string } == true }
            val isSensitive = sensitiveClass(definition.type)
            val isForeground = definition.type == "Lorg/microg/gms/common/ForegroundServiceContext;"
            val isMcs = definition.type == "Lorg/microg/gms/gcm/McsService;"
            val hasNotificationSink = definition.methods.any { m -> m.implementation?.instructions?.any { i ->
                val ref = (i as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "Landroid/app/NotificationManager;" && ref.name == "notify"
            } == true }
            if (!needsStrings && !isSensitive && !isForeground && !isMcs && !hasNotificationSink) continue
            val cls = mutableClassDefBy(definition)
            for (field in cls.fields) {
                val old = (field.initialValue as? StringEncodedValue)?.value ?: continue
                val replacement = mapped(old)
                if (old != replacement) {
                    field.initialValue = MutableStringEncodedValue(ImmutableStringEncodedValue(replacement))
                    fieldChanges.add(field.definingClass + "->" + field.name + ":" + old + " => " + replacement)
                }
            }
            for (method in cls.methods.toList()) {
                val impl = method.implementation ?: continue
                val originalInstructions = impl.instructions.toList()
                for ((index, ins) in originalInstructions.withIndex()) {
                    val ref = (ins as? ReferenceInstruction)?.reference
                    if (ref is StringReference && ins is OneRegisterInstruction) {
                        val replacement = mapped(ref.string)
                        if (replacement != ref.string) {
                            method.replaceInstruction(index, "const-string/jumbo v${ins.registerA}, ${quoted(replacement)}")
                            stringChanges.add(methodId(method) + "@" + index + ":" + ref.string + " => " + replacement)
                        }
                    }
                    if (ref !is MethodReference) continue
                    if (isSensitive && ref.definingClass == "Landroid/util/Log;" && ref.returnType == "I" && ref.name in setOf("d", "i", "v", "w", "e", "wtf", "println")) {
                        // Drop the logging sink, including Throwable overloads which can carry raw
                        // tokens/URLs. Preserve any integer result register and control flow.
                        method.replaceInstruction(index, "nop")
                        val next = originalInstructions.getOrNull(index + 1)
                        if (next?.opcode == Opcode.MOVE_RESULT) {
                            method.replaceInstruction(index + 1, "const/16 v${(next as OneRegisterInstruction).registerA}, 0x0")
                        }
                        redactions.add(methodId(method) + "@" + index + ":Log." + ref.name)
                    }
                    if (ref.definingClass == "Landroid/app/NotificationManager;" && ref.name == "notify") {
                        val parameters = ref.parameterTypes.map { it.toString() }
                        check(parameters in listOf(listOf("I", "Landroid/app/Notification;"), listOf("Ljava/lang/String;", "I", "Landroid/app/Notification;")))
                        val call = when (ins) {
                            is FiveRegisterInstruction -> {
                                val registers = listOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG).take(ins.registerCount)
                                "invoke-static {" + registers.joinToString(", ") { "v$it" } + "}"
                            }
                            is RegisterRangeInstruction -> "invoke-static/range {v${ins.startRegister} .. v${ins.startRegister + ins.registerCount - 1}}"
                            else -> error("Unexpected notification instruction format")
                        }
                        method.replaceInstruction(index, "$call, $ENV->notify(Landroid/app/NotificationManager;${parameters.joinToString("")})V")
                        notificationHooks++
                    }
                    if (isForeground && method.name == "completeForegroundService" && ref.name == "startForeground" && ref.parameterTypes.map { it.toString() } == listOf("I", "Landroid/app/Notification;")) {
                        val regs = ins as? FiveRegisterInstruction ?: error("Unexpected foreground instruction format")
                        check(regs.registerCount == 3)
                        method.replaceInstruction(index, "invoke-static {v${regs.registerC}, v${regs.registerD}, v${regs.registerE}}, $ENV->startForeground(Landroid/app/Service;ILandroid/app/Notification;)V")
                        foregroundHooks++
                    }
                    if (isMcs && ref.definingClass == "Lokio/NioSystemFileSystem\$\$ExternalSyntheticApiModelOutline0;" && ref.name == "m" && ref.parameterTypes.map { it.toString() } == listOf("Landroid/app/AlarmManager;", "I", "J", "Landroid/app/PendingIntent;")) {
                        val call = when (ins) {
                            is FiveRegisterInstruction -> "invoke-static {v${ins.registerC}, v${ins.registerD}, v${ins.registerE}, v${ins.registerF}, v${ins.registerG}}"
                            is RegisterRangeInstruction -> "invoke-static/range {v${ins.startRegister} .. v${ins.startRegister + ins.registerCount - 1}}"
                            else -> error("Unexpected alarm instruction format")
                        }
                        method.replaceInstruction(index, "$call, $ENV->scheduleAlarm(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V")
                        alarmHooks++
                    }
                }
            }
        }
        // Keep token-bearing AuthResponse out of logs even when a caller outside the auth
        // package stringifies it. Data fields and authentication behavior remain unchanged.
        val authResponse = mutableClassDefBy("Lorg/microg/gms/auth/AuthResponse;")
        val originalToString = authResponse.methods.single { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
        val safeToString = MutableMethod(ImmutableMethod(originalToString.definingClass, originalToString.name,
            originalToString.parameters, originalToString.returnType, originalToString.accessFlags,
            originalToString.annotations, originalToString.hiddenApiRestrictions,
            ImmutableMethodImplementation(1, emptyList(), emptyList(), emptyList())))
        safeToString.addInstructions(0, "const-string v0, \"AuthResponse{redacted}\"\nreturn-object v0")
        authResponse.methods.remove(originalToString)
        authResponse.methods.add(safeToString)
        redactions.add("Lorg/microg/gms/auth/AuthResponse;->toString()Ljava/lang/String;:redacted")
        // Every manifest component gets its real backend resources/classloader at the Android
        // attachment boundary. No class/Binder descriptor relocation and no fake package/UID.
        for ((type, kind) in componentHooks) {
            val cls = mutableClassDefBy(type)
            fun hook(name: String, parameters: List<String>, body: String, access: Int) {
                val matching = cls.methods.filter { it.name == name && it.parameterTypes.map { p -> p.toString() } == parameters }
                check(matching.size <= 1)
                val method = matching.singleOrNull() ?: MutableMethod(ImmutableMethod(type, name,
                    parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, "V", access,
                    emptySet(), emptySet(), ImmutableMethodImplementation(parameters.size + 1, emptyList(), emptyList(), emptyList()))).also {
                        val regs = (0..parameters.size).joinToString(", ") { n -> "p$n" }
                        it.addInstructions(0, "invoke-super {$regs}, ${cls.superclass}->$name(${parameters.joinToString("")})V\nreturn-void")
                        cls.methods.add(it)
                    }
                check(method.implementation != null && method.accessFlags and AccessFlags.STATIC.value == 0)
                method.addInstructions(0, body)
                hooks.add(type + "->" + name + ":" + kind)
            }
            when (kind) {
                "activity", "service" -> hook("attachBaseContext", listOf(CONTEXT),
                    "invoke-static/range {p1 .. p1}, $ENV->wrap($CONTEXT)$CONTEXT\nmove-result-object p1", AccessFlags.PROTECTED.value)
                "provider" -> hook("attachInfo", listOf(CONTEXT, "Landroid/content/pm/ProviderInfo;"),
                    "invoke-static/range {p1 .. p1}, $ENV->wrap($CONTEXT)$CONTEXT\nmove-result-object p1", AccessFlags.PUBLIC.value)
                "receiver" -> hook("onReceive", listOf(CONTEXT, "Landroid/content/Intent;"),
                    "invoke-static/range {p1 .. p1}, $ENV->wrap($CONTEXT)$CONTEXT\nmove-result-object p1", AccessFlags.PUBLIC.value)
                else -> error("Unknown component kind")
            }
            if (kind == "activity") hook("onCreate", listOf("Landroid/os/Bundle;"),
                "invoke-static/range {p0 .. p0}, $ENV->prepareActivity(Landroid/app/Activity;)V", AccessFlags.PROTECTED.value)
        }
        check(foregroundHooks == 1) { "Expected exactly one foreground promotion call, found $foregroundHooks" }
        check(alarmHooks == 2) { "Expected two MCS exact alarm calls, found $alarmHooks" }
        check(notificationHooks > 0)
        check(stringChanges.isNotEmpty() && fieldChanges.isNotEmpty() && redactions.isNotEmpty())
        val report = "{" + listOf(
            "stringRewrites" to stringChanges, "fieldRewrites" to fieldChanges,
            "contextAndThemeHooks" to hooks, "redactedLogCalls" to redactions,
            "foregroundHooks" to listOf(foregroundHooks.toString()), "alarmHooks" to listOf(alarmHooks.toString()), "notificationHooks" to listOf(notificationHooks.toString())
        ).joinToString(",") { (key, values) -> quoted(key) + ":[" + values.joinToString(",") { quoted(it) } + "]" } + "}"
        File(System.getProperty("inhouse.backend.report") ?: error("Report path required")).writeText(report)
    }
}

// These seven Android component types also exist in the installed YouTube host.
// Their actual class/type references must move, including WorkManager-created Intents.
// Binder wire names and all other backend class names remain unchanged.
private val relocatedComponents = listOf(
    "androidx.room.MultiInstanceInvalidationService",
    "androidx.work.impl.background.systemalarm.RescheduleReceiver",
    "androidx.work.impl.background.systemjob.SystemJobService",
    "androidx.work.impl.diagnostics.DiagnosticsReceiver",
    "androidx.work.impl.foreground.SystemForegroundService",
    "androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver",
    "com.google.android.gms.common.api.GoogleApiActivity"
).associateWith { "inhouse.microg.module." + it }

fun main(args: Array<String>) {
    require(args.size == 2) { "Backend relocation requires input and output archives" }
    val input = File(args[0]); val output = File(args[1])
    val types = relocatedComponents.mapKeys { "L" + it.key.replace('.', '/') + ";" }
        .mapValues { "L" + it.value.replace('.', '/') + ";" }
    fun mapString(value: String) = relocatedComponents[value] ?: types[value] ?: value
    val rewriter = DexRewriter(object : RewriterModule() {
        override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> = Rewriter { type ->
            val prefix = type.takeWhile { it == '[' }
            prefix + (types[type.removePrefix(prefix)] ?: type.removePrefix(prefix))
        }
        override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> {
            val original = super.getInstructionRewriter(rewriters)
            return Rewriter { instruction ->
                val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference
                if (ref != null && mapString(ref.string) != ref.string) {
                    val register = (instruction as OneRegisterInstruction).registerA
                    val mapped = ImmutableStringReference(mapString(ref.string))
                    when (instruction.opcode) {
                        Opcode.CONST_STRING -> ImmutableInstruction21c(Opcode.CONST_STRING, register, mapped)
                        Opcode.CONST_STRING_JUMBO -> ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, register, mapped)
                        else -> error("Unexpected string instruction")
                    }
                } else original.rewrite(instruction)
            }
        }
        override fun getEncodedValueRewriter(rewriters: Rewriters): Rewriter<EncodedValue> {
            val original = super.getEncodedValueRewriter(rewriters)
            return Rewriter { value ->
                if (value is StringEncodedValue && mapString(value.value) != value.value)
                    ImmutableStringEncodedValue(mapString(value.value))
                else original.rewrite(value)
            }
        }
    })
    val container = DexFileFactory.loadDexContainer(input, Opcodes.getDefault())
    val dexNames = container.dexEntryNames.toSet()
    val found = mutableSetOf<String>()
    ZipFile(input).use { archive -> ZipOutputStream(output.outputStream()).use { target ->
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            target.putNextEntry(ZipEntry(entry.name))
            if (entry.name in dexNames) {
                val dex = container.getEntry(entry.name)!!.dexFile
                found.addAll(dex.classes.map { it.type }.filter { it in types })
                val store = MemoryDataStore()
                DexPool.writeTo(store, rewriter.dexFileRewriter.rewrite(dex))
                target.write(store.data)
                store.close()
            } else archive.getInputStream(entry).use { it.copyTo(target) }
            target.closeEntry()
        }
    } }
    check(found == types.keys) { "Unreviewed component relocation matches: " + found }
}
