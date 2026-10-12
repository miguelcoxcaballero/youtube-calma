package inhouse.microg.background

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.io.File

private const val SCHEDULER = "Linhouse/microg/bridge/CheckinJobScheduler;"
private const val NETWORK = "Linhouse/microg/bridge/CheckinJobNetwork;"
private const val CONTEXT = "Landroid/content/Context;"
private const val MCS = "Lorg/microg/gms/gcm/McsService;"
private const val MCS_COORDINATOR = "Linhouse/microg/bridge/McsSessionCoordinator;"

/** Inactive proposal. It is not compiled or selected by the normal backend pipeline. */
val backgroundCheckin = bytecodePatch(name = "Inhouse bounded background device registration proposal") {
    compatibleWith("app.revanced.android.gms"("0.3.13.2.250932"))
    execute {
        check(System.getProperty("inhouse.backgroundCheckin") == "true") {
            "Background checkin proposal requires explicit -Dinhouse.backgroundCheckin=true"
        }
        check(packageMetadata.versionCode == "250932004") { "Unreviewed backend version" }
        val changes = mutableListOf<String>()
        fun find(type: String, name: String, parameters: List<String>) = mutableClassDefBy(type).methods.single {
            it.name == name && it.parameterTypes.map { value -> value.toString() } == parameters
        }.also { check(it.implementation != null) }
        fun methodId(type: String, name: String) = "$type->$name"
        val receiver = find("Lorg/microg/gms/checkin/TriggerReceiver;", "onReceive", listOf(CONTEXT, "Landroid/content/Intent;"))
        // v0 is an existing local; incoming Context/Intent registers stay unchanged.
        check(receiver.implementation!!.registerCount > 3)
        receiver.addInstructions(0, """
            invoke-static/range {p1 .. p2}, $SCHEDULER->handleReceiver(${CONTEXT}Landroid/content/Intent;)Z
            move-result v0
            if-eqz v0, :inhouse_original_receiver
            return-void
            :inhouse_original_receiver
            nop
        """.trimIndent())
        changes.add("checkin.TriggerReceiver.onReceive: API35 scheduler gate")
        val schedule = find("Lorg/microg/gms/checkin/CheckinService;", "schedule", listOf(CONTEXT))
        check(schedule.implementation!!.registerCount > 1)
        schedule.addInstructions(0, """
            invoke-static/range {p0 .. p0}, $SCHEDULER->handleSchedule($CONTEXT)Z
            move-result v0
            if-eqz v0, :inhouse_original_schedule
            return-void
            :inhouse_original_schedule
            nop
        """.trimIndent())
        changes.add("checkin.CheckinService.schedule: API35 scheduler gate")
        val httpMethods = listOf(
            Triple("Lorg/microg/gms/checkin/CheckinClient;", "request", listOf("Lorg/microg/gms/checkin/CheckinRequest;")),
            Triple("Lorg/microg/gms/common/HttpFormClient;", "request", listOf("Ljava/lang/String;", "Lorg/microg/gms/common/HttpFormClient\$Request;", "Ljava/lang/Class;")),
            Triple("Lorg/microg/gms/people/PeopleManager;", "loadUserInfo", listOf(CONTEXT, "Landroid/accounts/Account;"))
        )
        for ((type, name, parameters) in httpMethods) {
            val method = find(type, name, parameters)
            var count = 0
            for ((index, instruction) in method.implementation!!.instructions.toList().withIndex().reversed()) {
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (ref.definingClass == "Ljava/net/URL;" && ref.name == "openConnection" && ref.parameterTypes.isEmpty()) {
                    check(ref.returnType == "Ljava/net/URLConnection;")
                    val regs = instruction as? FiveRegisterInstruction ?: error("Unreviewed URL instruction format")
                    check(regs.registerCount == 1)
                    method.replaceInstruction(index, "invoke-static {v${regs.registerC}}, $NETWORK->openConnection(Ljava/net/URL;)Ljava/net/URLConnection;")
                    count++
                    changes.add(methodId(type, name) + "@$index: job-only HTTP budget")
                }
            }
            check(count == 1) { "Expected one URL.openConnection in $type->$name, found $count" }
        }
        for ((name, parameters) in listOf(
            "checkin" to listOf(CONTEXT, "Z"),
            "handleResponse" to listOf(CONTEXT, "Lorg/microg/gms/checkin/CheckinResponse;")
        )) {
            find("Lorg/microg/gms/checkin/CheckinManager;", name, parameters)
                .addInstructions(0, "invoke-static {}, $NETWORK->checkpoint()V")
            changes.add("checkin.CheckinManager.$name: job-only cancellation checkpoint")
        }
        val people = find("Lorg/microg/gms/people/PeopleManager;", "loadUserInfo", listOf(CONTEXT, "Landroid/accounts/Account;"))
        val writes = people.implementation!!.instructions.toList().withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lorg/microg/gms/people/DatabaseHelper;" && ref.name == "putOwner" &&
                ref.parameterTypes.map { it.toString() } == listOf("Landroid/content/ContentValues;")
        }
        check(writes.size == 1) { "Unreviewed PeopleManager write count" }
        people.addInstructions(writes.single().index, "invoke-static {}, $NETWORK->checkpoint()V")
        changes.add("people.PeopleManager.loadUserInfo: job-only checkpoint before profile write")
        check(changes.size == 8)
        // MCS starts only from genuine BOOT_COMPLETED or an explicitly visible Activity.
        // Checkin jobs signal this already-promoted service; they never start a new FGS.
        val gcmReceiver = find("Lorg/microg/gms/gcm/TriggerReceiver;", "onReceive", listOf(CONTEXT, "Landroid/content/Intent;"))
        check(gcmReceiver.implementation!!.registerCount > 3)
        gcmReceiver.addInstructions(0, """
            invoke-static/range {p1 .. p2}, $MCS_COORDINATOR->prepareBootReceiver(${CONTEXT}Landroid/content/Intent;)Z
            move-result v0
            if-eqz v0, :inhouse_original_gcm_receiver
            return-void
            :inhouse_original_gcm_receiver
            nop
        """.trimIndent())
        changes.add("gcm.TriggerReceiver.onReceive: genuine boot start before checkin job")
        val promotion = find("Lorg/microg/gms/common/ForegroundServiceContext;", "completeForegroundService",
            listOf("Landroid/app/Service;", "Landroid/content/Intent;", "Ljava/lang/String;"))
        val promotions = promotion.implementation!!.instructions.toList().withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Linhouse/microg/bridge/BackendEnvironment;" && ref.name == "startForeground"
        }
        check(promotions.size == 1)
        val promotionInstruction = promotions.single().value as? FiveRegisterInstruction ?: error("Unreviewed promotion format")
        check(promotionInstruction.registerCount == 3)
        promotion.replaceInstruction(promotions.single().index,
            "invoke-static {v${promotionInstruction.registerC}, v${promotionInstruction.registerD}, v${promotionInstruction.registerE}}, $MCS_COORDINATOR->startForeground(Landroid/app/Service;ILandroid/app/Notification;)V")
        changes.add("ForegroundServiceContext.completeForegroundService: record MCS only after actual promotion")
        val start = find(MCS, "onStartCommand", listOf("Landroid/content/Intent;", "I", "I"))
        check(start.implementation!!.registerCount in 5..16)
        val completed = start.implementation!!.instructions.toList().withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lorg/microg/gms/common/ForegroundServiceContext;" && ref.name == "completeForegroundService"
        }
        check(completed.size == 1)
        start.addInstructions(completed.single().index + 1, """
            invoke-static {p0, p1, p3}, $MCS_COORDINATOR->handleStart(${MCS}Landroid/content/Intent;I)Z
            move-result v0
            if-eqz v0, :inhouse_original_mcs_start
            const/4 v0, 0x3
            return v0
            :inhouse_original_mcs_start
            nop
        """.trimIndent())
        changes.add("McsService.onStartCommand: wait for real registration without zero-ID login")
        find(MCS, "onDestroy", emptyList()).addInstructions(0,
            "invoke-static/range {p0 .. p0}, $MCS_COORDINATOR->onDestroy($MCS)V")
        changes.add("McsService.onDestroy: unregister stopped foreground session")
        val mcs = mutableClassDefBy(MCS)
        check(mcs.methods.none { it.name == "inhouseRequestReconnect" })
        for ((name, type) in listOf("rootHandler" to "Landroid/os/Handler;",
            "wakeLock" to "Landroid/os/PowerManager\$WakeLock;", "connectIntent" to "Landroid/content/Intent;")) {
            check(mcs.fields.single { it.name == name }.type == type)
        }
        val reconnect = MutableMethod(ImmutableMethod(MCS, "inhouseRequestReconnect", emptyList(), "Z",
            AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(7, emptyList(), emptyList(), emptyList())))
        reconnect.addInstructions(0, """
            const-class v0, $MCS
            monitor-enter v0
            :inhouse_try_start
            invoke-static {p0}, $MCS_COORDINATOR->isActive($MCS)Z
            move-result v1
            if-eqz v1, :inhouse_no_reconnect
            invoke-static {p0}, Lorg/microg/gms/checkin/LastCheckinInfo;->read($CONTEXT)Lorg/microg/gms/checkin/LastCheckinInfo;
            move-result-object v1
            invoke-virtual {v1}, Lorg/microg/gms/checkin/LastCheckinInfo;->getAndroidId()J
            move-result-wide v2
            const-wide/16 v4, 0x0
            cmp-long v1, v2, v4
            if-eqz v1, :inhouse_no_reconnect
            sget-object v1, $MCS->rootHandler:Landroid/os/Handler;
            if-eqz v1, :inhouse_pending_reconnect
            sget-object v2, $MCS->wakeLock:Landroid/os/PowerManager${'$'}WakeLock;
            if-eqz v2, :inhouse_no_reconnect
            const-wide/16 v3, 0x1388
            invoke-virtual {v2, v3, v4}, Landroid/os/PowerManager${'$'}WakeLock;->acquire(J)V
            const/16 v2, 0x28
            const-string v3, "inhouse-registration-ready"
            invoke-virtual {v1, v2, v3}, Landroid/os/Handler;->obtainMessage(ILjava/lang/Object;)Landroid/os/Message;
            move-result-object v2
            invoke-virtual {v1, v2}, Landroid/os/Handler;->sendMessage(Landroid/os/Message;)Z
            move-result v1
            goto :inhouse_reconnect_done
            :inhouse_pending_reconnect
            new-instance v1, Landroid/content/Intent;
            const-string v2, "org.microg.gms.gcm.mcs.CONNECT"
            invoke-direct {v1, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
            iput-object v1, p0, $MCS->connectIntent:Landroid/content/Intent;
            const/4 v1, 0x1
            goto :inhouse_reconnect_done
            :inhouse_no_reconnect
            const/4 v1, 0x0
            :inhouse_reconnect_done
            :inhouse_try_end
            monitor-exit v0
            return v1
            .catchall {:inhouse_try_start .. :inhouse_try_end} :inhouse_reconnect_error
            :inhouse_reconnect_error
            move-exception v1
            monitor-exit v0
            throw v1
        """.trimIndent())
        mcs.methods.add(reconnect)
        changes.add("McsService.inhouseRequestReconnect: guarded original protocol message, no service launch")
        check(changes.size == 13)
        val report = File(System.getProperty("inhouse.backgroundCheckin.report") ?: error("Report path required"))
        fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        report.writeText("{\"proposalOnly\":true,\"optIn\":true,\"changes\":[" + changes.joinToString(",") { q(it) } + "]}")
    }
}
